package com.example.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.platform.Settings.CallPolicy;
import com.example.platform.upstream.RetryBudget;
import com.example.platform.upstream.UpstreamClient;
import com.example.platform.upstream.UpstreamFailure;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a real local HTTP server, so that timeouts and connection failures are real. */
class UpstreamClientTest {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile List<Integer> statuses = List.of(200);
    private volatile long delayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            int call = calls.incrementAndGet();
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                int status = statuses.get(Math.min(call - 1, statuses.size() - 1));
                exchange.sendResponseHeaders(status, -1);
            } catch (InterruptedException | IOException ignored) {
                // The client gave up, which is the point of some of these tests.
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static CallPolicy policy(long attemptMillis, int attempts, long deadlineMillis, int threshold, Duration reset) {
        return new CallPolicy(Duration.ofMillis(attemptMillis), attempts, Duration.ofMillis(deadlineMillis),
                Duration.ofMillis(1), Duration.ofMillis(2), threshold, reset);
    }

    private static final CallPolicy POLICY = policy(100, 3, 500, 100, Duration.ofSeconds(30));

    private UpstreamClient client(CallPolicy policy) {
        return new UpstreamClient("cases", HTTP, "http://127.0.0.1:" + server.getAddress().getPort(), policy);
    }

    private static int get(UpstreamClient upstream) {
        return upstream.request("GET", "/", true, null).statusCode();
    }

    private static UpstreamFailure failure(UpstreamClient upstream, boolean retrySafe) {
        AtomicReference<UpstreamFailure> caught = new AtomicReference<>();
        assertThatThrownBy(() -> upstream.request("GET", "/", retrySafe, null))
                .isInstanceOfSatisfying(UpstreamFailure.class, caught::set);
        return caught.get();
    }

    @Test
    void retriesAServerErrorThenSucceeds() {
        statuses = List.of(500, 200);
        assertThat(get(client(POLICY))).isEqualTo(200);
        assertThat(calls).hasValue(2);
    }

    @Test
    void clientErrorsAreReturnedNotRetried() {
        statuses = List.of(404);
        assertThat(get(client(POLICY))).isEqualTo(404);
        assertThat(calls).hasValue(1);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        statuses = List.of(500);
        assertThat(failure(client(POLICY), true).reason).isEqualTo("error");
        assertThat(calls).hasValue(3);
    }

    @Test
    void aWriteTheUpstreamCannotDedupeIsNeverRetried() {
        statuses = List.of(500);
        failure(client(POLICY), false);
        assertThat(calls).hasValue(1);
    }

    @Test
    void timeoutsAreEnforcedAndReported() {
        delayMillis = 5000;
        long started = System.nanoTime();
        assertThat(failure(client(POLICY), true).reason).isEqualTo("timeout");
        assertThat((System.nanoTime() - started) / 1e9).isLessThan(1);
    }

    @Test
    void deadlineStopsRetriesEarly() {
        delayMillis = 5000;
        long started = System.nanoTime();
        failure(client(policy(100, 50, 250, 100, Duration.ofSeconds(30))), true);
        assertThat((System.nanoTime() - started) / 1e9).isLessThan(0.6);
        assertThat(calls.get()).isLessThanOrEqualTo(3); // No more calls once there's no time left to use them.
    }

    @Test
    void anAttemptNeverOutlastsTheDeadline() {
        delayMillis = 5000;
        long started = System.nanoTime();
        failure(client(policy(2000, 1, 200, 100, Duration.ofSeconds(30))), true);
        assertThat((System.nanoTime() - started) / 1e9).isLessThan(0.6);
    }

    /** Out of time isn't the upstream's fault, so an attempt that can't succeed mustn't count against it. */
    @Test
    void noAttemptIsMadeOnceTheDeadlineHasPassed() {
        delayMillis = 5000;
        UpstreamClient upstream = client(policy(100, 50, 150, 3, Duration.ofSeconds(30)));
        failure(upstream, true); // Time for two attempts, against a threshold of three.
        assertThat(upstream.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void connectionErrorsAreRetried() {
        server.stop(0);
        assertThat(failure(client(POLICY), true).reason).isEqualTo("error");
    }

    @Test
    void openCircuitFailsWithoutCallingUpstream() {
        statuses = List.of(500);
        UpstreamClient upstream = client(policy(100, 1, 500, 1, Duration.ofSeconds(60)));
        failure(upstream, true);
        UpstreamFailure rejected = failure(upstream, true);
        assertThat(rejected.reason).isEqualTo("circuit_open");
        assertThat(rejected.retryAfter).isPositive();
        assertThat(calls).hasValue(1);
    }

    @Test
    void retryBudgetLimitsRetriesAcrossManyCalls() {
        statuses = List.of(500);
        UpstreamClient upstream = client(POLICY);
        for (int i = 0; i < 20; i++) {
            failure(upstream, true);
        }
        // Unlimited retries would make 60 calls: 3 for each of the 20 requests.
        assertThat(calls.get()).isLessThan(40);
    }

    // The breaker's configuration, as Resilience4j supplies the state machine.

    private UpstreamClient breakerOfTwo(Duration reset) {
        return client(policy(100, 1, 500, 2, reset));
    }

    @Test
    void circuitStaysClosedBelowTheThreshold() {
        statuses = List.of(500);
        UpstreamClient upstream = breakerOfTwo(Duration.ofSeconds(60));
        failure(upstream, true);
        assertThat(upstream.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void onlyConsecutiveFailuresOpenTheCircuit() {
        statuses = List.of(500, 200, 500, 200, 500);
        UpstreamClient upstream = breakerOfTwo(Duration.ofSeconds(60));
        failure(upstream, true);
        get(upstream);
        failure(upstream, true);
        get(upstream);
        failure(upstream, true);
        assertThat(upstream.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void circuitOpensAtTheThreshold() {
        statuses = List.of(500);
        UpstreamClient upstream = breakerOfTwo(Duration.ofSeconds(60));
        failure(upstream, true);
        failure(upstream, true);
        assertThat(upstream.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(failure(upstream, true).reason).isEqualTo("circuit_open");
    }

    @Test
    void successfulProbeClosesTheCircuit() throws Exception {
        statuses = List.of(500, 500, 200);
        UpstreamClient upstream = breakerOfTwo(Duration.ofMillis(50));
        failure(upstream, true);
        failure(upstream, true);
        Thread.sleep(80);
        assertThat(get(upstream)).isEqualTo(200);
        assertThat(upstream.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void failedProbeStartsANewCoolDown() throws Exception {
        statuses = List.of(500);
        UpstreamClient upstream = breakerOfTwo(Duration.ofMillis(150));
        failure(upstream, true);
        failure(upstream, true);
        Thread.sleep(200);
        failure(upstream, true); // The probe, which reaches the upstream and fails.
        assertThat(calls).hasValue(3);
        assertThat(failure(upstream, true).reason).isEqualTo("circuit_open");
        assertThat(calls).hasValue(3);
        Thread.sleep(200);
        failure(upstream, true);
        assertThat(calls).hasValue(4);
    }

    @Test
    void halfOpenAllowsExactlyOneProbeAtATime() throws Exception {
        statuses = List.of(500, 500, 200);
        UpstreamClient upstream = client(policy(1000, 1, 2000, 2, Duration.ofMillis(50)));
        failure(upstream, true);
        failure(upstream, true);
        Thread.sleep(80);
        delayMillis = 300;
        Thread probe = Thread.ofVirtual().start(() -> get(upstream));
        Thread.sleep(100);
        assertThat(failure(upstream, true).reason).isEqualTo("circuit_open");
        probe.join();
        assertThat(upstream.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /** A cancelled caller gives no verdict. Without releasing the slot, the circuit would wedge for good. */
    @Test
    void cancelledProbeDoesNotWedgeTheCircuit() throws Exception {
        statuses = List.of(500, 500, 200, 200);
        UpstreamClient upstream = client(policy(2000, 1, 3000, 2, Duration.ofMillis(50)));
        failure(upstream, true);
        failure(upstream, true);
        Thread.sleep(80);
        delayMillis = 1000;
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                upstream.request("GET", "/", true, null);
            } catch (UpstreamFailure expected) {
                // Interrupted below.
            }
        });
        Thread.sleep(100);
        caller.interrupt();
        caller.join();
        delayMillis = 0;
        assertThat(get(upstream)).isEqualTo(200);
    }

    @Test
    void retryBudgetRunsOutAndRefillsWithTraffic() {
        RetryBudget budget = new RetryBudget(0.5, 2);
        assertThat(budget.trySpend()).isTrue();
        assertThat(budget.trySpend()).isTrue();
        assertThat(budget.trySpend()).isFalse();
        budget.recordRequest();
        budget.recordRequest();
        assertThat(budget.trySpend()).isTrue();
    }
}
