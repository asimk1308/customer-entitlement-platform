package com.example.platform.api;

import com.example.platform.domain.EscalationRules;
import com.example.platform.idempotency.Idempotency;
import com.example.platform.idempotency.Idempotency.Claim;
import com.example.platform.idempotency.Idempotency.Event;
import com.example.platform.idempotency.Idempotency.Response;
import com.example.platform.idempotency.Idempotency.WriteResult;
import com.example.platform.security.Principal;
import com.example.platform.security.RequiresScope;
import com.example.platform.upstream.Models.Case;
import com.example.platform.upstream.Models.Comment;
import com.example.platform.upstream.Models.Entitlement;
import com.example.platform.upstream.Sources;
import com.example.platform.upstream.Sources.VersionConflict;
import com.example.platform.upstream.UpstreamFailure;
import com.example.platform.web.ApiProblem;
import com.example.platform.web.ApiProblem.Code;
import com.example.platform.web.Times;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The two case mutations. Both run through {@link Idempotency#run}, so retries behave the same way. */
@RestController
class CaseWriteController {
    private static final Logger LOG = LoggerFactory.getLogger(CaseWriteController.class);
    private static final String CASE_ID = "CS-[0-9]{1,10}";
    private static final String CASE_ID_MESSAGE = "Must look like CS-10231.";
    private static final String KEY = "[A-Za-z0-9_-]{16,128}";
    private static final String KEY_MESSAGE = "Must be 16 to 128 letters, digits, hyphens or underscores.";

    /**
     * A class rather than a record, because a record can't tell an absent field from an explicit null.
     * An absent {@code visibility} takes its default, but an explicit null breaks the contract.
     */
    static final class NewComment {
        @NotNull(message = "This field is required.")
        @Size(min = 1, max = 4000)
        private String body;

        @Pattern(regexp = "internal|public", message = "Not a known visibility.")
        private String visibility = "internal";

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }

        public String getVisibility() {
            return visibility;
        }

        @JsonSetter(nulls = Nulls.FAIL)
        public void setVisibility(String visibility) {
            this.visibility = visibility;
        }
    }

    record EscalationRequest(
            @NotNull(message = "This field is required.")
            @Pattern(regexp = "customer_impact|sla_breach|data_loss_risk|renewal_risk|security_incident",
                    message = "Not a known reason code.") String reasonCode,
            @NotNull(message = "This field is required.") @Size(min = 10, max = 1000) String justification) {}

    record Escalation(String reasonCode, String escalatedAt, String escalatedBy, String agent) {}

    record EscalationResult(@JsonProperty("case") Views.CaseDetail escalatedCase, Escalation escalation) {}

    record EventData(String caseId, String accountId, String priority, String reasonCode, String escalatedBy, String agent) {}

    record EscalatedEvent(String id, String type, String occurredAt, EventData data) {}

    private final Sources sources;
    private final Idempotency idempotency;
    private final Clock clock;

    CaseWriteController(Sources sources, Idempotency idempotency, Clock clock) {
        this.sources = sources;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Case visibleCase(Principal principal, String caseId) {
        Case item = sources.getCase(caseId);
        if (item == null || !principal.canSeeAccount(item.accountId())) {
            throw ApiProblem.of(Code.NOT_FOUND);
        }
        return item;
    }

    private static ResponseEntity<String> respond(Response response) {
        ResponseEntity.BodyBuilder builder = Views.json(response.status());
        response.headers().forEach(builder::header);
        if (response.replayed()) {
            builder.header("Idempotency-Replayed", "true");
        }
        return builder.body(response.body());
    }

    @RequiresScope("cases:comment")
    @PostMapping("/v1/cases/{caseId}/comments")
    ResponseEntity<String> addComment(
            Principal principal,
            @PathVariable @Pattern(regexp = CASE_ID, message = CASE_ID_MESSAGE) String caseId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY, message = KEY_MESSAGE) String idempotencyKey,
            @Valid @RequestBody NewComment comment) {
        Instant now = clock.instant();
        String requested = comment.getVisibility();
        // Agents only ever write internal notes, so an injected instruction can't reach a customer.
        String visibility = principal.agent() != null ? "internal" : requested;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", comment.getBody());
        payload.put("visibility", requested);
        try {
            return respond(idempotency.run(now, principal, "POST /v1/cases/" + caseId + "/comments", idempotencyKey,
                    payload, "case.comment", caseId, claimed -> {
                        Case item = visibleCase(principal, caseId);
                        idempotency.markAttempted(claimed, null);
                        Comment created = sources.addComment(caseId, claimed.externalId(), principal.subject(),
                                principal.agent(), comment.getBody(), visibility);
                        if (created == null) {
                            throw ApiProblem.of(Code.NOT_FOUND);
                        }
                        return new WriteResult(201, Views.comment(created), "case.comment", caseId, item.accountId(),
                                Map.of(), Map.of("visibility", visibility, "commentId", created.id()), List.of());
                    }));
        } catch (UpstreamFailure failure) {
            throw Views.upstreamProblem(failure);
        }
    }

    /**
     * Not run when resuming: the earlier attempt may have succeeded, and these checks would see its
     * effect and reject the retry of a write that actually worked.
     */
    private int preconditions(Principal principal, String caseId, String ifMatch, Claim claimed) {
        Case item = visibleCase(principal, caseId);
        if (!ifMatch.equals(Views.etag(item))) {
            throw ApiProblem.withHeaders(Code.STALE_VERSION, null, Map.of("ETag", Views.etag(item)));
        }
        EscalationRules.checkState(item.status());
        if (EscalationRules.needsTier(item.priority())) {
            CaseController.LoadedEntitlements loaded = CaseController.loadEntitlements(sources, true);
            Entitlement entitlement = loaded.of(item.accountId());
            if (entitlement == null) {
                throw Views.unavailable(new UpstreamFailure("entitlements", "error"),
                        "This customer's tier is unknown, so eligibility can't be checked.");
            }
            EscalationRules.checkEligibility(item.priority(), entitlement.tier());
        }
        idempotency.markAttempted(claimed, item.version());
        return item.version();
    }

    @RequiresScope("cases:escalate")
    @PostMapping("/v1/cases/{caseId}/escalate")
    ResponseEntity<String> escalateCase(
            Principal principal,
            @PathVariable @Pattern(regexp = CASE_ID, message = CASE_ID_MESSAGE) String caseId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY, message = KEY_MESSAGE) String idempotencyKey,
            @Valid @RequestBody EscalationRequest change,
            @RequestHeader(name = "If-Match", required = false) @Size(max = 128) String ifMatch) {
        if (ifMatch == null) {
            throw ApiProblem.of(Code.PRECONDITION_REQUIRED);
        }
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", change.reasonCode());
        payload.put("justification", change.justification());
        payload.put("ifMatch", ifMatch);
        try {
            return respond(idempotency.run(now, principal, "POST /v1/cases/" + caseId + "/escalate", idempotencyKey,
                    payload, "case.escalate", caseId, claimed -> {
                        int version = claimed.resuming() && claimed.expectedVersion() != null
                                ? claimed.expectedVersion()
                                : preconditions(principal, caseId, ifMatch, claimed);
                        Case item;
                        try {
                            item = sources.transition(caseId, claimed.externalId(), "escalated", version,
                                    change.reasonCode(), change.justification());
                        } catch (VersionConflict conflict) {
                            throw ApiProblem.of(Code.STALE_VERSION);
                        }
                        if (item == null) {
                            throw ApiProblem.of(Code.NOT_FOUND);
                        }
                        return escalated(item, change, principal, now, claimed.externalId());
                    }));
        } catch (UpstreamFailure failure) {
            throw Views.upstreamProblem(failure);
        }
    }

    private WriteResult escalated(Case item, EscalationRequest change, Principal principal, Instant now, String externalId) {
        CaseController.LoadedEntitlements loaded = CaseController.loadEntitlements(sources, false);
        String escalatedAt = Times.api(now);
        LOG.atInfo().addKeyValue("caseId", item.id()).addKeyValue("reasonCode", change.reasonCode()).log("case_escalated");
        // From the external ID, so that every retry of this write names the same event.
        String eventId = "evt_" + externalId;
        EscalatedEvent event = new EscalatedEvent(eventId, "case.escalated", escalatedAt, new EventData(
                item.id(), item.accountId(), item.priority(), change.reasonCode(), principal.subject(), principal.agent()));
        return new WriteResult(
                200,
                new EscalationResult(Views.detail(item, loaded.of(item.accountId()), loaded.sources()),
                        new Escalation(change.reasonCode(), escalatedAt, principal.subject(), principal.agent())),
                "case.escalate",
                item.id(),
                item.accountId(),
                Map.of("ETag", Views.etag(item)),
                Map.of("reasonCode", change.reasonCode()),
                List.of(new Event(eventId, event.type(), event)));
    }
}
