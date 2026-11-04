package com.example.platform.upstream;

import com.example.platform.Settings.CallPolicy;
import com.example.platform.web.CorrelationFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** One resilient HTTP client per upstream: timeouts, retries, circuit breaking, correlation IDs. */
public class UpstreamClient {
    private static final Logger LOG = LoggerFactory.getLogger(UpstreamClient.class);

    private final String name;
    private final HttpClient http;
    private final String baseUrl;
    private final CallPolicy policy;
    private final RetryBudget budget = new RetryBudget();
    private final CircuitBreaker breaker;
    private final AtomicReference<Long> openedAtNanos = new AtomicReference<>();

    public UpstreamClient(String name, HttpClient http, String baseUrl, CallPolicy policy) {
        this.name = name;
        this.http = http;
        this.baseUrl = baseUrl;
        this.policy = policy;
        // A count window the size of the threshold, at a 100% failure rate, means N consecutive
        // failures open the circuit. One probe passes after the cool-down and decides the next state.
        this.breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(policy.failureThreshold())
                .minimumNumberOfCalls(policy.failureThreshold())
                .failureRateThreshold(100)
                .waitDurationInOpenState(policy.resetAfter())
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        breaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State state = event.getStateTransition().getToState();
            if (state == CircuitBreaker.State.OPEN) {
                openedAtNanos.set(System.nanoTime());
            }
            LOG.atWarn().addKeyValue("upstream", name).addKeyValue("state", state.name().toLowerCase())
                    .log("circuit_state_changed");
        });
    }

    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    /**
     * Returns any response below 500, as a 404 or 409 means the upstream is healthy.
     *
     * @param retrySafe must be false for a write the upstream can't deduplicate. If such a write times
     *     out, its outcome is unknown, and a retry could apply it twice.
     */
    public HttpResponse<String> request(String method, String path, boolean retrySafe, String jsonBody) {
        int maxAttempts = retrySafe ? policy.maxAttempts() : 1;
        long deadline = System.nanoTime() + policy.deadline().toNanos();
        budget.recordRequest();
        String reason = "error";

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!breaker.tryAcquirePermission()) {
                throw new UpstreamFailure(name, "circuit_open", secondsUntilProbe());
            }
            long started = System.nanoTime();
            long remaining = deadline - started;
            CompletableFuture<HttpResponse<String>> call = null;
            try {
                call = http.sendAsync(build(method, path, jsonBody), HttpResponse.BodyHandlers.ofString());
                // Waiting on the whole exchange rather than using the client's own timeout, which stops
                // at the response headers. This bounds the attempt, including a slow response body.
                HttpResponse<String> response =
                        call.get(Math.min(policy.attemptTimeout().toNanos(), remaining), TimeUnit.NANOSECONDS);
                if (response.statusCode() < 500) {
                    breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    return response;
                }
                reason = "error";
            } catch (TimeoutException timedOut) {
                call.cancel(true);
                reason = "timeout";
            } catch (ExecutionException failed) {
                reason = "error";
            } catch (InterruptedException interrupted) {
                // A cancelled caller gives no verdict on the upstream, so free the probe slot.
                call.cancel(true);
                breaker.releasePermission();
                Thread.currentThread().interrupt();
                throw new UpstreamFailure(name, "error");
            }
            breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, new UpstreamFailure(name, reason));

            if (attempt == maxAttempts) {
                break;
            }
            // Full jitter, so that clients retrying together don't all hit the upstream together.
            long ceiling = Math.min(policy.backoffCap().toNanos(), policy.backoffBase().toNanos() << attempt);
            long pause = ThreadLocalRandom.current().nextLong(ceiling + 1);
            if (System.nanoTime() + pause >= deadline || !budget.trySpend()) {
                break;
            }
            LOG.atInfo().addKeyValue("upstream", name).addKeyValue("attempt", attempt).addKeyValue("reason", reason)
                    .log("upstream_retry");
            try {
                TimeUnit.NANOSECONDS.sleep(pause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new UpstreamFailure(name, reason);
    }

    private HttpRequest build(String method, String path, String jsonBody) {
        String correlationId = MDC.get(CorrelationFilter.CORRELATION_ID);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header(CorrelationFilter.CORRELATION_HEADER, correlationId == null ? "none" : correlationId)
                .timeout(Duration.ofSeconds(30));
        if (jsonBody == null) {
            return request.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
    }

    private double secondsUntilProbe() {
        Long openedAt = openedAtNanos.get();
        if (openedAt == null) {
            return 0;
        }
        double elapsed = (System.nanoTime() - openedAt) / 1e9;
        return Math.max(0, policy.resetAfter().toMillis() / 1000.0 - elapsed);
    }
}
