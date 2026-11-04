package com.example.platform.web;

import com.example.platform.web.ApiProblem.Code;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opaque keyset cursors. A cursor stores the last row's sort key, so rows inserted during paging
 * can't cause skips or repeats, as they can with offsets. It is signed so clients can't forge one,
 * and tied to its filters because a position only means something within the same result set.
 */
public final class Cursors {
    private static final int SIGNATURE_BYTES = 16;
    private static final String INVALID = "This cursor is invalid. Restart from the first page.";

    /** A numeric rank and the case ID. Ties always break on ID, so the order is total. */
    public record SortKey(long value, String id) implements Comparable<SortKey> {
        @Override
        public int compareTo(SortKey other) {
            int byValue = Long.compare(value, other.value);
            return byValue != 0 ? byValue : id.compareTo(other.id);
        }
    }

    private final JsonMapper json;
    private final byte[] secret;

    public Cursors(JsonMapper json, String secret) {
        this.json = json;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String encode(SortKey key, Map<String, Object> filters) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("k", List.of(key.value(), key.id()));
        payload.put("f", Hashes.sha256Hex(canonical(filters)).substring(0, 16));
        byte[] body = json.writeValueAsBytes(payload);
        byte[] signed = new byte[SIGNATURE_BYTES + body.length];
        System.arraycopy(signature(body), 0, signed, 0, SIGNATURE_BYTES);
        System.arraycopy(body, 0, signed, SIGNATURE_BYTES, body.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signed);
    }

    public SortKey decode(String cursor, Map<String, Object> filters) {
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException malformed) {
            throw ApiProblem.of(Code.INVALID_CURSOR, INVALID);
        }
        if (raw.length <= SIGNATURE_BYTES) {
            throw ApiProblem.of(Code.INVALID_CURSOR, INVALID);
        }
        byte[] body = Arrays.copyOfRange(raw, SIGNATURE_BYTES, raw.length);
        byte[] supplied = Arrays.copyOf(raw, SIGNATURE_BYTES);
        if (!MessageDigest.isEqual(supplied, signature(body))) {
            throw ApiProblem.of(Code.INVALID_CURSOR, INVALID);
        }
        JsonNode payload = json.readTree(body);
        if (!payload.get("f").asString().equals(Hashes.sha256Hex(canonical(filters)).substring(0, 16))) {
            throw ApiProblem.of(Code.INVALID_CURSOR,
                    "This cursor was issued for different filters. Restart from the first page.");
        }
        JsonNode key = payload.get("k");
        return new SortKey(key.get(0).asLong(), key.get(1).asString());
    }

    private String canonical(Map<String, Object> filters) {
        return json.writeValueAsString(new TreeMap<>(filters));
    }

    private byte[] signature(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Arrays.copyOf(mac.doFinal(body), SIGNATURE_BYTES);
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
