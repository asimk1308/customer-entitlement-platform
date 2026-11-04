package com.example.platform.api;

import com.example.platform.security.Principal;
import com.example.platform.security.RequiresScope;
import com.example.platform.upstream.Models.Case;
import com.example.platform.upstream.Models.Entitlement;
import com.example.platform.upstream.Models.EntitlementView;
import com.example.platform.upstream.Sources;
import com.example.platform.upstream.UpstreamFailure;
import com.example.platform.web.ApiProblem;
import com.example.platform.web.ApiProblem.Code;
import com.example.platform.web.Cursors;
import com.example.platform.web.Cursors.SortKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CaseController {
    private static final Logger LOG = LoggerFactory.getLogger(CaseController.class);
    private static final Map<String, Integer> PRIORITY_RANK = Map.of("P1", 1, "P2", 2, "P3", 3, "P4", 4);
    private static final String ACCOUNT = "ACC-[0-9]{1,10}";
    private static final int DEFAULT_LIMIT = 25;
    private static final String DEFAULT_SORT = "-openedAt";
    // The deprecated `account` parameter: announced 2026-11-01, removed 2027-06-30.
    private static final String DEPRECATION = "@" + Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();
    private static final String SUNSET = "Wed, 30 Jun 2027 00:00:00 GMT";

    record Page(int limit, boolean hasMore, String nextCursor) {}

    record CaseList(List<Views.CaseSummary> data, Page page, boolean degraded, List<Views.DegradedSource> degradedSources) {}

    /** @param view null if entitlements are down, have no snapshot, and the request can do without them */
    record LoadedEntitlements(EntitlementView view, List<Views.DegradedSource> sources) {
        Entitlement of(String accountId) {
            return view == null ? null : view.entitlements().get(accountId);
        }
    }

    private final Sources sources;
    private final Cursors cursors;
    private final Clock clock;

    CaseController(Sources sources, Cursors cursors, Clock clock) {
        this.sources = sources;
        this.cursors = cursors;
        this.clock = clock;
    }

    /**
     * Tier and renewal are required if the request filters on them. Answering such a request with
     * partial data would look like a complete answer that happens to have fewer rows.
     */
    static LoadedEntitlements loadEntitlements(Sources sources, boolean needed) {
        EntitlementView view;
        try {
            view = sources.loadEntitlements();
        } catch (UpstreamFailure failure) {
            if (needed) {
                throw Views.unavailable(failure, "Tier and renewal filters need entitlement data, which is unavailable.");
            }
            LOG.atWarn().addKeyValue("reason", failure.reason).log("entitlements_unavailable");
            return new LoadedEntitlements(null, List.of(Views.degraded("entitlements", failure.reason, null)));
        }
        if (view.degradedReason() != null) {
            return new LoadedEntitlements(view, List.of(Views.degraded("entitlements", view.degradedReason(), view.asOf())));
        }
        return new LoadedEntitlements(view, List.of());
    }

    /**
     * A number and the case ID, where the number is negated for descending order. Ties always break on
     * ID, so the order is total and a keyset cursor can't skip rows.
     */
    static SortKey sortKey(Case item, String sort) {
        long value = sort.endsWith("priority") ? PRIORITY_RANK.get(item.priority()) : item.openedAt().getEpochSecond();
        return new SortKey(sort.startsWith("-") ? -value : value, item.id());
    }

    private static void rejectDuplicates(String field, List<String> values) {
        if (values != null && new HashSet<>(values).size() != values.size()) {
            throw ApiProblem.invalid("query." + field, "Values must be unique.");
        }
    }

    /** Spring turns `?limit=` into "not supplied", but the contract says an empty value isn't a number. */
    private static void rejectEmptyNumbers(HttpServletRequest request, String... names) {
        for (String name : names) {
            if ("".equals(request.getParameter(name))) {
                throw ApiProblem.invalid("query." + name, "Must be a number.");
            }
        }
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    @RequiresScope("cases:read")
    @GetMapping("/v1/cases")
    ResponseEntity<CaseList> listCases(
            Principal principal,
            HttpServletRequest request,
            @RequestParam(required = false) @Size(max = 5)
                    List<@Pattern(regexp = "open|in_progress|escalated|resolved|closed", message = "Not a known status.") String> status,
            @RequestParam(required = false) @Size(max = 4)
                    List<@Pattern(regexp = "P[1-4]", message = "Not a known priority.") String> priority,
            @RequestParam(required = false) @Pattern(regexp = ACCOUNT, message = "Must look like ACC-007.") String accountId,
            @RequestParam(required = false) @Pattern(regexp = ACCOUNT, message = "Must look like ACC-007.") String account,
            @RequestParam(required = false) @Min(0) @Max(8760) Integer minAgeHours,
            @RequestParam(required = false) @Size(max = 3)
                    List<@Pattern(regexp = "Platinum|Gold|Silver", message = "Not a known tier.") String> supportTier,
            @RequestParam(required = false) @Min(0) @Max(730) Integer renewalWithinDays,
            @RequestParam(required = false) @Pattern(regexp = "-?(openedAt|priority)", message = "Not a known sort.") String sort,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) @Size(max = 512) String cursor) {
        rejectEmptyNumbers(request, "minAgeHours", "renewalWithinDays", "limit");
        rejectDuplicates("status", status);
        rejectDuplicates("priority", priority);
        rejectDuplicates("supportTier", supportTier);
        Instant now = clock.instant();
        String accountFilter = accountId != null ? accountId : account;
        String order = sort == null ? DEFAULT_SORT : sort;
        int size = limit == null ? DEFAULT_LIMIT : limit;

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("status", sorted(status));
        filters.put("priority", sorted(priority));
        filters.put("accountId", accountFilter);
        filters.put("minAgeHours", minAgeHours);
        filters.put("supportTier", sorted(supportTier));
        filters.put("renewalWithinDays", renewalWithinDays);
        filters.put("sort", order);
        SortKey after = cursor == null ? null : cursors.decode(cursor, filters);

        List<Case> cases;
        try {
            cases = sources.listCases();
        } catch (UpstreamFailure failure) {
            throw Views.upstreamProblem(failure);
        }

        boolean needsEntitlements = (supportTier != null && !supportTier.isEmpty()) || renewalWithinDays != null;
        LoadedEntitlements loaded = loadEntitlements(sources, needsEntitlements);

        List<Case> rows = new ArrayList<>();
        for (Case item : cases) {
            if (matches(item, principal, status, priority, accountFilter, minAgeHours, supportTier, renewalWithinDays, loaded, now)) {
                rows.add(item);
            }
        }
        rows.sort(Comparator.comparing(item -> sortKey(item, order)));
        if (after != null) {
            rows.removeIf(item -> sortKey(item, order).compareTo(after) <= 0);
        }
        boolean hasMore = rows.size() > size;
        List<Case> page = rows.subList(0, Math.min(size, rows.size()));
        String nextCursor = hasMore ? cursors.encode(sortKey(page.getLast(), order), filters) : null;

        ResponseEntity.BodyBuilder response = Views.json(200);
        if (account != null) {
            response.header("Deprecation", DEPRECATION).header("Sunset", SUNSET);
        }
        return response.body(new CaseList(
                page.stream().map(item -> Views.summary(item, loaded.of(item.accountId()))).toList(),
                new Page(size, hasMore, nextCursor),
                !loaded.sources().isEmpty(),
                loaded.sources()));
    }

    private static boolean matches(Case item, Principal principal, List<String> status, List<String> priority,
                                   String account, Integer minAgeHours, List<String> tiers, Integer renewalWithinDays,
                                   LoadedEntitlements loaded, Instant now) {
        if (!principal.canSeeAccount(item.accountId())) {
            return false;
        }
        if (status != null && !status.contains(item.status())) {
            return false;
        }
        if (priority != null && !priority.contains(item.priority())) {
            return false;
        }
        if (account != null && !item.accountId().equals(account)) {
            return false;
        }
        if (minAgeHours != null && Duration.between(item.openedAt(), now).compareTo(Duration.ofHours(minAgeHours)) < 0) {
            return false;
        }
        Entitlement entitlement = loaded.of(item.accountId());
        if (tiers != null && (entitlement == null || !tiers.contains(entitlement.tier()))) {
            return false;
        }
        if (renewalWithinDays != null) {
            if (entitlement == null) {
                return false;
            }
            long days = ChronoUnit.DAYS.between(LocalDate.ofInstant(now, ZoneOffset.UTC), entitlement.renewal());
            if (days < 0 || days > renewalWithinDays) {
                return false;
            }
        }
        return true;
    }

    @RequiresScope("cases:read")
    @GetMapping("/v1/cases/{caseId}")
    ResponseEntity<Views.CaseDetail> getCase(
            Principal principal,
            @PathVariable @Pattern(regexp = "CS-[0-9]{1,10}", message = "Must look like CS-10231.") String caseId) {
        Case item;
        try {
            item = sources.getCase(caseId);
        } catch (UpstreamFailure failure) {
            throw Views.upstreamProblem(failure);
        }
        if (item == null || !principal.canSeeAccount(item.accountId())) {
            throw ApiProblem.of(Code.NOT_FOUND);
        }
        LoadedEntitlements loaded = loadEntitlements(sources, false);
        return Views.json(200)
                .header("ETag", Views.etag(item))
                .header("Cache-Control", loaded.sources().isEmpty() ? "private" : "no-store")
                .header("Vary", "Authorization")
                .body(Views.detail(item, loaded.of(item.accountId()), loaded.sources()));
    }
}
