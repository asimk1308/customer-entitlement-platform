package com.example.platform.upstream;

import com.example.platform.Settings;
import com.example.platform.store.Store;
import com.example.platform.upstream.Adapters.UpstreamDataError;
import com.example.platform.upstream.Models.Case;
import com.example.platform.upstream.Models.Comment;
import com.example.platform.upstream.Models.Entitlement;
import com.example.platform.upstream.Models.EntitlementView;
import com.example.platform.upstream.Models.Usage;
import com.example.platform.web.Concurrent;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three source systems as the rest of the service sees them: internal models or UpstreamFailure,
 * never raw payloads.
 */
@Component
public class Sources {
    private static final Logger LOG = LoggerFactory.getLogger(Sources.class);
    // Parameterised, so an account ID can never change the shape of the query.
    private static final String LATEST_USAGE_SQL = "SELECT month, protected_tb, api_calls, active_users FROM usage "
            + "WHERE account_id = ? ORDER BY month DESC LIMIT 1";

    /** The case changed after we read it. */
    public static class VersionConflict extends RuntimeException {}

    private final JsonMapper json;
    private final Store store;
    private final Clock clock;
    private final Duration entitlementTtl;
    private final UpstreamClient cases;
    private final UpstreamClient usage;
    // A breaker each, or a healthy CSV keeps resetting the failure count of a hung feed.
    private final UpstreamClient entitlementsCsv;
    private final UpstreamClient entitlementsXml;
    private final Map<String, String> crosswalk;
    private final AtomicReference<CompletableFuture<EntitlementView>> refreshing = new AtomicReference<>();
    private volatile EntitlementView cached;

    public Sources(JsonMapper json, Store store, Clock clock, Settings settings) {
        this.json = json;
        this.store = store;
        this.clock = clock;
        this.entitlementTtl = settings.entitlementTtl();
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.cases = new UpstreamClient("cases", http, settings.upstreamUrl(), settings.cases());
        this.usage = new UpstreamClient("usage", http, settings.upstreamUrl(), settings.usage());
        this.entitlementsCsv = new UpstreamClient("entitlements", http, settings.upstreamUrl(), settings.entitlements());
        this.entitlementsXml = new UpstreamClient("entitlements", http, settings.upstreamUrl(), settings.entitlements());
        this.crosswalk = store.crosswalk(Adapters.ENTITLEMENTS_CSV);
    }

    // ---- Cases ----

    private <T> T parseCases(java.util.function.Supplier<T> parser) {
        try {
            return parser.get();
        } catch (UpstreamDataError | JacksonException error) {
            LOG.atError().addKeyValue("upstream", "cases").addKeyValue("error", error.getClass().getSimpleName())
                    .log("upstream_bad_data");
            throw new UpstreamFailure("cases", "error");
        }
    }

    /**
     * Fetches everything and filters locally, which is only acceptable for a mock this size.
     * DECISIONS.md covers what replaces it at real volume.
     */
    public List<Case> listCases() {
        HttpResponse<String> response = cases.request("GET", "/cases", true, null);
        if (response.statusCode() != 200) {
            throw new UpstreamFailure("cases", "error");
        }
        return parseCases(() -> {
            List<Case> parsed = new ArrayList<>();
            json.readTree(response.body()).required("records").forEach(raw -> parsed.add(Adapters.parseCase(raw)));
            return parsed;
        });
    }

    /** Returns null if there is no such case. */
    public Case getCase(String caseId) {
        HttpResponse<String> response = cases.request("GET", "/cases/" + caseId, true, null);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new UpstreamFailure("cases", "error");
        }
        return parseCases(() -> Adapters.parseCase(json.readTree(response.body())));
    }

    // Writes are safe to retry only because each carries an external ID the upstream deduplicates on.
    public Comment addComment(String caseId, String externalId, String author, String agent, String body, String visibility) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("externalId", externalId);
        payload.put("author", author);
        payload.put("agent", agent);
        payload.put("body", body);
        payload.put("visibility", visibility);
        HttpResponse<String> response =
                cases.request("POST", "/cases/" + caseId + "/comments", true, json.writeValueAsString(payload));
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new UpstreamFailure("cases", "error");
        }
        return parseCases(() -> Adapters.parseComment(json.readTree(response.body())));
    }

    public Case transition(String caseId, String externalId, String to, int expectedVersion, String reason, String justification) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("externalId", externalId);
        payload.put("to", to);
        payload.put("expectedVersion", expectedVersion);
        payload.put("reason", reason);
        payload.put("justification", justification);
        HttpResponse<String> response =
                cases.request("POST", "/cases/" + caseId + "/transition", true, json.writeValueAsString(payload));
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() == 409) {
            throw new VersionConflict();
        }
        if (response.statusCode() != 200) {
            throw new UpstreamFailure("cases", "error");
        }
        return parseCases(() -> Adapters.parseCase(json.readTree(response.body())));
    }

    // ---- Usage ----

    /** Returns null if the warehouse has no rows for this account. */
    public Usage latestUsage(String accountId) {
        Map<String, Object> statement = Map.of("statement", LATEST_USAGE_SQL, "bindings", List.of(accountId));
        // It's a read, despite the POST.
        HttpResponse<String> response =
                usage.request("POST", "/warehouse/statements", true, json.writeValueAsString(statement));
        if (response.statusCode() != 200) {
            throw new UpstreamFailure("usage", "error");
        }
        try {
            return Adapters.parseLatestUsage(json.readTree(response.body()));
        } catch (UpstreamDataError | JacksonException error) {
            throw new UpstreamFailure("usage", "error");
        }
    }

    // ---- Entitlements ----

    private boolean isFresh(EntitlementView view) {
        return view != null && Duration.between(view.asOf(), clock.instant()).compareTo(entitlementTtl) < 0;
    }

    /**
     * Serves from a short in-memory cache, refreshes from the source when that expires, and falls back
     * to the last-known-good snapshot if the refresh fails.
     *
     * @throws UpstreamFailure only if the source is down and no snapshot exists
     */
    public EntitlementView loadEntitlements() {
        if (isFresh(cached)) {
            return cached;
        }
        // One refresh at a time, so that a cache expiry doesn't send a burst of identical requests.
        // Everyone waiting shares its outcome, including a failure. Waiting for a turn to retry instead
        // would queue requests behind each other for as long as the source is down.
        CompletableFuture<EntitlementView> mine = new CompletableFuture<>();
        CompletableFuture<EntitlementView> running = refreshing.compareAndExchange(null, mine);
        if (running != null) {
            return Concurrent.await(running);
        }
        try {
            EntitlementView view = isFresh(cached) ? cached : refreshEntitlements();
            mine.complete(view);
            return view;
        } catch (RuntimeException failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            refreshing.set(null);
        }
    }

    private EntitlementView refreshEntitlements() {
        Map<String, Entitlement> fresh;
        try {
            fresh = fetchEntitlements();
        } catch (UpstreamFailure failure) {
            return fallback(failure);
        }
        Instant asOf = clock.instant();
        EntitlementView view = new EntitlementView(fresh, asOf, null);
        cached = view;
        store.saveSnapshot(fresh, asOf);
        return view;
    }

    private Map<String, Entitlement> fetchEntitlements() {
        CompletableFuture<HttpResponse<String>> csv = Concurrent.supply(
                () -> entitlementsCsv.request("GET", "/entitlements/export.csv", true, null));
        CompletableFuture<HttpResponse<String>> xml = Concurrent.supply(
                () -> entitlementsXml.request("GET", "/entitlements/feed.xml", true, null));
        CompletableFuture.allOf(csv, xml).exceptionally(ignored -> null).join();
        HttpResponse<String> csvResponse = Concurrent.await(csv);
        HttpResponse<String> xmlResponse = Concurrent.await(xml);
        if (csvResponse.statusCode() != 200 || xmlResponse.statusCode() != 200) {
            throw new UpstreamFailure("entitlements", "error");
        }
        try {
            var csvRows = Adapters.parseEntitlementCsv(csvResponse.body());
            var xmlRows = Adapters.parseEntitlementXml(xmlResponse.body());
            // A broken export can still answer 200. Accepting it would replace the snapshot with
            // nothing, and "no such customer" would look like a real answer.
            if (csvRows.isEmpty() || xmlRows.isEmpty()) {
                throw new UpstreamDataError("empty entitlement source");
            }
            return Adapters.mergeEntitlements(csvRows, xmlRows, crosswalk);
        } catch (UpstreamDataError error) {
            LOG.atError().addKeyValue("upstream", "entitlements").addKeyValue("error", error.getMessage())
                    .log("upstream_bad_data");
            throw new UpstreamFailure("entitlements", "error");
        }
    }

    private EntitlementView fallback(UpstreamFailure failure) {
        Store.Snapshot snapshot = store.loadSnapshot();
        if (snapshot == null) {
            throw failure;
        }
        LOG.atWarn().addKeyValue("reason", failure.reason).log("entitlements_using_snapshot");
        return new EntitlementView(snapshot.entitlements(), snapshot.asOf(), failure.reason);
    }
}
