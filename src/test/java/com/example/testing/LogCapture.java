package com.example.testing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/** Captures what the API logs, including structured fields. */
public final class LogCapture implements AutoCloseable {
    private final Logger root = (Logger) LoggerFactory.getLogger("com.example.platform");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture() {
        appender.start();
        root.addAppender(appender);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** Waits briefly, because the access log is written just after the response has been sent. */
    public Optional<ILoggingEvent> first(String message) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (true) {
            Optional<ILoggingEvent> found =
                    events().stream().filter(event -> event.getFormattedMessage().equals(message)).findFirst();
            if (found.isPresent() || System.nanoTime() > deadline) {
                return found;
            }
            Thread.onSpinWait();
        }
    }

    public static Object field(ILoggingEvent event, String name) {
        return event.getKeyValuePairs().stream().filter(pair -> pair.key.equals(name))
                .map(pair -> pair.value).findFirst().orElse(null);
    }

    /**
     * Throws if nothing was captured, so that a "this never appears in the logs" check can't pass only
     * because capturing failed. Starting a context resets logging, so start the API before capturing.
     */
    public String text() {
        if (events().isEmpty()) {
            throw new IllegalStateException("No log events were captured");
        }
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : events()) {
            all.append(event.getFormattedMessage()).append(' ').append(event.getMDCPropertyMap()).append(' ');
            List<KeyValuePair> pairs = event.getKeyValuePairs();
            if (pairs != null) {
                pairs.forEach(pair -> all.append(pair.key).append('=').append(pair.value).append(' '));
            }
        }
        return all.toString();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
    }
}
