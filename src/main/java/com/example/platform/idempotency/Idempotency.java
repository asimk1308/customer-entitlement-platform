package com.example.platform.idempotency;

import com.example.platform.security.Principal;
import com.example.platform.store.Store;
import com.example.platform.upstream.UpstreamFailure;
import com.example.platform.web.ApiProblem;
import com.example.platform.web.ApiProblem.Code;
import com.example.platform.web.CorrelationFilter;
import com.example.platform.web.Hashes;
import com.example.platform.web.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Makes POSTs safe to retry. See the outcomes table on `addCaseComment` in openapi.yaml. */
@Component
public class Idempotency {
    static final Duration TTL = Duration.ofHours(24);
    static final Duration LEASE = Duration.ofSeconds(30);
    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

    /**
     * @param externalId sent upstream, and the same on every retry, so the upstream can deduplicate
     * @param resuming an earlier attempt may have applied this write upstream
     * @param lockedUntil identifies this holder of the lease, as each new holder sets a later time
     */
    public record Claim(String principal, String route, String key, String externalId, boolean resuming,
                        Integer expectedVersion, Stored replay, String lockedUntil) {}

    public record Stored(int status, String body, Map<String, String> headers) {}

    /** @param events the outbox payloads, already shaped as their contract says */
    public record WriteResult(int status, Object body, String action, String caseId, String accountId,
                              Map<String, String> headers, Map<String, Object> detail, List<Event> events) {}

    public record Event(String id, String type, Object payload) {}

    /** @param replayed the response came from the stored result of an earlier request */
    public record Response(int status, String body, Map<String, String> headers, boolean replayed) {}

    private final Store store;
    private final JsonMapper json;

    public Idempotency(Store store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    public static String principalKey(Principal principal) {
        return principal.subject() + "|" + (principal.agent() == null ? "" : principal.agent());
    }

    public String requestHash(Map<String, Object> payload) {
        return Hashes.sha256Hex(json.writeValueAsString(new TreeMap<>(payload)));
    }

    private record Row(String requestHash, String state, boolean upstreamAttempted, Integer expectedVersion,
                       String lockedUntil, Integer responseStatus, String responseBody, String responseHeaders) {}

    public Claim claim(Instant now, String principal, String route, String key, String digest) {
        String externalId = Hashes.sha256Hex(principal + "|" + route + "|" + key).substring(0, 32);
        String nowText = Times.stored(now);
        String lease = Times.stored(now.plus(LEASE));
        Claim fresh = new Claim(principal, route, key, externalId, false, null, null, lease);
        return store.transaction.execute(status -> {
            store.db.sql("DELETE FROM idempotency_keys WHERE principal = ? AND route = ? AND key = ? AND expires_at <= ?")
                    .params(principal, route, key, nowText).update();
            int inserted = store.db.sql("INSERT OR IGNORE INTO idempotency_keys "
                            + "(principal, route, key, request_hash, state, locked_until, expires_at) "
                            + "VALUES (?, ?, ?, ?, 'in_progress', ?, ?)")
                    .params(principal, route, key, digest, lease, Times.stored(now.plus(TTL)))
                    .update();
            if (inserted == 1) {
                return fresh;
            }
            Row row = store.db.sql("SELECT request_hash, state, upstream_attempted, expected_version, locked_until, "
                            + "response_status, response_body, response_headers FROM idempotency_keys "
                            + "WHERE principal = ? AND route = ? AND key = ?")
                    .params(principal, route, key)
                    .query((rs, index) -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3) != 0,
                            nullableInt(rs, 4), rs.getString(5), nullableInt(rs, 6), rs.getString(7), rs.getString(8)))
                    .single();
            if (!row.requestHash().equals(digest)) {
                throw ApiProblem.of(Code.IDEMPOTENCY_KEY_REUSED, "Use a new key for a different request.");
            }
            if (row.state().equals("completed")) {
                Stored replay = new Stored(row.responseStatus(), row.responseBody(),
                        json.readValue(row.responseHeaders(), HEADERS));
                return new Claim(principal, route, key, externalId, false, null, replay, null);
            }
            if (row.lockedUntil().compareTo(nowText) > 0) {
                throw ApiProblem.of(Code.IDEMPOTENCY_IN_PROGRESS, "Retry shortly with the same key.");
            }
            store.db.sql("UPDATE idempotency_keys SET locked_until = ? WHERE principal = ? AND route = ? AND key = ?")
                    .params(lease, principal, route, key).update();
            return new Claim(principal, route, key, externalId, row.upstreamAttempted(), row.expectedVersion(), null, lease);
        });
    }

    private static Integer nullableInt(ResultSet row, int column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    /**
     * Must be committed before the upstream call: a crash after it could otherwise leave a write
     * applied upstream with no record that it might have been.
     */
    public void markAttempted(Claim claimed, Integer expectedVersion) {
        store.db.sql("UPDATE idempotency_keys SET upstream_attempted = 1, expected_version = ? "
                        + "WHERE principal = ? AND route = ? AND key = ?")
                .params(expectedVersion, claimed.principal(), claimed.route(), claimed.key())
                .update();
    }

    /**
     * The write was definitely not applied, so forget the key. A retry starts from scratch. Does nothing
     * if a retry has taken over the lease, because its attempt may have succeeded.
     */
    private void release(Claim claimed) {
        store.db.sql("DELETE FROM idempotency_keys WHERE principal = ? AND route = ? AND key = ? "
                        + "AND state = 'in_progress' AND locked_until = ?")
                .params(claimed.principal(), claimed.route(), claimed.key(), claimed.lockedUntil()).update();
    }

    /** The outcome is unknown. Keep the record, so a retry resumes, but let it start right away. */
    private void unlock(Claim claimed, Instant now) {
        store.db.sql("UPDATE idempotency_keys SET locked_until = ? WHERE principal = ? AND route = ? AND key = ? "
                        + "AND state = 'in_progress' AND locked_until = ?")
                .params(Times.stored(now), claimed.principal(), claimed.route(), claimed.key(), claimed.lockedUntil())
                .update();
    }

    /**
     * The response, audit row and outbox rows commit together or not at all. If this process dies
     * before here, a retry resumes and the upstream deduplicates, so nothing is lost or doubled.
     *
     * <p>A stalled request and the retry that took over its lease can both reach this point with the
     * same deduplicated write. The first to arrive commits, and the other returns what it stored.
     */
    private Response commit(Instant now, Claim claimed, Principal principal, WriteResult result) {
        String body = json.writeValueAsString(result.body());
        Response own = new Response(result.status(), body, result.headers(), false);
        return store.transaction.execute(status -> {
            int won = store.db.sql("UPDATE idempotency_keys SET state = 'completed', response_status = ?, response_body = ?, "
                            + "response_headers = ? WHERE principal = ? AND route = ? AND key = ? AND state = 'in_progress'")
                    .params(result.status(), body, json.writeValueAsString(result.headers()),
                            claimed.principal(), claimed.route(), claimed.key())
                    .update();
            if (won == 0) {
                return store.db.sql("SELECT response_status, response_body, response_headers FROM idempotency_keys "
                                + "WHERE principal = ? AND route = ? AND key = ?")
                        .params(claimed.principal(), claimed.route(), claimed.key())
                        .query((row, index) -> new Response(row.getInt(1), row.getString(2),
                                json.readValue(row.getString(3), HEADERS), true))
                        .optional()
                        .orElse(own);
            }
            writeAudit(now, principal, result.action(), result.caseId(), result.accountId(), "success", result.detail());
            result.events().forEach(event -> enqueue(now, event));
            return own;
        });
    }

    private void writeAudit(Instant now, Principal principal, String action, String caseId, String accountId,
                            String outcome, Map<String, Object> detail) {
        store.audit(now, principal.subject(), principal.agent(), action, caseId, accountId, outcome,
                json.writeValueAsString(detail));
    }

    private void enqueue(Instant now, Event event) {
        record Subscription(String id, String events) {}
        List<Subscription> subscriptions = store.db.sql("SELECT id, events FROM webhook_subscriptions WHERE active = 1")
                .query((row, index) -> new Subscription(row.getString(1), row.getString(2))).list();
        for (Subscription subscription : subscriptions) {
            List<String> wanted = json.readValue(subscription.events(), new TypeReference<List<String>>() {});
            if (wanted.contains(event.type())) {
                store.db.sql("INSERT INTO outbox_events (event_id, subscription_id, payload, correlation_id, status, "
                                + "next_attempt_at, created_at) VALUES (?, ?, ?, ?, 'pending', ?, ?)")
                        .params(event.id(), subscription.id(), json.writeValueAsString(event.payload()),
                                MDC.get(CorrelationFilter.CORRELATION_ID), Times.stored(now), Times.stored(now))
                        .update();
            }
        }
    }

    public Response run(Instant now, Principal principal, String route, String key, Map<String, Object> payload,
                        String auditAction, String auditCaseId, Function<Claim, WriteResult> action) {
        Claim claimed = claim(now, principalKey(principal), route, key, requestHash(payload));
        if (claimed.replay() != null) {
            Stored replay = claimed.replay();
            return new Response(replay.status(), replay.body(), replay.headers(), true);
        }
        WriteResult result;
        try {
            result = action.apply(claimed);
        } catch (ApiProblem problem) {
            release(claimed);
            writeAudit(now, principal, auditAction, auditCaseId, null, "rejected:" + problem.code.wire(), Map.of());
            throw problem;
        } catch (UpstreamFailure failure) {
            unlock(claimed, now);
            writeAudit(now, principal, auditAction, auditCaseId, null, "upstream_failure", Map.of());
            throw failure;
        }
        return commit(now, claimed, principal, result);
    }
}
