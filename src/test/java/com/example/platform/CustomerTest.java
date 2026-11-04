package com.example.platform;

import static com.example.testing.Contract.assertSchema;
import static com.example.testing.TestStack.auth;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.testing.Api;
import com.example.testing.LogCapture;
import com.example.testing.Reply;
import com.example.testing.TestStack;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class CustomerTest extends ApiTest {
    private Reply get(Api api, String persona, String account) {
        return api.http().get("/v1/customers/" + account, auth(persona));
    }

    private Reply get(String persona, String account) {
        return get(api(), persona, account);
    }

    private Reply get(String persona) {
        return get(persona, "ACC-007");
    }

    private static List<String> issueCodes(JsonNode body) {
        List<String> codes = new ArrayList<>();
        body.get("dataQuality").forEach(issue -> codes.add(issue.get("code").asString()));
        return codes;
    }

    @Test
    void financeSeesArrAndTheResponseMatchesTheContract() {
        Reply response = get("finance");
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertSchema("Customer", body);
        assertThat(body.get("entitlement").get("arrUsd").asInt()).isEqualTo(480000);
        assertThat(body.get("redactedFields")).isEmpty();
        assertThat(body.get("degraded").asBoolean()).isFalse();
    }

    @Test
    void supportDoesNotSeeArrAndIsToldItWasWithheld() {
        Reply response = get("support");
        JsonNode body = response.json();
        assertSchema("Customer", body);
        assertThat(body.get("entitlement").has("arrUsd")).isFalse();
        assertThat(body.get("redactedFields").get(0).asString()).isEqualTo("entitlement.arrUsd");
        assertThat(response.text()).doesNotContain("480000");
    }

    @Test
    void responsesCanNotBeSharedBetweenCallers() {
        Reply response = get("support");
        assertThat(response.header("Cache-Control")).isEqualTo("private");
        assertThat(response.header("Vary")).isEqualTo("Authorization");
    }

    @Test
    void csvIdIsJoinedThroughTheCrosswalk() {
        JsonNode body = get("finance").json();
        assertThat(body.get("entitlement").get("name").asString()).isEqualTo("Acme Corp");
        assertThat(body.get("usage").get("protectedTb").asDouble()).isEqualTo(412.5);
        assertThat(body.get("dataQuality")).isEmpty();
    }

    @Test
    void theSourceIdIsNotACustomer() {
        assertThat(get("support", "ACC-77").status()).isEqualTo(404);
    }

    @Test
    void conflictBetweenSourcesIsReported() {
        assertThat(issueCodes(get("support", "ACC-021").json())).containsExactly("source_conflict");
    }

    @Test
    void unmappedCsvAccountIsReported() {
        JsonNode body = get("support", "ACC-88").json();
        assertSchema("Customer", body);
        assertThat(issueCodes(body)).containsExactly("unmapped_source_id");
    }

    @Test
    void feedOnlyAccountHasNoNameNoArrAndNoUsage() {
        JsonNode body = get("finance", "ACC-050").json();
        assertSchema("Customer", body);
        assertThat(body.get("entitlement").get("name").isNull()).isTrue();
        assertThat(body.get("entitlement").has("arrUsd")).isFalse();
        assertThat(body.get("usage").isNull()).isTrue();
        assertThat(body.get("degraded").asBoolean()).isFalse(); // No usage data differs from being unable to read it.
    }

    @Test
    void unknownCustomerIsAProblem404() {
        Reply response = get("support", "ACC-999");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.contentType()).isEqualTo("application/problem+json");
        assertSchema("Problem", response.json());
    }

    @Test
    void restrictedCallerGets404ForAnAccountOutsideTheirBook() {
        assertThat(get("customer-success", "ACC-012").status()).isEqualTo(200);
        assertThat(get("customer-success", "ACC-007").status()).isEqualTo(404);
    }

    @Test
    void invalidIdIsAValidationProblem() {
        Reply response = get("support", "nope");
        assertThat(response.status()).isEqualTo(400);
        assertSchema("Problem", response.json());
        assertThat(response.json().get("errors").get(0).get("field").asString()).isEqualTo("path.customerId");
    }

    private void assertUsageDegraded(String reason) {
        Reply response = get("finance");
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertSchema("Customer", body);
        assertThat(body.get("entitlement").get("arrUsd").asInt()).isEqualTo(480000);
        assertThat(body.get("usage").isNull()).isTrue();
        assertThat(body.get("degraded").asBoolean()).isTrue();
        assertThat(body.get("degradedSources").get(0).get("source").asString()).isEqualTo("usage");
        assertThat(body.get("degradedSources").get(0).get("reason").asString()).isEqualTo(reason);
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void usageFailingStillReturnsEntitlementWithADegradedFlag() {
        chaosError("usage", 500);
        assertUsageDegraded("error");
    }

    @Test
    void usageTimingOutStillReturnsEntitlementWithADegradedFlag() {
        chaosLatency("usage", 1000);
        assertUsageDegraded("timeout");
    }

    @Test
    void slowUsageCanNotHoldTheResponsePastItsBudget() {
        get("support"); // Warm up the connection and key cache, so this measures only the fault.
        chaosLatency("usage", 5000);
        long started = System.nanoTime();
        assertThat(get("support").status()).isEqualTo(200);
        assertThat((System.nanoTime() - started) / 1e9).isLessThan(1.2); // The usage budget here is 0.7 seconds.
    }

    @Test
    void aSingleUsageFailureIsRetriedInvisibly() {
        TestStack.upstreams().inject("usage", 0, 503, 1, 0);
        assertThat(get("support").json().get("degraded").asBoolean()).isFalse();
    }

    @Test
    void entitlementsDownUsesTheSnapshotAndSaysHowOldItIs() {
        assertThat(get("finance").status()).isEqualTo(200);
        chaosError("entitlements", 500);
        Reply response = get("finance");
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertSchema("Customer", body);
        assertThat(body.get("entitlement").get("arrUsd").asInt()).isEqualTo(480000);
        assertThat(body.get("degraded").asBoolean()).isTrue();
        JsonNode source = body.get("degradedSources").get(0);
        assertThat(body.get("degradedSources").size()).isEqualTo(1);
        assertThat(source.get("source").asString()).isEqualTo("entitlements");
        assertThat(source.get("reason").asString()).isEqualTo("error");
        assertThat(source.get("asOf").asString()).isEqualTo("2026-11-02T12:00:00Z");
    }

    @Test
    void arrStaysHiddenWhenServedFromTheSnapshot() {
        get("finance");
        chaosError("entitlements", 500);
        assertThat(get("support").json().get("entitlement").has("arrUsd")).isFalse();
    }

    @Test
    void entitlementsDownWithNoSnapshotIsA503() {
        chaosError("entitlements", 500);
        Reply response = get("support");
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.header("Retry-After")).isNotBlank();
        assertSchema("Problem", response.json());
        assertThat(response.json().get("upstream").asString()).isEqualTo("entitlements");
    }

    @Test
    void everythingDownAfterASuccessStillServesTheSnapshot() {
        get("support");
        chaosError("entitlements", 500);
        chaosError("usage", 500);
        JsonNode body = get("support").json();
        Set<String> sources = new TreeSet<>();
        body.get("degradedSources").forEach(source -> sources.add(source.get("source").asString()));
        assertThat(sources).containsExactly("entitlements", "usage");
        assertThat(body.get("usage").isNull()).isTrue();
    }

    @Test
    void customerMissingFromAStaleSnapshotIsNotReportedAsNonexistent() {
        get("support");
        chaosError("entitlements", 500);
        assertThat(get("support", "ACC-999").status()).isEqualTo(503);
    }

    @Test
    void snapshotSurvivesARestart() {
        assertThat(get(make(), "support", "ACC-007").status()).isEqualTo(200);
        chaosError("entitlements", 500);
        assertThat(get(make(), "support", "ACC-007").json().get("degraded").asBoolean()).isTrue();
    }

    @Test
    void entitlementsAreServedFromCacheWithinTheTtl() {
        Api api = make("platform.entitlement-ttl=60s");
        get(api, "support", "ACC-007");
        chaosError("entitlements", 500);
        // The test clock is frozen, so the cache is still fresh and the fault is never reached.
        assertThat(get(api, "support", "ACC-007").json().get("degraded").asBoolean()).isFalse();
    }

    @Test
    void circuitOpensThenRecovers() throws Exception {
        Api api = make("platform.usage.max-attempts=1", "platform.usage.failure-threshold=2",
                "platform.usage.reset-after=200ms");
        List<String> reasons = new ArrayList<>();
        chaosError("usage", 500);
        for (int i = 0; i < 3; i++) {
            reasons.add(get(api, "support", "ACC-007").json().get("degradedSources").get(0).get("reason").asString());
        }
        assertThat(reasons).containsExactly("error", "error", "circuit_open");

        clearChaos();
        assertThat(get(api, "support", "ACC-007").json().get("degraded").asBoolean()).isTrue(); // Still cooling down.
        Thread.sleep(250);
        assertThat(get(api, "support", "ACC-007").json().get("degraded").asBoolean()).isFalse();
        assertThat(get(api, "support", "ACC-007").json().get("degraded").asBoolean()).isFalse();
    }

    @Test
    void correlationIdIsReturnedAndSentUpstream() throws Exception {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        // A recording proxy in front of the mock, so that this sees what actually crossed the wire.
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        proxy.createContext("/", exchange -> {
            seen.add(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            try {
                byte[] sent = exchange.getRequestBody().readAllBytes();
                var forwarded = client.send(java.net.http.HttpRequest
                                .newBuilder(java.net.URI.create(TestStack.UPSTREAM_HTTP.url() + exchange.getRequestURI()))
                                .method(exchange.getRequestMethod(), java.net.http.HttpRequest.BodyPublishers.ofByteArray(sent))
                                .header("Content-Type", "application/json").build(),
                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                exchange.sendResponseHeaders(forwarded.statusCode(), forwarded.body().length);
                exchange.getResponseBody().write(forwarded.body());
            } catch (InterruptedException interrupted) {
                exchange.sendResponseHeaders(500, -1);
            }
            exchange.close();
        });
        proxy.start();
        try {
            Api api = make("platform.upstream-url=http://127.0.0.1:" + proxy.getAddress().getPort());
            Reply response = api.http().get("/v1/customers/ACC-007", with(auth(), "X-Correlation-Id", "trace-abc-12345"));
            assertThat(response.header("X-Correlation-Id")).isEqualTo("trace-abc-12345");
            assertThat(seen).isNotEmpty();
            assertThat(new TreeSet<>(seen)).containsExactly("trace-abc-12345");
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void malformedCorrelationIdIsReplacedNotReflected() {
        String returned = api().http().get("/v1/customers/ACC-007", with(auth(), "X-Correlation-Id", "bad id with junk"))
                .header("X-Correlation-Id");
        assertThat(returned).doesNotContain("junk");
    }

    @Test
    void logsAreStructuredAndExcludeArrAndTokens() {
        Map<String, String> headers = auth("finance");
        api();
        try (LogCapture logs = new LogCapture()) {
            api().http().get("/v1/customers/ACC-007", headers);
            var record = logs.first("request_completed").orElseThrow();
            assertThat(LogCapture.field(record, "subject")).isEqualTo("fkhan");
            assertThat(LogCapture.field(record, "status")).isEqualTo(200);
            assertThat(record.getMDCPropertyMap()).containsKey("correlationId");
            assertThat(logs.text()).doesNotContain("480000");
            assertThat(logs.text()).doesNotContain(headers.get("Authorization").split(" ")[1]);
        }
    }

    /** Sabotages the real database, so that a real exception crosses the real handler. */
    @Test
    void anUnhandledErrorIsAProblemWithNoInternals() {
        api().db().sql("DROP TABLE entitlement_snapshot").update();
        Reply response = get("support");
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.json().get("code").asString()).isEqualTo("internal_error");
        assertThat(response.text()).doesNotContain("entitlement_snapshot").doesNotContain("SQL");
        assertThat(response.header("X-Correlation-Id")).isEqualTo(response.json().get("traceId").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/xml"})
    void anUnusualAcceptHeaderDoesNotChangeTheResponse(String accept) {
        assertThat(api().http().get("/v1/customers/ACC-007", with(auth(), "Accept", accept)).status()).isEqualTo(200);
    }
}
