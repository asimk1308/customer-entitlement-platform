package com.example.platform.api;

import com.example.platform.Settings;
import com.example.platform.security.Principal;
import com.example.platform.security.RequiresScope;
import com.example.platform.store.Store;
import com.example.platform.web.ApiProblem;
import com.example.platform.web.ApiProblem.Code;
import com.example.platform.web.Times;
import com.example.platform.webhook.Webhooks;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
class WebhookSubscriptionController {
    private static final SecureRandom RANDOM = new SecureRandom();

    record NewSubscription(
            @NotNull(message = "This field is required.") @Size(max = 2048) String url,
            @NotEmpty(message = "At least one event is required.")
                    List<@NotNull @Pattern(regexp = "case\\.escalated", message = "Not a known event.") String> events) {}

    record Subscription(String id, String url, List<String> events, boolean active, String createdAt, String secret) {}

    private final Store store;
    private final Settings settings;
    private final Clock clock;
    private final JsonMapper json;

    WebhookSubscriptionController(Store store, Settings settings, Clock clock, JsonMapper json) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
        this.json = json;
    }

    @RequiresScope("webhooks:manage")
    @PostMapping("/v1/webhook-subscriptions")
    ResponseEntity<Subscription> create(Principal principal, @Valid @RequestBody NewSubscription subscription) {
        if (new HashSet<>(subscription.events()).size() != subscription.events().size()) {
            throw ApiProblem.invalid("body.events", "Values must be unique.");
        }
        if (!Webhooks.urlIsAllowed(subscription.url(), settings.webhookAllowedHosts())) {
            throw ApiProblem.of(Code.WEBHOOK_URL_NOT_ALLOWED, "This URL isn't on the platform's allow-list.");
        }
        Instant now = clock.instant();
        String id = "wh_" + UUID.randomUUID().toString().replace("-", "");
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        store.transaction.executeWithoutResult(status -> {
            store.db.sql("INSERT INTO webhook_subscriptions (id, owner, url, events, secret, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(id, principal.subject(), subscription.url(), json.writeValueAsString(subscription.events()), secret,
                            Times.stored(now))
                    .update();
            store.audit(now, principal.subject(), principal.agent(), "webhook_subscription.create", null, null, "success",
                    json.writeValueAsString(Map.of("subscriptionId", id)));
        });
        return Views.json(201).body(new Subscription(id, subscription.url(), subscription.events(), true, Times.api(now), secret));
    }
}
