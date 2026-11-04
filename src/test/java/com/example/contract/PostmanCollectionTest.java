package com.example.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.testing.Contract;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Keeps the committed collection honest: covering the whole API, with assertions on every request. */
class PostmanCollectionTest {
    private static final JsonNode COLLECTION = load();

    private static JsonNode load() {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(Contract.ROOT.resolve("postman/collection.json")));
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static List<JsonNode> requests() {
        List<JsonNode> items = new ArrayList<>();
        COLLECTION.get("item").forEach(folder -> folder.get("item").forEach(items::add));
        return items;
    }

    @Test
    void hasFunctionalAndRegressionFolders() {
        List<String> names = new ArrayList<>();
        COLLECTION.get("item").forEach(folder -> names.add(folder.get("name").asString()));
        assertThat(names).contains("Functional", "Regression");
    }

    @Test
    void everyContractOperationIsCalled() {
        for (Map.Entry<String, JsonNode> path : Contract.SPEC.get("paths").properties()) {
            if (path.getKey().equals("/healthz")) {
                continue;
            }
            Pattern url = Pattern.compile("^\\{\\{baseUrl}}" + path.getKey().replaceAll("\\{[^}]+}", "[^/]+") + "$");
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                String method = operation.getKey().toUpperCase();
                boolean called = requests().stream().map(item -> item.get("request")).anyMatch(request ->
                        request.get("method").asString().equals(method)
                                && url.matcher(request.get("url").asString().split("\\?")[0]).matches());
                assertThat(called).as(method + " " + path.getKey()).isTrue();
            }
        }
    }

    @Test
    void everyRequestMakesAssertions() {
        for (JsonNode item : requests()) {
            boolean asserts = false;
            for (JsonNode event : item.get("event")) {
                if (event.get("listen").asString().equals("test")) {
                    for (JsonNode line : event.get("script").get("exec")) {
                        asserts |= line.asString().contains("pm.test");
                    }
                }
            }
            assertThat(asserts).as(item.get("name").asString()).isTrue();
        }
    }
}
