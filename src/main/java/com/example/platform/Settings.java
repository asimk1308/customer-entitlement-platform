package com.example.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Runtime settings, with their values in application.properties. */
@ConfigurationProperties("platform")
public record Settings(
        Path dbPath,
        String upstreamUrl,
        String idpUrl,
        String issuer,
        String audience,
        String cursorSecret,
        Duration entitlementTtl,
        int rateLimitPerMinute,
        Set<String> webhookAllowedHosts,
        Duration outboxPoll,
        CallPolicy cases,
        CallPolicy entitlements,
        CallPolicy usage) {

    /**
     * @param deadline total time for one logical call, including retries and backoff
     * @param failureThreshold consecutive failures that open the circuit
     * @param resetAfter how long the circuit stays open before a probe
     */
    public record CallPolicy(
            Duration attemptTimeout,
            int maxAttempts,
            Duration deadline,
            @DefaultValue("50ms") Duration backoffBase,
            @DefaultValue("400ms") Duration backoffCap,
            @DefaultValue("5") int failureThreshold,
            @DefaultValue("30s") Duration resetAfter) {}
}
