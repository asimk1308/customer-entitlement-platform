package com.example.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.testing.Contract;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks on openapi.yaml itself. Structural validators accept schemas that no payload can satisfy, so
 * these validate real payloads against the schemas.
 */
class SpecTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Map<String, Object> comment() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", "c1");
        value.put("author", "jdoe");
        value.put("agent", null);
        value.put("body", "Investigating");
        value.put("visibility", "internal");
        value.put("createdAt", "2026-10-24T09:00:00Z");
        return value;
    }

    private static Map<String, Object> caseSummary() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", "CS-10231");
        value.put("accountId", "ACC-007");
        value.put("customerName", "Acme Corp");
        value.put("priority", "P1");
        value.put("status", "open");
        value.put("openedAt", "2026-10-24T08:12:00Z");
        value.put("subject", "Backup failing on node 3");
        value.put("commentCount", 1);
        value.put("latestComment", comment());
        value.put("supportTier", "Platinum");
        value.put("renewalDate", "2027-01-15");
        value.put("etag", "\"abc\"");
        return value;
    }

    private static Map<String, Object> withField(Map<String, Object> base, String name, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(base);
        changed.put(name, value);
        return changed;
    }

    private static Map<String, Object> caseDetail(List<?> comments) {
        Map<String, Object> value = withField(caseSummary(), "comments", comments);
        value.put("degraded", false);
        value.put("degradedSources", List.of());
        return value;
    }

    private static Map<String, Object> escalatedEvent() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseId", "CS-10231");
        data.put("accountId", "ACC-007");
        data.put("priority", "P1");
        data.put("reasonCode", "sla_breach");
        data.put("escalatedBy", "jdoe");
        data.put("agent", null);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", "evt_1");
        value.put("type", "case.escalated");
        value.put("occurredAt", "2026-10-24T09:00:00Z");
        value.put("data", data);
        return value;
    }

    static Stream<Arguments> validPayloads() {
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", "wh_1");
        created.put("url", "http://localhost:9000/hook");
        created.put("events", List.of("case.escalated"));
        created.put("active", true);
        created.put("createdAt", "2026-10-24T09:00:00Z");
        created.put("secret", "s3cret");
        return Stream.of(
                Arguments.of("CaseSummary", caseSummary()),
                Arguments.of("Case", caseDetail(List.of(comment()))),
                Arguments.of("CreatedWebhookSubscription", created),
                Arguments.of("CaseEscalatedEvent", escalatedEvent()));
    }

    @ParameterizedTest
    @MethodSource("validPayloads")
    void validPayloadsAreAccepted(String schema, Map<String, Object> payload) {
        assertThat(Contract.errors(schema, JSON.valueToTree(payload))).isEmpty();
    }

    @SuppressWarnings("unchecked")
    static Stream<Arguments> payloadsWithUnexpectedFields() {
        Map<String, Object> eventData = new HashMap<>((Map<String, Object>) escalatedEvent().get("data"));
        eventData.put("justification", "text");
        return Stream.of(
                // Upstream field names must not leak through.
                Arguments.of("Case", withField(caseDetail(List.of()), "Status", "open")),
                Arguments.of("CaseSummary", withField(caseSummary(), "arr_usd", 480000)),
                // A list row is not the detail shape.
                Arguments.of("CaseSummary", withField(caseSummary(), "comments", List.of())),
                // Webhook payloads stay thin: no financial data or free text.
                Arguments.of("CaseEscalatedEvent", withField(escalatedEvent(), "arrUsd", 480000)),
                Arguments.of("CaseEscalatedEvent", withField(escalatedEvent(), "data", eventData)));
    }

    @ParameterizedTest
    @MethodSource("payloadsWithUnexpectedFields")
    void unexpectedFieldsAreRejected(String schema, Map<String, Object> payload) {
        assertThat(Contract.errors(schema, JSON.valueToTree(payload))).isNotEmpty();
    }

    private record Example(String label, JsonNode schema, JsonNode value) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static void collect(String label, JsonNode response, List<Example> into) {
        JsonNode content = response.get("content");
        if (content == null) {
            return;
        }
        for (Map.Entry<String, JsonNode> media : content.properties()) {
            JsonNode schema = media.getValue().get("schema");
            if (media.getValue().has("example")) {
                into.add(new Example(label, schema, media.getValue().get("example")));
            }
            if (media.getValue().has("examples")) {
                for (Map.Entry<String, JsonNode> example : media.getValue().get("examples").properties()) {
                    into.add(new Example(label + "/" + example.getKey(), schema, example.getValue().get("value")));
                }
            }
        }
    }

    /** Every example in responses, whether inline or under components. */
    static List<Example> examples() {
        List<Example> found = new ArrayList<>();
        for (Map.Entry<String, JsonNode> response : Contract.SPEC.get("components").get("responses").properties()) {
            collect(response.getKey(), response.getValue(), found);
        }
        for (Map.Entry<String, JsonNode> path : Contract.SPEC.get("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                for (Map.Entry<String, JsonNode> response : operation.getValue().get("responses").properties()) {
                    if (!response.getValue().has("$ref")) {
                        collect(operation.getKey() + " " + path.getKey() + " " + response.getKey(), response.getValue(), found);
                    }
                }
            }
        }
        return found;
    }

    @Test
    void examplesWereFound() {
        assertThat(examples().size()).isGreaterThanOrEqualTo(10);
    }

    @ParameterizedTest
    @MethodSource("examples")
    void everyExampleMatchesItsSchema(Example example) {
        assertThat(Contract.errorsAgainst(example.schema(), example.value())).isEmpty();
    }
}
