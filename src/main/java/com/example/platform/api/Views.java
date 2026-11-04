package com.example.platform.api;

import com.example.platform.security.Principal;
import com.example.platform.upstream.Models.Case;
import com.example.platform.upstream.Models.Comment;
import com.example.platform.upstream.Models.Entitlement;
import com.example.platform.upstream.UpstreamFailure;
import com.example.platform.web.ApiProblem;
import com.example.platform.web.ApiProblem.Code;
import com.example.platform.web.Hashes;
import com.example.platform.web.Times;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Internal models to API shapes. The field-level ARR rule lives here so that it applies however the
 * customer was loaded.
 */
final class Views {
    static final String FINANCIALS_SCOPE = "customers:financials:read";

    private Views() {}

    record CommentView(String id, String author, String agent, String body, String visibility, String createdAt) {}

    record CaseSummary(String id, String accountId, String customerName, String priority, String status,
                       String openedAt, String subject, int commentCount, CommentView latestComment,
                       String supportTier, String renewalDate, String etag) {}

    record CaseDetail(@JsonUnwrapped CaseSummary summary, List<CommentView> comments, boolean degraded,
                      List<DegradedSource> degradedSources) {}

    /** {@code arrUsd} is omitted rather than null, because `redactedFields` explains its absence. */
    record EntitlementBody(String name, String supportTier, String renewalDate, String asOf,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Long arrUsd) {}

    record DegradedSource(String source, String reason, String asOf) {}

    static DegradedSource degraded(String source, String reason, Instant asOf) {
        return new DegradedSource(source, reason, asOf == null ? null : Times.api(asOf));
    }

    static String etag(Case item) {
        return '"' + Hashes.sha256Hex(item.id() + ":" + item.version()).substring(0, 16) + '"';
    }

    static CommentView comment(Comment comment) {
        return new CommentView(comment.id(), comment.author(), comment.agent(), comment.body(), comment.visibility(),
                Times.api(comment.createdAt()));
    }

    static CaseSummary summary(Case item, Entitlement entitlement) {
        return new CaseSummary(
                item.id(),
                item.accountId(),
                entitlement == null ? null : entitlement.name(),
                item.priority(),
                item.status(),
                Times.api(item.openedAt()),
                item.subject(),
                item.comments().size(),
                item.comments().isEmpty() ? null : comment(item.comments().getLast()),
                entitlement == null ? null : entitlement.tier(),
                entitlement == null ? null : entitlement.renewal().toString(),
                etag(item));
    }

    static CaseDetail detail(Case item, Entitlement entitlement, List<DegradedSource> degraded) {
        return new CaseDetail(summary(item, entitlement), item.comments().stream().map(Views::comment).toList(),
                !degraded.isEmpty(), degraded);
    }

    record EntitlementResult(EntitlementBody body, List<String> redacted) {}

    static EntitlementResult entitlement(Entitlement entitlement, Instant asOf, Principal principal) {
        List<String> redacted = new ArrayList<>();
        Long arr = null;
        if (!principal.has(FINANCIALS_SCOPE)) {
            redacted.add("entitlement.arrUsd");
        } else {
            arr = entitlement.arrUsd();
        }
        return new EntitlementResult(new EntitlementBody(entitlement.name(), entitlement.tier(),
                entitlement.renewal().toString(), Times.api(asOf), arr), redacted);
    }

    static ApiProblem unavailable(UpstreamFailure failure, String detail) {
        long retryAfter = Math.max(1, Math.round(failure.retryAfter == null || failure.retryAfter == 0 ? 5 : failure.retryAfter));
        return ApiProblem.upstream(Code.UPSTREAM_UNAVAILABLE, failure.source, detail,
                Map.of("Retry-After", String.valueOf(retryAfter)));
    }

    static ApiProblem upstreamProblem(UpstreamFailure failure) {
        return switch (failure.reason) {
            case "timeout" -> ApiProblem.upstream(Code.UPSTREAM_TIMEOUT, failure.source, null, Map.of());
            case "circuit_open" -> unavailable(failure, "The case system is unavailable.");
            default -> ApiProblem.upstream(Code.UPSTREAM_ERROR, failure.source, null, Map.of());
        };
    }

    /** Explicit, so that an unusual Accept header can't turn a good response into a 406. */
    static ResponseEntity.BodyBuilder json(int status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
    }
}
