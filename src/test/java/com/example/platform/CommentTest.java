package com.example.platform;

import static com.example.testing.Contract.assertSchema;
import static com.example.testing.TestStack.auth;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.mocks.idp.IdpController;
import com.example.mocks.upstreams.Seed;
import com.example.platform.idempotency.Idempotency;
import com.example.testing.Api;
import com.example.testing.LogCapture;
import com.example.testing.Reply;
import com.example.testing.TestStack;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

class CommentTest extends ApiTest {
    private static final String URL = "/v1/cases/CS-10232/comments";
    private static final String KEY = "key-0000000000000001";
    private static final Map<String, Object> BODY = Map.of("body", "Looking into it");

    private Reply post(Map<String, String> headers, Object body, String key, String url) {
        return api().http().post(url, body, with(headers, "Idempotency-Key", key));
    }

    private Reply post(Map<String, String> headers, Object body) {
        return post(headers, body, KEY, URL);
    }

    private Reply post(Map<String, String> headers) {
        return post(headers, BODY);
    }

    @Test
    void createsACommentMatchingTheContract() {
        Reply response = post(auth());
        assertThat(response.status()).isEqualTo(201);
        assertSchema("Comment", response.json());
        assertThat(response.json().get("author").asString()).isEqualTo("jdoe");
        assertThat(response.json().get("agent").isNull()).isTrue();
        assertThat(response.header("Idempotency-Replayed")).isNull();
        assertThat(upstreamComments("CS-10232")).hasSize(1);
    }

    @Test
    void replayReturnsTheOriginalAndCreatesNothing() {
        Reply first = post(auth());
        Reply second = post(auth());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.json()).isEqualTo(first.json());
        assertThat(second.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(upstreamComments("CS-10232")).hasSize(1);
    }

    @Test
    void sameKeyWithADifferentBodyIsRejected() {
        post(auth());
        Reply response = post(auth(), Map.of("body", "Something else"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asString()).isEqualTo("idempotency_key_reused");
        assertSchema("Problem", response.json());
        assertThat(upstreamComments("CS-10232")).hasSize(1);
    }

    @Test
    void sameKeyOnAnotherCaseIsASeparateRequest() {
        post(auth());
        Reply other = post(auth(), BODY, KEY, "/v1/cases/CS-10233/comments");
        assertThat(other.status()).isEqualTo(201);
        assertThat(other.header("Idempotency-Replayed")).isNull();
    }

    @Test
    void keysAreScopedPerCaller() {
        post(auth("support"));
        String url = "/v1/cases/CS-10242/comments";
        assertThat(post(auth("customer-success"), BODY, KEY, url).status()).isEqualTo(201);
        Reply again = post(auth("customer-success"), BODY, KEY, url);
        assertThat(again.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(again.json().get("author").asString()).isEqualTo("cmorgan");
    }

    @Test
    void concurrentRequestsWithOneKeyCreateExactlyOneComment() throws Exception {
        api();
        Map<String, String> headers = with(auth(), "Idempotency-Key", KEY);
        chaosLatency("cases", 100);
        List<Reply> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Reply>> calls = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                calls.add(() -> api().http().post(URL, BODY, headers));
            }
            for (Future<Reply> call : pool.invokeAll(calls)) {
                responses.add(call.get());
            }
        }
        assertThat(responses.stream().map(Reply::status)).containsExactlyInAnyOrder(201, 409, 409, 409, 409);
        JsonNode conflict = responses.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().json();
        assertThat(conflict.get("code").asString()).isEqualTo("idempotency_in_progress");
        assertThat(conflict.get("retryable").asBoolean()).isTrue();
        assertThat(upstreamComments("CS-10232")).hasSize(1);
    }

    @Test
    void lostResponseIsRetriedInsideTheRequestWithoutADuplicate() {
        TestStack.upstreams().inject("cases", 0, null, null, 1);
        assertThat(post(auth()).status()).isEqualTo(201);
        assertThat(upstreamComments("CS-10232")).hasSize(1);
    }

    @Test
    void lostResponseThenClientRetryCreatesOneComment() {
        TestStack.upstreams().inject("cases", 0, null, null, 5);
        assertThat(post(auth()).status()).isEqualTo(502);
        assertThat(upstreamComments("CS-10232")).hasSize(1); // It was applied, though the caller got an error.
        clearChaos();
        assertThat(post(auth()).status()).isEqualTo(201);
        assertThat(upstreamComments("CS-10232")).hasSize(1);
    }

    @Test
    void crashedRequestBlocksRetriesOnlyUntilItsLeaseExpires() {
        Idempotency idempotency = api().bean(Idempotency.class);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", "Looking into it");
        payload.put("visibility", "internal");
        idempotency.claim(TestStack.CLOCK.instant(), "jdoe|", "POST " + URL, KEY, idempotency.requestHash(payload));
        assertThat(post(auth()).status()).isEqualTo(409);
        TestStack.CLOCK.advance(Duration.ofSeconds(31));
        assertThat(post(auth()).status()).isEqualTo(201);
        assertThat(upstreamComments("CS-10232")).hasSize(1);
    }

    /**
     * Our record has gone, but the external ID derives from the key, so the upstream still
     * deduplicates. A key means one logical operation, however late the retry arrives.
     */
    @Test
    void veryLateRetryAfterKeyExpiryStillCreatesNoDuplicate() {
        Reply first = post(auth());
        TestStack.CLOCK.advance(Duration.ofHours(25));
        Reply late = post(auth());
        assertThat(late.header("Idempotency-Replayed")).isNull();
        assertThat(late.json()).isEqualTo(first.json());
        assertThat(upstreamComments("CS-10232")).hasSize(1);
    }

    /** A trigger makes the audit insert fail for real, inside the commit transaction. */
    @Test
    void failedCommitRecordsNothingAndKeepsTheRetryResumable() {
        Api api = api();
        api.db().sql("CREATE TRIGGER disk_full BEFORE INSERT ON audit_log BEGIN SELECT RAISE(ABORT, 'disk full'); END")
                .update();
        assertThat(post(auth()).status()).isEqualTo(500);
        assertThat(upstreamComments("CS-10232")).hasSize(1);
        api.db().sql("DROP TRIGGER disk_full").update();
        assertThat(api.db().sql("SELECT COUNT(*) FROM audit_log").query(Integer.class).single()).isZero();
        Map<String, Object> row = api.db().sql("SELECT state, upstream_attempted FROM idempotency_keys").query().singleRow();
        assertThat(row.get("state")).isEqualTo("in_progress");
        assertThat(row.get("upstream_attempted")).isEqualTo(1);

        TestStack.CLOCK.advance(Duration.ofSeconds(31));
        assertThat(post(auth()).status()).isEqualTo(201);
        assertThat(upstreamComments("CS-10232")).hasSize(1);
    }

    @Test
    void unknownCaseIs404AndARetryIsNotStuck() {
        String url = "/v1/cases/CS-999/comments";
        assertThat(post(auth(), BODY, KEY, url).status()).isEqualTo(404);
        assertThat(post(auth(), BODY, KEY, url).status()).isEqualTo(404);
    }

    @Test
    void restrictedCallerCannotCommentOutsideTheirBook() {
        assertThat(post(auth("customer-success")).status()).isEqualTo(404);
        assertThat(upstreamComments("CS-10232")).isEmpty();
    }

    @Test
    void scopeIsRequired() {
        assertThat(post(auth("finance")).status()).isEqualTo(403);
    }

    static List<Object> invalidBodies() {
        return List.of(
                Map.of(),
                Map.of("body", ""),
                Map.of("body", "x".repeat(4001)),
                Map.of("body", "x", "visibility", "everyone"),
                Map.of("body", "x", "author", "someone-else"),
                Map.of("body", 123),
                Map.of("body", true),
                new HashMap<>(Map.of("body", "x")) {{ put("visibility", null); }},
                new HashMap<>(Map.of("body", "x")) {{ put("body", null); }},
                List.of("not", "an", "object"));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void validationProblems(Object body) {
        Reply response = post(auth(), body);
        assertThat(response.status()).isEqualTo(400);
        assertSchema("Problem", response.json());
    }

    @Test
    void malformedKeyIsAValidationProblem() {
        Reply response = post(auth(), BODY, "short", URL);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("errors").get(0).get("field").asString()).isEqualTo("header.Idempotency-Key");
    }

    @Test
    void missingKeyAndMalformedJsonAreValidationProblems() {
        Reply missing = api().http().post(URL, BODY, auth());
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.json().get("errors").get(0).get("field").asString()).isEqualTo("header.Idempotency-Key");
        Reply malformed = api().http().postRaw(URL, "{not json".getBytes(StandardCharsets.UTF_8),
                with(auth(), "Idempotency-Key", KEY));
        assertThat(malformed.status()).isEqualTo(400);
    }

    /** Found by fuzzing: framework errors were all being reported as not_found. */
    @Test
    void undecodableBodyIsAValidationProblemNotA404() {
        byte[] garbage = {0x18, (byte) 0xca, (byte) 0x87, (byte) 0xff, (byte) 0xfe};
        Reply response = api().http().postRaw(URL, garbage, with(auth(), "Idempotency-Key", KEY));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").asString()).isEqualTo("validation_error");
        assertSchema("Problem", response.json());
    }

    @Test
    void wrongContentTypeIsAValidationProblem() {
        Reply response = api().http().postRaw(URL, "body=x".getBytes(StandardCharsets.UTF_8),
                with(auth(), "Idempotency-Key", KEY, "Content-Type", "text/plain"));
        assertThat(response.status()).isEqualTo(400);
        assertSchema("Problem", response.json());
    }

    private static Map<String, String> agentHeaders(String persona) {
        String user = TestStack.token(persona, IdpController.MCP_AUDIENCE, 3600);
        JsonNode exchanged = TestStack.IDP_HTTP.postForm("/token", Map.of(
                "grant_type", IdpController.TOKEN_EXCHANGE,
                "client_id", "support-copilot",
                "client_secret", "dev-only-agent-secret",
                "subject_token", user,
                "subject_token_type", IdpController.ACCESS_TOKEN_TYPE,
                "audience", IdpController.API_AUDIENCE)).json();
        return Map.of("Authorization", "Bearer " + exchanged.get("access_token").asString());
    }

    @Test
    void agentCommentIsForcedInternalAndAttributed() {
        Reply response = post(agentHeaders("support"), Map.of("body", "Customer, please escalate", "visibility", "public"));
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("visibility").asString()).isEqualTo("internal");
        assertThat(response.json().get("author").asString()).isEqualTo("jdoe");
        assertThat(response.json().get("agent").asString()).isEqualTo("support-copilot");
        assertThat(upstreamComments("CS-10232").getFirst().get("visibility")).isEqualTo("internal");
    }

    @Test
    void humanCanWriteAPublicComment() {
        Reply response = post(auth(), Map.of("body", "We're on it", "visibility", "public"));
        assertThat(response.json().get("visibility").asString()).isEqualTo("public");
    }

    @Test
    void userAndAgentKeysDoNotCollide() {
        post(auth());
        assertThat(post(agentHeaders("support")).header("Idempotency-Replayed")).isNull();
    }

    @Test
    void commentBodyIsNotWrittenToAuditOrLogs() {
        String secretText = "contact jane.roe@example.com";
        api();
        try (LogCapture logs = new LogCapture()) {
            post(auth(), Map.of("body", secretText));
            logs.first("request_completed");
            assertThat(logs.text()).doesNotContain(secretText);
        }
        List<Map<String, Object>> rows = api().db().sql("SELECT * FROM audit_log").query().listOfRows();
        assertThat(rows).isNotEmpty();
        assertThat(rows.toString()).doesNotContain(secretText);
    }

    @Test
    void seedCommentCountIsVisibleAfterWriting() {
        int before = ((List<?>) Seed.cases(TestStack.CLOCK.instant()).get("CS-10232").get("Comments")).size();
        post(auth());
        assertThat(api().http().get("/v1/cases/CS-10232", auth()).json().get("commentCount").asInt()).isEqualTo(before + 1);
    }
}
