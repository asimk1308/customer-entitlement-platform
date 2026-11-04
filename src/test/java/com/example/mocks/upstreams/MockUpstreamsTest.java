package com.example.mocks.upstreams;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.platform.upstream.Adapters;
import com.example.platform.upstream.Models.Usage;
import com.example.testing.Http;
import com.example.testing.TestStack;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MockUpstreamsTest {
    private static final String USAGE_SQL = "SELECT month, protected_tb, api_calls, active_users FROM usage "
            + "WHERE account_id = ? ORDER BY month DESC LIMIT 1";
    private static final Map<String, Object> STATEMENT = Map.of("statement", USAGE_SQL, "bindings", List.of("ACC-007"));

    private final Http client = TestStack.UPSTREAM_HTTP;

    @BeforeEach
    void reset() {
        TestStack.resetMocks();
    }

    private static Map<String, Object> comment() {
        return Map.of("externalId", "ext-1", "author", "jdoe", "body", "Looking");
    }

    @Test
    void rawCasePayloadKeepsTheUpstreamShape() {
        JsonNode raw = client.get("/cases/CS-10231").json();
        assertThat(raw.get("Status").asString()).isEqualTo("open");
        assertThat(raw.get("Comments").get(0).get("body").asString()).isEqualTo("Investigating");
    }

    @Test
    void allCasesParseThroughTheAdapter() {
        JsonNode records = client.get("/cases").json().get("records");
        Set<String> ids = new HashSet<>();
        records.forEach(raw -> ids.add(Adapters.parseCase(raw).id()));
        assertThat(ids).hasSize(records.size());
    }

    @Test
    void sameExternalIdCreatesOnlyOneComment() {
        JsonNode first = client.post("/cases/CS-10232/comments", comment(), Map.of()).json();
        JsonNode second = client.post("/cases/CS-10232/comments", comment(), Map.of()).json();
        assertThat(second).isEqualTo(first);
        assertThat(client.get("/cases/CS-10232").json().get("Comments").size()).isEqualTo(1);
    }

    @Test
    void aCommentChangesTheCaseVersion() {
        client.post("/cases/CS-10232/comments", comment(), Map.of());
        assertThat(client.get("/cases/CS-10232").json().get("version").asInt()).isEqualTo(2);
    }

    @Test
    void transitionRejectsAStaleVersion() {
        Map<String, Object> body = Map.of("externalId", "t1", "to", "escalated", "expectedVersion", 99);
        assertThat(client.post("/cases/CS-10232/transition", body, Map.of()).status()).isEqualTo(409);
    }

    @Test
    void transitionRetryAfterSuccessReturnsTheStoredResult() {
        Map<String, Object> body = Map.of("externalId", "t2", "to", "escalated", "expectedVersion", 1);
        assertThat(client.post("/cases/CS-10232/transition", body, Map.of()).json().get("Status").asString()).isEqualTo("escalated");
        // The version is now 2, so only the external ID can make this retry succeed.
        assertThat(client.post("/cases/CS-10232/transition", body, Map.of()).status()).isEqualTo(200);
    }

    @Test
    void warehouseAnswersParameterisedSqlWithTheAppendixRow() {
        Usage usage = Adapters.parseLatestUsage(client.post("/warehouse/statements", STATEMENT, Map.of()).json());
        assertThat(usage.month()).isEqualTo("2026-10");
        assertThat(usage.protectedTb()).isEqualTo(412.5);
    }

    @Test
    void warehouseCannotBeWrittenTo() {
        Map<String, Object> body = Map.of("statement", "DELETE FROM usage", "bindings", List.of());
        assertThat(client.post("/warehouse/statements", body, Map.of()).status()).isEqualTo(400);
    }

    @Test
    void chaosFailsOneUpstreamAndLeavesTheOthersAlone() {
        client.post("/__chaos", Map.of("upstream", "usage", "errorStatus", 500), Map.of());
        assertThat(client.post("/warehouse/statements", STATEMENT, Map.of()).status()).isEqualTo(500);
        assertThat(client.get("/cases/CS-10231").status()).isEqualTo(200);
    }

    @Test
    void chaosCanFailAFixedNumberOfRequests() {
        client.post("/__chaos", Map.of("upstream", "cases", "errorStatus", 503, "failNext", 2), Map.of());
        assertThat(List.of(client.get("/cases/CS-10231").status(), client.get("/cases/CS-10231").status(),
                client.get("/cases/CS-10231").status())).containsExactly(503, 503, 200);
    }

    @Test
    void chaosCanBeCleared() {
        client.post("/__chaos", Map.of("upstream", "cases", "errorStatus", 503), Map.of());
        client.request("DELETE", "/__chaos", Map.of());
        assertThat(client.get("/cases/CS-10231").status()).isEqualTo(200);
    }

    @Test
    void entitlementExportsParse() {
        assertThat(Adapters.parseEntitlementCsv(client.get("/entitlements/export.csv").text())).hasSize(Seed.ACCOUNTS.size() + 1);
        assertThat(Adapters.parseEntitlementXml(client.get("/entitlements/feed.xml").text())).hasSize(Seed.ACCOUNTS.size() + 1);
    }

    @Test
    void casesCanBeArrangedForBlackBoxTests() {
        client.post("/__cases/CS-10231", Map.of("latestCommentBody", "rewritten", "version", 7), Map.of());
        JsonNode raw = client.get("/cases/CS-10231").json();
        assertThat(raw.get("version").asInt()).isEqualTo(7);
        JsonNode comments = raw.get("Comments");
        assertThat(comments.get(comments.size() - 1).get("body").asString()).isEqualTo("rewritten");
        assertThat(client.post("/__reset", Map.of(), Map.of()).status()).isEqualTo(204);
        assertThat(client.get("/cases/CS-10231").json().get("version").asInt()).isEqualTo(1);
    }
}
