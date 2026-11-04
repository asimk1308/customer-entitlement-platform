package com.example.platform;

import static com.example.testing.Contract.assertSchema;
import static com.example.testing.TestStack.auth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mocks.idp.IdpController;
import com.example.mocks.upstreams.Seed;
import com.example.testing.Reply;
import com.example.testing.TestStack;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

class EscalationTest extends ApiTest {
    private static final String KEY = "esc-key-000000000001";
    private static final String JUSTIFICATION = "Open past the SLA for this tier.";
    private static final Map<String, Object> BODY = Map.of("reasonCode", "sla_breach", "justification", JUSTIFICATION);

    private String currentEtag(Map<String, String> headers, String caseId) {
        return api().http().get("/v1/cases/" + caseId, headers).header("ETag");
    }

    private Reply escalate(Map<String, String> headers, String caseId, String etag, String key, Object body) {
        String tag = etag != null ? etag : currentEtag(headers, caseId);
        return api().http().post("/v1/cases/" + caseId + "/escalate", body,
                with(headers, "Idempotency-Key", key, "If-Match", tag));
    }

    private Reply escalate(Map<String, String> headers, String caseId, String etag) {
        return escalate(headers, caseId, etag, KEY, BODY);
    }

    private Reply escalate(Map<String, String> headers, String caseId) {
        return escalate(headers, caseId, null);
    }

    private Reply escalate(Map<String, String> headers) {
        return escalate(headers, "CS-10231");
    }

    @Test
    void escalatesAndMatchesTheContract() {
        Reply response = escalate(auth());
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertSchema("EscalationResult", body);
        assertThat(body.get("case").get("status").asString()).isEqualTo("escalated");
        assertThat(body.get("escalation").get("escalatedBy").asString()).isEqualTo("jdoe");
        assertThat(response.header("ETag")).isEqualTo(body.get("case").get("etag").asString());
        assertThat(upstreamStatus("CS-10231")).isEqualTo("escalated");
    }

    @Test
    void returnedEtagIsTheCaseCurrentEtag() {
        assertThat(escalate(auth()).header("ETag")).isEqualTo(currentEtag(auth(), "CS-10231"));
    }

    @ParameterizedTest
    @CsvSource({
        "CS-10244, 409, case_already_escalated", // Already escalated.
        "CS-10241, 422, case_not_escalatable", // Closed.
        "CS-10252, 422, case_not_escalatable", // Resolved.
        "CS-10250, 422, escalation_not_eligible", // P3, Silver.
    })
    void businessRules(String caseId, int status, String code) {
        Object before = upstreamStatus(caseId);
        Reply response = escalate(auth(), caseId);
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.json().get("code").asString()).isEqualTo(code);
        assertSchema("Problem", response.json());
        assertThat(upstreamStatus(caseId)).isEqualTo(before);
    }

    @Test
    void lowPriorityCaseForAGoldCustomerCanEscalate() {
        assertThat(escalate(auth(), "CS-10251").status()).isEqualTo(200);
    }

    @Test
    void missingIfMatchIs428() {
        Reply response = api().http().post("/v1/cases/CS-10231/escalate", BODY, with(auth(), "Idempotency-Key", KEY));
        assertThat(response.status()).isEqualTo(428);
        assertThat(response.json().get("code").asString()).isEqualTo("precondition_required");
    }

    @Test
    void staleEtagIs412AndReturnsTheCurrentOne() {
        String etag = currentEtag(auth(), "CS-10231");
        bumpVersion("CS-10231");
        Reply response = escalate(auth(), "CS-10231", etag);
        assertThat(response.status()).isEqualTo(412);
        assertSchema("Problem", response.json());
        assertThat(response.header("ETag")).isEqualTo(currentEtag(auth(), "CS-10231"));
        assertThat(upstreamStatus("CS-10231")).isEqualTo("open");
    }

    /** Whoever is about to escalate should see what was just added before going ahead. */
    @Test
    void aNewCommentMakesAnEarlierDecisionStale() {
        String etag = currentEtag(auth(), "CS-10231");
        api().http().post("/v1/cases/CS-10231/comments", Map.of("body", "Now fixed"),
                with(auth(), "Idempotency-Key", "comment-key-00000001"));
        assertThat(escalate(auth(), "CS-10231", etag).status()).isEqualTo(412);
    }

    /** The upstream's own version check closes the gap left by our earlier read. */
    @Test
    void changeBetweenCheckAndWriteIs412NotASilentOverwrite() {
        TestStack.upstreams().beforeTransition = () -> bumpVersion("CS-10231");
        Reply response = escalate(auth());
        assertThat(response.status()).isEqualTo(412);
        assertThat(upstreamStatus("CS-10231")).isEqualTo("open");
    }

    @Test
    void replayReturnsTheOriginalResult() {
        String etag = currentEtag(auth(), "CS-10231");
        Reply first = escalate(auth(), "CS-10231", etag);
        Reply second = escalate(auth(), "CS-10231", etag);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json()).isEqualTo(first.json());
        assertThat(second.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(second.header("ETag")).isEqualTo(first.header("ETag"));
    }

    /**
     * The case this design exists for. The first attempt really did work, so the retry sees a changed
     * version and an escalated case. Pre-checks would wrongly reject it.
     */
    @Test
    void retryAfterALostResponseSucceedsRatherThanReportingStale() {
        String etag = currentEtag(auth(), "CS-10231");
        TestStack.upstreams().inject("cases", 0, null, null, 5);
        assertThat(escalate(auth(), "CS-10231", etag).status()).isEqualTo(502);
        assertThat(upstreamStatus("CS-10231")).isEqualTo("escalated");
        clearChaos();

        Reply retry = escalate(auth(), "CS-10231", etag);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.json().get("case").get("status").asString()).isEqualTo("escalated");
        assertThat(TestStack.upstreams().cases.get("CS-10231").get("version")).isEqualTo(2);
    }

    @Test
    void aDifferentKeyAfterALostResponseIsCorrectlyStale() {
        String etag = currentEtag(auth(), "CS-10231");
        TestStack.upstreams().inject("cases", 0, null, null, 5);
        escalate(auth(), "CS-10231", etag);
        clearChaos();
        assertThat(escalate(auth(), "CS-10231", etag, "esc-key-000000000002", BODY).status()).isEqualTo(412);
    }

    @Test
    void retryAfterFailureBeforeReachingUpstreamAppliesIt() {
        String etag = currentEtag(auth(), "CS-10231");
        chaosError("cases", 500);
        assertThat(escalate(auth(), "CS-10231", etag).status()).isEqualTo(502);
        assertThat(upstreamStatus("CS-10231")).isEqualTo("open");
        clearChaos();
        assertThat(escalate(auth(), "CS-10231", etag).status()).isEqualTo(200);
        assertThat(upstreamStatus("CS-10231")).isEqualTo("escalated");
    }

    @Test
    void urgentCaseEscalatesWhileEntitlementsAreDown() {
        chaosError("entitlements", 500);
        Reply response = escalate(auth());
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("case").get("supportTier").isNull()).isTrue();
    }

    @Test
    void lowPriorityCaseFailsClosedWhenTierIsUnknown() {
        String etag = currentEtag(auth(), "CS-10251");
        api().db().sql("DELETE FROM entitlement_snapshot").update(); // The read above created a snapshot.
        chaosError("entitlements", 500);
        assertThat(escalate(auth(), "CS-10251", etag).status()).isEqualTo(503);
        assertThat(upstreamStatus("CS-10251")).isEqualTo("open");
    }

    @Test
    void lowPriorityCaseForAnAccountWithNoEntitlementFailsClosed() {
        Map<String, Object> changed = new HashMap<>(TestStack.upstreams().cases.get("CS-10251"));
        changed.put("accountId", "ACC-404");
        TestStack.upstreams().cases.put("CS-10251", changed);
        Reply response = escalate(auth(), "CS-10251");
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.json().get("upstream").asString()).isEqualTo("entitlements");
        assertThat(upstreamStatus("CS-10251")).isEqualTo("open");
    }

    @Test
    void lowPriorityCaseCanUseSnapshotTier() {
        String etag = currentEtag(auth(), "CS-10251"); // This read also populates the snapshot.
        chaosError("entitlements", 500);
        assertThat(escalate(auth(), "CS-10251", etag).status()).isEqualTo(200);
    }

    @Test
    void scopeIsRequired() {
        assertThat(escalate(auth("customer-success"), "CS-10240").status()).isEqualTo(403);
    }

    @Test
    void restrictedCallerCannotEscalateOutsideTheirBook() {
        String etag = currentEtag(auth(), "CS-10231");
        // Needs the scope, so that it is the account rule being tested.
        IdpController.Persona support = TestStack.idp().personas.get("support");
        TestStack.idp().personas.put("restricted-escalator",
                new IdpController.Persona(support.subject(), support.scope(), List.of("ACC-012")));
        try {
            assertThat(escalate(auth("restricted-escalator"), "CS-10231", etag).status()).isEqualTo(404);
        } finally {
            TestStack.idp().personas.remove("restricted-escalator");
        }
        assertThat(upstreamStatus("CS-10231")).isEqualTo("open");
    }

    static List<Map<String, Object>> invalidBodies() {
        return List.of(
                Map.of(),
                Map.of("reasonCode", "because", "justification", "Long enough to pass."),
                Map.of("reasonCode", "sla_breach", "justification", "short"),
                Map.of("reasonCode", "sla_breach", "justification", "x".repeat(1001)),
                Map.of("reasonCode", "sla_breach", "justification", JUSTIFICATION, "status", "closed"));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void validationProblems(Map<String, Object> body) {
        assertThat(escalate(auth(), "CS-10231", null, KEY, body).status()).isEqualTo(400);
    }

    private List<Map<String, Object>> auditRows() {
        return api().db().sql("SELECT * FROM audit_log ORDER BY id").query().listOfRows();
    }

    @Test
    void successAndRejectionAreBothAudited() {
        escalate(with(auth(), "X-Correlation-Id", "trace-audit-0001"));
        escalate(auth(), "CS-10244", null, "esc-key-000000000003", BODY);
        List<Map<String, Object>> rows = auditRows();
        assertThat(rows.stream().map(row -> row.get("outcome")))
                .containsExactly("success", "rejected:case_already_escalated");
        assertThat(rows.getFirst().get("actor")).isEqualTo("jdoe");
        assertThat(rows.getFirst().get("case_id")).isEqualTo("CS-10231");
        assertThat(rows.getFirst().get("correlation_id")).isEqualTo("trace-audit-0001");
    }

    @Test
    void replayAddsNoAuditRow() {
        String etag = currentEtag(auth(), "CS-10231");
        escalate(auth(), "CS-10231", etag);
        escalate(auth(), "CS-10231", etag);
        assertThat(auditRows()).hasSize(1);
    }

    @Test
    void auditLogCannotBeChangedOrDeleted() {
        escalate(auth());
        for (String statement : List.of("UPDATE audit_log SET actor = 'nobody'", "DELETE FROM audit_log")) {
            assertThatThrownBy(() -> api().db().sql(statement).update()).hasMessageContaining("append-only");
        }
    }

    @Test
    void justificationStaysOutOfAudit() {
        escalate(auth());
        assertThat(auditRows().toString()).doesNotContain(JUSTIFICATION);
    }

    @Test
    void escalateAfterSeedKeepsOtherCasesUntouched() {
        escalate(auth());
        assertThat(upstreamStatus("CS-10232")).isEqualTo(Seed.cases(TestStack.CLOCK.instant()).get("CS-10232").get("Status"));
    }
}
