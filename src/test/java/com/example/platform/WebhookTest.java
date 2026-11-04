package com.example.platform;

import static com.example.testing.Contract.assertSchema;
import static com.example.testing.TestStack.auth;
import static com.example.testing.TestStack.receiver;
import static com.example.testing.TestStack.receiverHook;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.platform.webhook.OutboxWorker;
import com.example.platform.webhook.Webhooks;
import com.example.testing.LogCapture;
import com.example.testing.Reply;
import com.example.testing.TestStack;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WebhookTest extends ApiTest {
    private static final int MAX_ATTEMPTS = 8;
    private static final String SUBSCRIPTIONS = "/v1/webhook-subscriptions";
    private static final String JUSTIFICATION = "Open past the SLA for this tier.";
    private static final Map<String, Object> BODY = Map.of("reasonCode", "sla_breach", "justification", JUSTIFICATION);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JsonNode subscribe() {
        Reply response = api().http().post(SUBSCRIPTIONS, Map.of("url", receiverHook(), "events", List.of("case.escalated")),
                auth("platform-admin"));
        assertThat(response.status()).isEqualTo(201);
        receiver().secrets.add(response.json().get("secret").asString());
        return response.json();
    }

    private Reply escalate(String caseId, String key) {
        Map<String, String> headers = auth();
        String etag = api().http().get("/v1/cases/" + caseId, headers).header("ETag");
        return api().http().post("/v1/cases/" + caseId + "/escalate", BODY,
                with(headers, "Idempotency-Key", key, "If-Match", etag, "X-Correlation-Id", "trace-webhook-01"));
    }

    private Reply escalate() {
        return escalate("CS-10231", "esc-key-000000000001");
    }

    private int deliver() {
        return api().bean(OutboxWorker.class).runOnce();
    }

    private List<Map<String, Object>> outbox() {
        return api().db().sql("SELECT * FROM outbox_events ORDER BY id").query().listOfRows();
    }

    private static Map<String, String> onlyEvent() {
        return receiver().events.values().iterator().next();
    }

    @Test
    void createMatchesTheContractAndReturnsTheSecret() {
        Reply response = api().http().post(SUBSCRIPTIONS, Map.of("url", receiverHook(), "events", List.of("case.escalated")),
                auth("platform-admin"));
        assertThat(response.status()).isEqualTo(201);
        assertSchema("CreatedWebhookSubscription", response.json());
    }

    @Test
    void createNeedsTheScope() {
        Map<String, Object> body = Map.of("url", receiverHook(), "events", List.of("case.escalated"));
        assertThat(api().http().post(SUBSCRIPTIONS, body, auth()).status()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254/latest/meta-data/", "http://localhost:9002@evil.example/"})
    void internalOrDisguisedUrlsAreRefused(String url) {
        Reply response = api().http().post(SUBSCRIPTIONS, Map.of("url", url, "events", List.of("case.escalated")),
                auth("platform-admin"));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").asString()).isEqualTo("webhook_url_not_allowed");
    }

    static List<Map<String, Object>> invalidSubscriptions() {
        String hook = "http://localhost:9002/hook";
        return List.of(
                Map.of("url", hook, "events", List.of()),
                Map.of("url", hook, "events", List.of("case.deleted")),
                Map.of("url", hook, "events", List.of("case.escalated", "case.escalated")),
                Map.of("events", List.of("case.escalated")));
    }

    @ParameterizedTest
    @MethodSource("invalidSubscriptions")
    void invalidSubscriptionsAreValidationProblems(Map<String, Object> body) {
        assertThat(api().http().post(SUBSCRIPTIONS, body, auth("platform-admin")).status()).isEqualTo(400);
    }

    @Test
    void undecodableBodyIsAValidationProblem() {
        Reply response = api().http().postRaw(SUBSCRIPTIONS, new byte[] {(byte) 0xff, (byte) 0xfe}, auth("platform-admin"));
        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void receiverWrittenFromTheDocsVerifiesARealDelivery() {
        subscribe();
        assertThat(escalate().status()).isEqualTo(200);
        assertThat(deliver()).isEqualTo(1);
        assertThat(receiver().rejected).isEmpty();
        Map<String, String> event = onlyEvent();
        JsonNode payload = JSON.readTree(event.get("body"));
        assertSchema("CaseEscalatedEvent", payload);
        assertThat(payload.get("id").asString()).isEqualTo(event.get("id"));
        assertThat(payload.get("data").get("caseId").asString()).isEqualTo("CS-10231");
        assertThat(event.get("correlationId")).isEqualTo("trace-webhook-01");
    }

    @Test
    void payloadCarriesNoFinancialDataOrFreeText() {
        subscribe();
        escalate();
        deliver();
        assertThat(onlyEvent().get("body")).doesNotContain("480000").doesNotContain(JUSTIFICATION)
                .doesNotContain("Backup failing");
    }

    @Test
    void noEventWithoutASuccessfulEscalation() {
        subscribe();
        assertThat(escalate("CS-10244", "esc-key-000000000001").status()).isEqualTo(409);
        assertThat(outbox()).isEmpty();
    }

    @Test
    void replayedRequestEmitsNoSecondEvent() {
        subscribe();
        Map<String, String> headers = auth();
        String etag = api().http().get("/v1/cases/CS-10231", headers).header("ETag");
        Map<String, String> request = with(headers, "Idempotency-Key", "esc-key-000000000001", "If-Match", etag);
        api().http().post("/v1/cases/CS-10231/escalate", BODY, request);
        api().http().post("/v1/cases/CS-10231/escalate", BODY, request);
        assertThat(outbox()).hasSize(1);
    }

    @Test
    void lostUpstreamResponseThenRetryEmitsOneEvent() {
        subscribe();
        Map<String, String> headers = auth();
        String etag = api().http().get("/v1/cases/CS-10231", headers).header("ETag");
        Map<String, String> request = with(headers, "Idempotency-Key", "esc-key-000000000001", "If-Match", etag);
        TestStack.upstreams().inject("cases", 0, null, null, 5);
        String url = "/v1/cases/CS-10231/escalate";
        assertThat(api().http().post(url, BODY, request).status()).isEqualTo(502);
        assertThat(outbox()).isEmpty();
        clearChaos();
        assertThat(api().http().post(url, BODY, request).status()).isEqualTo(200);
        assertThat(outbox()).hasSize(1);
    }

    @Test
    void failedDeliveryWaitsForBackoffThenRetriesWithTheSameId() {
        subscribe();
        escalate();
        receiver().behaviour.failNext = 1;
        deliver();
        assertThat(outbox().getFirst().get("status")).isEqualTo("pending");
        assertThat(outbox().getFirst().get("attempts")).isEqualTo(1);

        assertThat(deliver()).isZero(); // Not due yet.
        TestStack.CLOCK.advance(Duration.ofSeconds(80));
        assertThat(deliver()).isEqualTo(1);
        Map<String, Object> row = outbox().getFirst();
        assertThat(row.get("status")).isEqualTo("delivered");
        assertThat(receiver().events).containsOnlyKeys((String) row.get("event_id"));
    }

    @Test
    void givesUpAfterTheMaximumAttemptsAndKeepsTheHistory() {
        subscribe();
        escalate();
        receiver().behaviour.failNext = 100;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThat(deliver()).isEqualTo(1);
            TestStack.CLOCK.advance(Duration.ofHours(2));
        }
        assertThat(deliver()).isZero();
        Map<String, Object> row = outbox().getFirst();
        assertThat(row.get("status")).isEqualTo("dead");
        assertThat(row.get("attempts")).isEqualTo(MAX_ATTEMPTS);
        assertThat(api().db().sql("SELECT COUNT(*) FROM webhook_deliveries").query(Integer.class).single()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void status410StopsDeliveryToThatSubscription() {
        subscribe();
        escalate();
        receiver().behaviour.failNext = 1;
        receiver().behaviour.status = 410;
        deliver();
        assertThat(outbox().getFirst().get("status")).isEqualTo("dead");
        assertThat(api().db().sql("SELECT active FROM webhook_subscriptions").query(Integer.class).single()).isZero();
        escalate("CS-10240", "esc-key-000000000002");
        assertThat(outbox()).hasSize(1); // No new row for the inactive subscription.
    }

    @Test
    void aRedirectIsAFailedDeliveryAndIsNotFollowed() {
        subscribe();
        escalate();
        receiver().behaviour.failNext = 1;
        receiver().behaviour.status = 302;
        // Following this would get around the URL allow-list.
        receiver().behaviour.location = TestStack.RECEIVER_HTTP.url() + "/__redirect-target";
        deliver();
        assertThat(outbox().getFirst().get("status")).isEqualTo("pending");
        assertThat(receiver().deliveries.get()).isEqualTo(1);
        assertThat(receiver().redirectTargetHits.get()).isZero();
    }

    @Test
    void oneFailingSubscriberDoesNotStopAnother() {
        subscribe();
        subscribe();
        escalate();
        receiver().behaviour.failNext = 1;
        deliver();
        assertThat(outbox().stream().map(row -> row.get("status"))).containsExactlyInAnyOrder("delivered", "pending");
    }

    @Test
    void duplicateDeliveryIsDeduplicatedByTheReceiver() {
        subscribe();
        escalate();
        deliver();
        // As if the worker crashed after sending but before recording the result.
        api().db().sql("UPDATE outbox_events SET status = 'pending'").update();
        api().db().sql("DELETE FROM webhook_deliveries").update();
        api().db().sql("UPDATE outbox_events SET attempts = 0").update();
        deliver();
        assertThat(receiver().deliveries.get()).isEqualTo(2);
        assertThat(receiver().events).hasSize(1);
    }

    /** Captures a real signed delivery so that tests can tamper with it. */
    private final class CapturedDelivery {
        final String secret = subscribe().get("secret").asString();
        final String eventId;
        final String body;

        CapturedDelivery() {
            escalate();
            Map<String, Object> row = api().db().sql("SELECT event_id, payload FROM outbox_events").query().singleRow();
            eventId = (String) row.get("event_id");
            body = (String) row.get("payload");
        }

        Reply send(long timestamp, String content, String signature) {
            return TestStack.RECEIVER_HTTP.postRaw("/webhooks/case-escalated", content.getBytes(StandardCharsets.UTF_8), Map.of(
                    "webhook-id", eventId,
                    "webhook-timestamp", String.valueOf(timestamp),
                    "webhook-signature", signature != null ? signature : Webhooks.sign(secret, eventId, timestamp, body)));
        }

        Reply send(long timestamp) {
            return send(timestamp, body, null);
        }
    }

    private static long now() {
        return TestStack.CLOCK.instant().getEpochSecond();
    }

    @Test
    void tamperedBodyIsRejected() {
        CapturedDelivery delivery = new CapturedDelivery();
        assertThat(delivery.send(now(), delivery.body.replace("CS-10231", "CS-10240"), null).status()).isEqualTo(400);
        assertThat(receiver().rejected).containsExactly("bad_signature");
        assertThat(delivery.send(now()).status()).isEqualTo(200);
    }

    @Test
    void replayOfACapturedDeliveryIsRejected() {
        CapturedDelivery delivery = new CapturedDelivery();
        long capturedAt = now();
        assertThat(delivery.send(capturedAt).status()).isEqualTo(200);
        TestStack.CLOCK.advance(Duration.ofMinutes(6));
        assertThat(delivery.send(capturedAt).status()).isEqualTo(400);
        assertThat(receiver().rejected).containsExactly("stale_timestamp");
    }

    @Test
    void forgedSignatureIsRejected() {
        CapturedDelivery delivery = new CapturedDelivery();
        String forged = Webhooks.sign("wrong-secret", delivery.eventId, now(), delivery.body);
        assertThat(delivery.send(now(), delivery.body, forged).status()).isEqualTo(400);
    }

    @Test
    void expiredIdempotencyKeysArePurgedByTheWorker() {
        escalate();
        TestStack.CLOCK.advance(Duration.ofHours(25));
        deliver();
        assertThat(api().db().sql("SELECT COUNT(*) FROM idempotency_keys").query(Integer.class).single()).isZero();
    }

    @Test
    void signingSecretIsNeverLogged() {
        api();
        try (LogCapture logs = new LogCapture()) {
            String secret = subscribe().get("secret").asString();
            escalate();
            deliver();
            assertThat(logs.text()).doesNotContain(secret);
        }
    }

    @Test
    void backgroundWorkerDeliversWithoutBeingAsked() throws Exception {
        make("platform.outbox-poll=20ms");
        subscribe();
        escalate();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (receiver().events.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(receiver().events).hasSize(1);
    }
}
