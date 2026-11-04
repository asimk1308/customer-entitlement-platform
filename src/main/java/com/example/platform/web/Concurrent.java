package com.example.platform.web;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.MDC;

/** Runs work concurrently on virtual threads, keeping the correlation ID in its logs. */
public final class Concurrent {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private Concurrent() {}

    public static <T> CompletableFuture<T> supply(Supplier<T> work) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                return work.get();
            } finally {
                MDC.clear();
            }
        }, EXECUTOR);
    }

    /** Waits for the result and rethrows the work's own exception rather than a wrapper. */
    public static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw wrapped;
        }
    }
}
