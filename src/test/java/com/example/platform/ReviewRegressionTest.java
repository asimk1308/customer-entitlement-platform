package com.example.platform;

import static com.example.testing.TestStack.auth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.platform.idempotency.Idempotency;
import com.example.platform.idempotency.Idempotency.Response;
import com.example.platform.idempotency.Idempotency.WriteResult;
import com.example.platform.security.Principal;
import com.example.platform.upstream.Adapters;
import com.example.platform.web.ApiProblem;
import com.example.platform.webhook.OutboxWorker;
import com.example.testing.Api;
import com.example.testing.Reply;
import com.example.testing.TestStack;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Failure paths that a code review reproduced after the rest of the suite had passed. */
class ReviewRegressionTest extends ApiTest {
    private static final String CSV_HEADER = "account_id,name,support_tier,contract_end,arr_usd\n";
    private static final Map<String, Object> ESCALATION =
            Map.of("reasonCode", "sla_breach", "justification", "Open past the SLA for this tier.");

    private static HttpServer jwks(RSAKey key, AtomicInteger fetches) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/jwks.json", exchange -> {
            fetches.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static Map<String, String> bearer(RSAKey key, String keyId, JWTClaimsSet claims) throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
        token.sign(new RSASSASigner(key));
        return Map.of("Authorization", "Bearer " + token.serialize());
    }

    private static JWTClaimsSet.Builder claims(String scope) {
        return new JWTClaimsSet.Builder().subject("jdoe").audience("platform-api").issuer("http://idp.test")
                .claim("scope", scope).expirationTime(new Date(System.currentTimeMillis() + 60_000));
    }

    /** Forwards to the mock upstreams, except for the paths given here. */
    private static HttpServer proxy(Map<String, Supplier<byte[]>> overrides) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        HttpClient client = HttpClient.newHttpClient();
        server.createContext("/", exchange -> {
            try (exchange) {
                Supplier<byte[]> override = overrides.get(exchange.getRequestURI().getPath());
                if (override != null) {
                    byte[] body = override.get();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    return;
                }
                byte[] sent = exchange.getRequestBody().readAllBytes();
                HttpResponse<byte[]> reply = client.send(
                        HttpRequest.newBuilder(URI.create(TestStack.UPSTREAM_HTTP.url() + exchange.getRequestURI()))
                                .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(sent))
                                .header("Content-Type", "application/json").build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                exchange.sendResponseHeaders(reply.statusCode(), reply.body().length == 0 ? -1 : reply.body().length);
                if (reply.body().length > 0) {
                    exchange.getResponseBody().write(reply.body());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        return server;
    }

    private static int count(Api api, String sql) {
        return api.db().sql(sql).query(Integer.class).single();
    }

    private static void subscribeReceiver(Api api) {
        Reply created = api.http().post("/v1/webhook-subscriptions",
                Map.of("url", TestStack.receiverHook(), "events", List.of("case.escalated")), auth("platform-admin"));
        TestStack.receiver().secrets.add(created.json().get("secret").asString());
    }

    private static void escalate(Api api, String caseId, String key) {
        Map<String, String> headers = auth();
        String etag = api.http().get("/v1/cases/" + caseId, headers).header("ETag");
        Reply reply = api.http().post("/v1/cases/" + caseId + "/escalate", ESCALATION,
                with(headers, "Idempotency-Key", key, "If-Match", etag));
        assertThat(reply.status()).isEqualTo(200);
    }

    /** Before the fix, requests queued for 17 s, and the healthy CSV kept the shared breaker closed. */
    @Test
    void oneHungFeedDoesNotQueueRequestsBehindEachOther() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger feedCalls = new AtomicInteger();
        boolean[] hang = {false};
        byte[] goodFeed = TestStack.UPSTREAM_HTTP.get("/entitlements/feed.xml").text().getBytes(StandardCharsets.UTF_8);
        HttpServer upstream = proxy(Map.of("/entitlements/feed.xml", () -> {
            feedCalls.incrementAndGet();
            if (hang[0]) {
                try {
                    never.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return goodFeed;
        }));
        try {
            Api api = make("platform.upstream-url=http://127.0.0.1:" + upstream.getAddress().getPort(),
                    "platform.entitlement-ttl=60s", "platform.entitlements.attempt-timeout=500ms",
                    "platform.entitlements.max-attempts=3", "platform.entitlements.deadline=1200ms",
                    "platform.entitlements.failure-threshold=5", "platform.usage.attempt-timeout=500ms");
            Map<String, String> headers = auth();
            assertThat(api.http().get("/v1/customers/ACC-007", headers).status()).isEqualTo(200);
            hang[0] = true;
            feedCalls.set(0);
            TestStack.CLOCK.advance(Duration.ofSeconds(61));

            List<Long> millis = new CopyOnWriteArrayList<>();
            List<Integer> statuses = new CopyOnWriteArrayList<>();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Callable<Void>> calls = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    calls.add(() -> {
                        long started = System.nanoTime();
                        statuses.add(api.http().get("/v1/customers/ACC-007", headers).status());
                        millis.add((System.nanoTime() - started) / 1_000_000);
                        return null;
                    });
                }
                pool.invokeAll(calls);
            }
            assertThat(statuses).containsOnly(200);
            assertThat(millis.stream().max(Long::compare).orElseThrow()).isLessThan(5_000L);
            assertThat(feedCalls.get()).isLessThanOrEqualTo(6);
        } finally {
            never.countDown();
            upstream.stop(0);
        }
    }

    @Test
    void aLeaseTakeoverCommitsOnceWithOneEventId() throws Exception {
        Api api = make("platform.cases.attempt-timeout=5s", "platform.cases.deadline=10s");
        subscribeReceiver(api);
        Map<String, String> headers = auth();
        String etag = api.http().get("/v1/cases/CS-10231", headers).header("ETag");
        Map<String, String> request = with(headers, "Idempotency-Key", "lease-key-0000000001", "If-Match", etag);
        chaosLatency("cases", 700);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Reply> first = pool.submit(() -> api.http().post("/v1/cases/CS-10231/escalate", ESCALATION, request));
            while (count(api, "SELECT COUNT(*) FROM idempotency_keys WHERE upstream_attempted = 1") == 0) {
                Thread.sleep(10);
            }
            TestStack.CLOCK.advance(Duration.ofSeconds(31));
            Future<Reply> second = pool.submit(() -> api.http().post("/v1/cases/CS-10231/escalate", ESCALATION, request));
            assertThat(first.get().status()).isEqualTo(200);
            assertThat(second.get().status()).isEqualTo(200);
        }
        assertThat(count(api, "SELECT COUNT(*) FROM audit_log WHERE action = 'case.escalate' AND outcome = 'success'"))
                .isEqualTo(1);
        assertThat(count(api, "SELECT COUNT(DISTINCT event_id) FROM outbox_events")).isEqualTo(1);
    }

    @Test
    void aRequestThatLostItsLeaseCannotForgetTheKey() {
        Api api = api();
        Idempotency idempotency = api.bean(Idempotency.class);
        Principal principal = new Principal("jdoe", Set.of(), null, null);
        Map<String, Object> payload = Map.of("body", "x");
        String route = "POST /v1/cases/CS-1/comments";
        String key = "stale-holder-key-0001";

        assertThatThrownBy(() -> idempotency.run(TestStack.CLOCK.instant(), principal, route, key, payload,
                "case.comment", "CS-1", claimed -> {
                    idempotency.markAttempted(claimed, null);
                    TestStack.CLOCK.advance(Duration.ofSeconds(31));
                    idempotency.claim(TestStack.CLOCK.instant(), claimed.principal(), route, key,
                            idempotency.requestHash(payload));
                    throw ApiProblem.of(ApiProblem.Code.NOT_FOUND);
                })).isInstanceOf(ApiProblem.class);

        assertThat(count(api, "SELECT COUNT(*) FROM idempotency_keys WHERE upstream_attempted = 1")).isEqualTo(1);
    }

    @Test
    void theLoserOfARaceReturnsTheWinnersResponse() {
        Api api = api();
        Idempotency idempotency = api.bean(Idempotency.class);
        Principal principal = new Principal("jdoe", Set.of(), null, null);
        Map<String, Object> payload = Map.of("body", "x");
        String route = "POST /v1/cases/CS-1/comments";
        String key = "race-loser-key-000001";
        WriteResult result = new WriteResult(201, Map.of("from", "loser"), "case.comment", "CS-1", "ACC-1",
                Map.of(), Map.of(), List.of());
        Instant now = TestStack.CLOCK.instant();

        Response response = idempotency.run(now, principal, route, key, payload, "case.comment", "CS-1", claimed -> {
            TestStack.CLOCK.advance(Duration.ofSeconds(31));
            Instant later = TestStack.CLOCK.instant();
            idempotency.run(later, principal, route, key, payload, "case.comment", "CS-1",
                    resumed -> new WriteResult(201, Map.of("from", "winner"), "case.comment", "CS-1", "ACC-1",
                            Map.of(), Map.of(), List.of()));
            return result;
        });

        assertThat(response.body()).contains("winner");
        assertThat(response.replayed()).isTrue();
        assertThat(count(api, "SELECT COUNT(*) FROM audit_log WHERE outcome = 'success'")).isEqualTo(1);
    }

    @Test
    void anUnparseableCsvFallsBackToTheSnapshot() throws Exception {
        byte[] csv = (CSV_HEADER + "ACC-1,\"Acme \"Corp\",Gold,2027-01-01,5\n").getBytes(StandardCharsets.UTF_8);
        assertThat(make().http().get("/v1/customers/ACC-007", auth()).status()).isEqualTo(200);
        HttpServer upstream = proxy(Map.of("/entitlements/export.csv", () -> csv));
        try {
            Api api = make("platform.upstream-url=http://127.0.0.1:" + upstream.getAddress().getPort());
            Reply customer = api.http().get("/v1/customers/ACC-007", auth());
            assertThat(customer.status()).isEqualTo(200);
            assertThat(customer.json().get("degraded").asBoolean()).isTrue();
            assertThat(api.http().get("/v1/cases", auth()).status()).isEqualTo(200);
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void parseErrorsDoNotCarryFieldValues() {
        assertThatThrownBy(() -> Adapters.parseEntitlementCsv(CSV_HEADER + "ACC-1,Acme,Gold,2027-01-01,480000.50\n"))
                .isInstanceOf(Adapters.UpstreamDataError.class)
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("480000"));
    }

    @Test
    void anEmptyExportIsAFailureAndKeepsTheSnapshot() throws Exception {
        assertThat(make().http().get("/v1/customers/ACC-007", auth()).status()).isEqualTo(200);
        int rows = count(api(), "SELECT COUNT(*) FROM entitlement_snapshot");
        HttpServer upstream = proxy(Map.of(
                "/entitlements/export.csv", () -> CSV_HEADER.getBytes(StandardCharsets.UTF_8),
                "/entitlements/feed.xml", () -> "<accounts/>".getBytes(StandardCharsets.UTF_8)));
        try {
            Api api = make("platform.upstream-url=http://127.0.0.1:" + upstream.getAddress().getPort());
            Reply customer = api.http().get("/v1/customers/ACC-007", auth());
            assertThat(customer.status()).isEqualTo(200);
            assertThat(customer.json().get("degraded").asBoolean()).isTrue();
            Reply list = api.http().get("/v1/cases", Map.of("supportTier", "Platinum"), auth());
            assertThat(list.json().get("degraded").asBoolean()).isTrue();
            assertThat(list.json().get("data").size()).isPositive();
            assertThat(count(api, "SELECT COUNT(*) FROM entitlement_snapshot")).isEqualTo(rows);
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void forgedKeyIdsCannotForceAFetchPerRequest() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("trusted").generate();
        AtomicInteger fetches = new AtomicInteger();
        HttpServer provider = jwks(key, fetches);
        try {
            Api api = make("platform.idp-url=http://127.0.0.1:" + provider.getAddress().getPort());
            assertThat(api.http().get("/v1/cases", bearer(key, "trusted", claims("cases:read").build())).status())
                    .isEqualTo(200);
            int before = fetches.get();
            RSAKey forger = new RSAKeyGenerator(2048).keyID("x").generate();
            for (int i = 0; i < 20; i++) {
                Reply reply = api.http().get("/v1/cases", bearer(forger, "made-up-" + i, claims("cases:read").build()));
                assertThat(reply.status()).isEqualTo(401);
            }
            assertThat(fetches.get() - before).isLessThanOrEqualTo(2);
            assertThat(api.http().get("/v1/cases", bearer(key, "trusted", claims("cases:read").build())).status())
                    .isEqualTo(200);
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void unusualTokenClaimsAreHandled() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("trusted").generate();
        HttpServer provider = jwks(key, new AtomicInteger());
        try {
            Api api = make("platform.idp-url=http://127.0.0.1:" + provider.getAddress().getPort());
            assertThat(api.http().get("/v1/cases",
                    bearer(key, "trusted", claims("cases:read cases:read").build())).status()).isEqualTo(200);
            assertThat(api.http().get("/v1/cases",
                    bearer(key, "trusted", claims("cases:read").claim("act", Map.of("sub", 123)).build())).status())
                    .isEqualTo(401);
            // Treated as a person, this token could post a public comment with no agent recorded.
            Reply noSubject = api.http().post("/v1/cases/CS-10232/comments", Map.of("body", "x", "visibility", "public"),
                    with(bearer(key, "trusted", claims("cases:comment").claim("act", Map.of("client_id", "agent")).build()),
                            "Idempotency-Key", "aaaaaaaaaaaaaaaaaaaaaa"));
            assertThat(noSubject.status()).isEqualTo(401);
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void degradedCaseDetailIsFlaggedAndNotCacheable() {
        Api api = api();
        Reply healthy = api.http().get("/v1/cases/CS-10231", auth());
        assertThat(healthy.header("Cache-Control")).isEqualTo("private");
        assertThat(healthy.json().get("degraded").asBoolean()).isFalse();

        chaosError("entitlements", 500);
        TestStack.CLOCK.advance(Duration.ofHours(1));
        Reply degraded = api.http().get("/v1/cases/CS-10231", auth());
        assertThat(degraded.status()).isEqualTo(200);
        assertThat(degraded.header("Cache-Control")).isEqualTo("no-store");
        assertThat(degraded.json().get("degraded").asBoolean()).isTrue();
        assertThat(degraded.json().get("degradedSources").get(0).get("source").asString()).isEqualTo("entitlements");
    }

    @Test
    void firewallRejectionsCarryTheTraceId() {
        Reply reply = api().http().get("/v1/cases;x=1", auth());
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.json().get("traceId").asString()).isEqualTo(reply.header("X-Correlation-Id"));
    }

    @Test
    void aGoneReceiverGetsNothingMoreAndItsPendingEventsDie() {
        Api api = api();
        subscribeReceiver(api);
        escalate(api, "CS-10231", "gone-key-00000000001");
        escalate(api, "CS-10240", "gone-key-00000000002");
        // One event is held back, then the receiver answers 410 to the other.
        api.db().sql("UPDATE outbox_events SET next_attempt_at = '2030-01-01T00:00:00.000Z' "
                + "WHERE id = (SELECT MAX(id) FROM outbox_events)").update();
        TestStack.receiver().behaviour.failNext = 1;
        TestStack.receiver().behaviour.status = 410;
        api.bean(OutboxWorker.class).runOnce();
        int before = TestStack.receiver().deliveries.get();

        TestStack.CLOCK.set(Instant.parse("2030-01-02T00:00:00Z"));
        api.bean(OutboxWorker.class).runOnce();

        assertThat(TestStack.receiver().deliveries.get()).isEqualTo(before);
        assertThat(count(api, "SELECT COUNT(*) FROM outbox_events WHERE status = 'pending'")).isZero();
    }

    @Test
    void creatingASubscriptionIsAudited() {
        Api api = api();
        subscribeReceiver(api);
        assertThat(count(api, "SELECT COUNT(*) FROM audit_log WHERE action = 'webhook_subscription.create' "
                + "AND outcome = 'success' AND actor IS NOT NULL")).isEqualTo(1);
    }
}
