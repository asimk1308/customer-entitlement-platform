package com.example.platform.webhook;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Webhook signing and URL checks. See the `case.escalated` webhook in openapi.yaml. */
public final class Webhooks {
    public static final int TOLERANCE_SECONDS = 300;
    private static final long BACKOFF_BASE_SECONDS = 60;
    private static final long BACKOFF_CAP_SECONDS = 3600;

    private Webhooks() {}

    public static String sign(String secret, String eventId, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((eventId + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static boolean verify(List<String> secrets, String eventId, long timestamp, String signatureHeader,
                                 String body, long now) {
        if (Math.abs(now - timestamp) > TOLERANCE_SECONDS) {
            return false;
        }
        for (String secret : secrets) {
            byte[] expected = sign(secret, eventId, timestamp, body).getBytes(StandardCharsets.UTF_8);
            for (String candidate : signatureHeader.split(" ")) {
                if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Exact host and port only. Otherwise a subscriber could make this service send requests to
     * internal addresses, such as a cloud metadata endpoint (SSRF).
     */
    public static boolean urlIsAllowed(String url, Set<String> allowedHosts) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException invalid) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getUserInfo() != null) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        return allowedHosts.contains(host + ":" + uri.getPort()) || allowedHosts.contains(host);
    }

    /**
     * Exponential, with jitter so that receivers recovering from an outage aren't hit all at once. The
     * 8 attempts span about 2 hours.
     */
    public static Duration nextDelay(int attempts) {
        long seconds = Math.min(BACKOFF_CAP_SECONDS, BACKOFF_BASE_SECONDS << (attempts - 1));
        return Duration.ofMillis((long) (seconds * 1000 * ThreadLocalRandom.current().nextDouble(0.8, 1.2)));
    }
}
