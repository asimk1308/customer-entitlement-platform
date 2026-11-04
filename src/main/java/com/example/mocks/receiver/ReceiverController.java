package com.example.mocks.receiver;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Written only from the verification steps in openapi.yaml. It shares no code with the sender on
 * purpose: if those steps are wrong or incomplete, this stops working.
 */
@RestController
public class ReceiverController {
    public record Secret(String secret) {}

    public static class Behaviour {
        public int failNext;
        public int status = 500;
        public String location;
    }

    public final List<String> secrets = new CopyOnWriteArrayList<>();
    public final Map<String, Map<String, String>> events = new LinkedHashMap<>();
    public final List<String> rejected = new CopyOnWriteArrayList<>();
    public final AtomicInteger deliveries = new AtomicInteger();
    public final AtomicInteger redirectTargetHits = new AtomicInteger();
    public volatile Behaviour behaviour = new Behaviour();

    private final Clock clock;

    public ReceiverController(Clock clock) {
        this.clock = clock;
    }

    public synchronized void reset() {
        secrets.clear();
        events.clear();
        rejected.clear();
        deliveries.set(0);
        redirectTargetHits.set(0);
        behaviour = new Behaviour();
    }

    @RequestMapping(value = "/__redirect-target", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void redirectTarget() {
        redirectTargetHits.incrementAndGet();
    }

    @PostMapping("/__secret")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void addSecret(@RequestBody Secret secret) {
        secrets.add(secret.secret());
    }

    @PostMapping("/__behaviour")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setBehaviour(@RequestBody Behaviour next) {
        behaviour = next;
    }

    @GetMapping("/__events")
    synchronized Map<String, Object> listEvents() {
        return Map.of("events", new ArrayList<>(events.values()), "rejected", List.copyOf(rejected),
                "deliveries", deliveries.get());
    }

    @PostMapping("/webhooks/case-escalated")
    synchronized ResponseEntity<Void> receive(
            @RequestHeader(value = "webhook-id", defaultValue = "") String eventId,
            @RequestHeader(value = "webhook-timestamp", defaultValue = "") String timestamp,
            @RequestHeader(value = "webhook-signature", defaultValue = "") String signatures,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) byte[] rawBody) throws GeneralSecurityException {
        deliveries.incrementAndGet();
        Behaviour current = behaviour;
        if (current.failNext > 0) {
            current.failNext--;
            ResponseEntity.BodyBuilder response = ResponseEntity.status(current.status);
            if (current.location != null) {
                response.header("Location", current.location);
            }
            return response.build();
        }
        byte[] body = rawBody == null ? new byte[0] : rawBody;

        // Step 1: reject anything outside the 5-minute window.
        if (!timestamp.matches("\\d+") || Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp)) > 300) {
            rejected.add("stale_timestamp");
            return ResponseEntity.badRequest().build();
        }
        // Steps 2 to 4: recompute over the raw bytes and compare in constant time.
        byte[] prefix = (eventId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
        boolean valid = false;
        for (String secret : secrets) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(prefix);
            byte[] expected = ("v1," + Base64.getEncoder().encodeToString(mac.doFinal(body)))
                    .getBytes(StandardCharsets.UTF_8);
            for (String candidate : signatures.split(" ")) {
                valid |= MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (!valid) {
            rejected.add("bad_signature");
            return ResponseEntity.badRequest().build();
        }
        // Delivery is at least once, so deduplicate on the event ID.
        Map<String, String> event = new LinkedHashMap<>();
        event.put("id", eventId);
        event.put("body", new String(body, StandardCharsets.UTF_8));
        event.put("correlationId", correlationId);
        events.putIfAbsent(eventId, event);
        return ResponseEntity.ok().build();
    }
}
