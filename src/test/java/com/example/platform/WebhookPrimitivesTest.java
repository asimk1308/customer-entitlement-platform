package com.example.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.platform.webhook.Webhooks;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WebhookPrimitivesTest {
    private static final String BODY = "{\"id\":\"evt_1\"}";
    private static final long NOW = 1_800_000_000L;

    private static String signature(String secret, String eventId, long timestamp, String body) {
        return Webhooks.sign(secret, eventId, timestamp, body);
    }

    private static String signature() {
        return signature("s1", "evt_1", NOW, BODY);
    }

    private static boolean verify(String header, List<String> secrets, String eventId, long timestamp, String body) {
        return Webhooks.verify(secrets, eventId, timestamp, header, body, NOW);
    }

    private static boolean verify(String header) {
        return verify(header, List.of("s1"), "evt_1", NOW, BODY);
    }

    @Test
    void validSignatureVerifies() {
        assertThat(verify(signature())).isTrue();
    }

    @Test
    void changedBodyFails() {
        assertThat(verify(signature(), List.of("s1"), "evt_1", NOW, "{\"id\":\"evt_2\"}")).isFalse();
    }

    @Test
    void changedEventIdFails() {
        assertThat(verify(signature(), List.of("s1"), "evt_2", NOW, BODY)).isFalse();
    }

    @Test
    void signatureCannotBeMovedToANewTimestamp() {
        assertThat(verify(signature("s1", "evt_1", NOW - 1000, BODY))).isFalse();
    }

    @Test
    void oldDeliveryIsRejectedEvenWithAValidSignature() {
        assertThat(verify(signature("s1", "evt_1", NOW - 300, BODY), List.of("s1"), "evt_1", NOW - 300, BODY)).isTrue();
        assertThat(verify(signature("s1", "evt_1", NOW - 301, BODY), List.of("s1"), "evt_1", NOW - 301, BODY)).isFalse();
    }

    @Test
    void farFutureTimestampIsRejected() {
        assertThat(verify(signature("s1", "evt_1", NOW + 301, BODY), List.of("s1"), "evt_1", NOW + 301, BODY)).isFalse();
    }

    @Test
    void rotationNewSignatureWithEitherSecretAccepted() {
        String withNew = signature("new", "evt_1", NOW, BODY);
        String withOld = signature("old", "evt_1", NOW, BODY);
        assertThat(verify(withNew, List.of("old", "new"), "evt_1", NOW, BODY)).isTrue();
        assertThat(verify(withOld + " " + withNew, List.of("new"), "evt_1", NOW, BODY)).isTrue();
    }

    @Test
    void wrongSecretFails() {
        assertThat(verify(signature("other", "evt_1", NOW, BODY))).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " | ", value = {
        "http://localhost:9002/hook | true",
        "https://localhost:9002/hook | true",
        "http://localhost:9003/hook | false",
        "http://169.254.169.254/latest/meta-data/ | false",
        "http://localhost:9002@evil.example/ | false",
        "http://evil.example/#localhost:9002 | false",
        "http://evil.example\\@localhost:9002/ | false",
        "http://user:pw@localhost:9002/ | false",
        "ftp://localhost:9002/ | false",
        "file:///etc/passwd | false",
        "http://localhost:notaport/ | false",
        "not a url | false",
    })
    void urlAllowList(String url, boolean allowed) {
        assertThat(Webhooks.urlIsAllowed(url, Set.of("localhost:9002"))).isEqualTo(allowed);
    }

    @Test
    void backoffGrowsThenIsCapped() {
        assertThat(Webhooks.nextDelay(1)).isBetween(Duration.ofSeconds(48), Duration.ofSeconds(72));
        assertThat(Webhooks.nextDelay(2)).isBetween(Duration.ofSeconds(96), Duration.ofSeconds(144));
        assertThat(Webhooks.nextDelay(20)).isLessThanOrEqualTo(Duration.ofSeconds(4320));
    }
}
