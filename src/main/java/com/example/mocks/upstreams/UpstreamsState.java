package com.example.mocks.upstreams;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Everything the mock holds in memory. Public so that in-process tests can arrange scenarios. */
@Component
public class UpstreamsState {
    public final Map<String, Chaos> chaos = new ConcurrentHashMap<>();
    /** externalId to stored result, as Salesforce external IDs allow. */
    public final Map<String, Object> writeResults = new ConcurrentHashMap<>();
    public volatile Map<String, Map<String, Object>> cases;
    /** Runs just before a transition is applied, so that a test can change the case at that moment. */
    public volatile Runnable beforeTransition;

    private final Clock clock;

    public UpstreamsState(Clock clock) {
        this.clock = clock;
        reset();
    }

    public final void reset() {
        cases = new ConcurrentHashMap<>(Seed.cases(clock.instant()));
        chaos.clear();
        writeResults.clear();
        beforeTransition = null;
    }

    public void inject(String upstream, int latencyMs, Integer errorStatus, Integer failNext, int loseResponseNext) {
        Chaos fault = new Chaos();
        fault.upstream = upstream;
        fault.latencyMs = latencyMs;
        fault.errorStatus = errorStatus;
        fault.failNext = failNext;
        fault.loseResponseNext = loseResponseNext;
        chaos.put(upstream, fault);
    }
}
