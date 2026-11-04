package com.example.platform.security;

import com.example.platform.Settings;
import com.example.platform.web.ApiProblem;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * A fixed-window limit per user and agent pair, so that one agent can't use up its user's quota, or
 * one user another's. In memory for this single process; PLATFORM.md covers the gateway's role.
 */
@Component
public class RateLimiter {
    private static final int WINDOW_SECONDS = 60;

    private final int limit;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private long window = -1;
    private Map<String, Integer> counts = new HashMap<>();

    public RateLimiter(Settings settings, Clock clock) {
        this.limit = settings.rateLimitPerMinute();
        this.clock = clock;
    }

    public void check(HttpServletResponse response, Principal principal) {
        long now = clock.instant().getEpochSecond();
        long current = now / WINDOW_SECONDS;
        int count;
        lock.lock();
        try {
            if (current != window) {
                window = current;
                counts = new HashMap<>(); // Dropping old counts also keeps memory bounded.
            }
            count = counts.merge(principal.subject() + "|" + principal.agent(), 1, Integer::sum);
        } finally {
            lock.unlock();
        }
        long reset = Math.max(1, (current + 1) * WINDOW_SECONDS - now);
        response.setHeader("RateLimit-Limit", String.valueOf(limit));
        response.setHeader("RateLimit-Remaining", String.valueOf(Math.max(0, limit - count)));
        response.setHeader("RateLimit-Reset", String.valueOf(reset));
        if (count > limit) {
            throw ApiProblem.withHeaders(ApiProblem.Code.RATE_LIMITED, "Retry after " + reset + " seconds.",
                    Map.of("Retry-After", String.valueOf(reset)));
        }
    }
}
