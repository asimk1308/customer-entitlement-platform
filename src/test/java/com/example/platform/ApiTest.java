package com.example.platform;

import com.example.testing.Api;
import com.example.testing.TestStack;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** Gives each test clean mocks and, on first use, its own API. */
abstract class ApiTest {
    @TempDir
    Path tmp;

    private final List<Api> started = new ArrayList<>();
    private Api api;

    @BeforeEach
    void resetMocks() {
        TestStack.resetMocks();
    }

    @AfterEach
    void stopApis() {
        started.forEach(Api::close);
    }

    Api api() {
        if (api == null) {
            api = make();
        }
        return api;
    }

    /** Builds an API with these settings. Calling this twice is a restart, as the database is kept. */
    Api make(String... overrides) {
        Api built = TestStack.startApi(tmp.resolve("platform.db"), List.of(overrides));
        started.add(built);
        api = built;
        return built;
    }

    static Map<String, String> with(Map<String, String> headers, String... more) {
        Map<String, String> merged = new HashMap<>(headers);
        for (int i = 0; i < more.length; i += 2) {
            merged.put(more[i], more[i + 1]);
        }
        return merged;
    }

    static void chaosError(String upstream, int status) {
        TestStack.upstreams().inject(upstream, 0, status, null, 0);
    }

    static void chaosLatency(String upstream, int millis) {
        TestStack.upstreams().inject(upstream, millis, null, null, 0);
    }

    static void clearChaos() {
        TestStack.upstreams().chaos.clear();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> upstreamComments(String caseId) {
        return (List<Map<String, Object>>) TestStack.upstreams().cases.get(caseId).get("Comments");
    }

    static Object upstreamStatus(String caseId) {
        return TestStack.upstreams().cases.get(caseId).get("Status");
    }

    static void bumpVersion(String caseId) {
        Map<String, Object> target = TestStack.upstreams().cases.get(caseId);
        target.put("version", (Integer) target.get("version") + 1);
    }
}
