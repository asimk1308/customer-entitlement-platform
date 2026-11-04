package com.example.platform.upstream;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Internal models. Nothing beyond the adapters ever sees an upstream's own field names. */
public final class Models {
    private Models() {}

    public record Comment(String id, String author, String agent, String body, String visibility, Instant createdAt) {}

    public record Case(String id, String accountId, String priority, String status, Instant openedAt,
                       String subject, int version, List<Comment> comments) {}

    public record Issue(String code, String detail) {}

    public record Entitlement(String accountId, String name, String tier, LocalDate renewal, Long arrUsd,
                              List<Issue> issues) {}

    public record Usage(String month, double protectedTb, long apiCalls, long activeUsers) {}

    /** @param degradedReason set if this is the last-known-good snapshot rather than fresh data */
    public record EntitlementView(Map<String, Entitlement> entitlements, Instant asOf, String degradedReason) {}
}
