package com.example.platform.webhook;

import com.example.platform.Settings;
import com.example.platform.store.Store;
import com.example.platform.web.Concurrent;
import com.example.platform.web.CorrelationFilter;
import com.example.platform.web.Times;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Delivers at least once. A crash between sending and recording means a duplicate delivery, which is
 * why receivers deduplicate on {@code webhook-id}.
 */
@Component
public class OutboxWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxWorker.class);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 8;
    private static final int BATCH_SIZE = 50;

    private record Due(long id, String eventId, String subscriptionId, String payload, String correlationId,
                       int attempts, String url, String secret) {}

    private final Store store;
    private final Clock clock;
    private final Duration poll;
    // Must not follow redirects, which could get around the URL allow-list.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(DELIVERY_TIMEOUT)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    private volatile Thread worker;

    public OutboxWorker(Store store, Clock clock, Settings settings) {
        this.store = store;
        this.clock = clock;
        this.poll = settings.outboxPoll();
    }

    /** Runs one delivery cycle and returns how many deliveries it attempted. */
    public int runOnce() {
        List<Due> rows = due();
        CompletableFuture.allOf(rows.stream()
                        .map(row -> Concurrent.supply(() -> {
                            deliver(row);
                            return null;
                        }))
                        .toArray(CompletableFuture[]::new))
                .join();
        return rows.size();
    }

    private List<Due> due() {
        String now = Times.stored(clock.instant());
        store.db.sql("DELETE FROM idempotency_keys WHERE expires_at <= ?").param(now).update();
        // A receiver that answered 410 gets nothing more, including events already waiting for it.
        store.db.sql("UPDATE outbox_events SET status = 'dead' WHERE status = 'pending' AND subscription_id IN "
                + "(SELECT id FROM webhook_subscriptions WHERE active = 0)").update();
        return store.db.sql("SELECT o.id, o.event_id, o.subscription_id, o.payload, o.correlation_id, o.attempts, "
                        + "s.url, s.secret FROM outbox_events o JOIN webhook_subscriptions s ON s.id = o.subscription_id "
                        + "WHERE o.status = 'pending' AND s.active = 1 AND o.next_attempt_at <= ? ORDER BY o.next_attempt_at LIMIT ?")
                .params(now, BATCH_SIZE)
                .query((row, index) -> new Due(row.getLong(1), row.getString(2), row.getString(3), row.getString(4),
                        row.getString(5), row.getInt(6), row.getString(7), row.getString(8)))
                .list();
    }

    private void deliver(Due row) {
        if (row.correlationId() != null) {
            MDC.put(CorrelationFilter.CORRELATION_ID, row.correlationId());
        }
        int attempt = row.attempts() + 1;
        Integer statusCode = null;
        String error = null;
        try {
            statusCode = send(row);
        } catch (TimeoutException timedOut) {
            error = "timeout";
        } catch (ExecutionException | IllegalArgumentException failed) {
            error = failed.getClass().getSimpleName();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return; // Shutting down. It stays pending, and the next start delivers it.
        }
        record(row, attempt, statusCode, error);
    }

    private int send(Due row) throws TimeoutException, ExecutionException, InterruptedException {
        long timestamp = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create(row.url()))
                .header("Content-Type", "application/json")
                .header("webhook-id", row.eventId())
                .header("webhook-timestamp", String.valueOf(timestamp))
                .header("webhook-signature", Webhooks.sign(row.secret(), row.eventId(), timestamp, row.payload()))
                .header(CorrelationFilter.CORRELATION_HEADER, row.correlationId() == null ? "none" : row.correlationId())
                .POST(HttpRequest.BodyPublishers.ofString(row.payload()))
                .build();
        CompletableFuture<HttpResponse<Void>> call = http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try {
            return call.get(DELIVERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).statusCode();
        } catch (TimeoutException timedOut) {
            call.cancel(true);
            throw timedOut;
        }
    }

    private void record(Due row, int attempt, Integer statusCode, String error) {
        Instant now = clock.instant();
        boolean delivered = statusCode != null && statusCode >= 200 && statusCode < 300;
        boolean gone = statusCode != null && statusCode == 410;
        String status;
        Instant nextAttempt = now;
        if (delivered) {
            status = "delivered";
        } else if (gone || attempt >= MAX_ATTEMPTS) {
            status = "dead";
        } else {
            status = "pending";
            nextAttempt = now.plus(Webhooks.nextDelay(attempt));
        }
        String next = Times.stored(nextAttempt);
        store.transaction.executeWithoutResult(transaction -> {
            store.db.sql("INSERT INTO webhook_deliveries VALUES (?, ?, ?, ?, ?)")
                    .params(row.id(), attempt, statusCode, error, Times.stored(now)).update();
            store.db.sql("UPDATE outbox_events SET status = ?, attempts = ?, next_attempt_at = ? WHERE id = ?")
                    .params(status, attempt, next, row.id()).update();
            if (gone) {
                store.db.sql("UPDATE webhook_subscriptions SET active = 0 WHERE id = ?")
                        .param(row.subscriptionId()).update();
            }
        });
        if (delivered) {
            LOG.atInfo().addKeyValue("eventId", row.eventId()).addKeyValue("attempt", attempt)
                    .addKeyValue("status", statusCode).log("webhook_delivered");
        } else {
            LOG.atWarn().addKeyValue("eventId", row.eventId()).addKeyValue("attempt", attempt)
                    .addKeyValue("status", statusCode).addKeyValue("error", error).addKeyValue("outcome", status)
                    .log("webhook_failed");
        }
    }

    private void runForever() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                runOnce();
            } catch (RuntimeException failure) { // One bad cycle must not stop delivery for good.
                LOG.atError().setCause(failure).log("outbox_cycle_failed");
            }
            try {
                Thread.sleep(poll);
            } catch (InterruptedException interrupted) {
                return;
            }
        }
    }

    @Override
    public void start() {
        if (!poll.isZero()) {
            worker = Thread.ofVirtual().name("outbox-worker").start(this::runForever);
        }
    }

    @Override
    public void stop() {
        Thread running = worker;
        worker = null;
        if (running != null) {
            running.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return worker != null;
    }
}
