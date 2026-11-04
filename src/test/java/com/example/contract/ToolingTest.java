package com.example.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.testing.Contract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proves the contract checks can fail. A rule whose selector matches nothing also passes, so a clean
 * lint run on its own says little.
 */
class ToolingTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FIRED = new HashSet<>();
    // Exactly this: oasdiff uses other non-zero codes when it can't read a file, which proves nothing.
    private static final int BREAKING = 1;

    @TempDir
    static Path spectralDir;

    private static ObjectNode spec() {
        return (ObjectNode) Contract.SPEC.deepCopy();
    }

    private static ObjectNode at(ObjectNode root, String... path) {
        ObjectNode node = root;
        for (String step : path) {
            node = (ObjectNode) node.get(step);
        }
        return node;
    }

    private static ObjectNode brokenSpec() {
        ObjectNode spec = spec();
        // Unversioned, badly cased, no security, and no 401, 403 or 429.
        ObjectNode bad = JSON.createObjectNode();
        ObjectNode get = bad.putObject("get");
        get.put("operationId", "bad").put("description", "x");
        get.putArray("tags").add("Cases");
        get.putObject("responses").putObject("200").put("description", "x");
        at(spec, "paths").set("/cases_Bad", bad);
        at(spec, "components", "schemas", "Comment", "properties").putObject("arr_usd").put("type", "number");
        ObjectNode wrongError = at(spec, "paths", "/v1/cases", "get", "responses").putObject("400");
        wrongError.put("description", "x");
        wrongError.putObject("content").putObject("application/json").putObject("schema").put("type", "object");
        return spec;
    }

    private static Process run(Path directory, List<String> command) throws IOException {
        return new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(false).start();
    }

    @BeforeAll
    static void lintABrokenSpec() throws Exception {
        Files.copy(Contract.ROOT.resolve(".spectral.yaml"), spectralDir.resolve(".spectral.yaml"));
        Files.writeString(spectralDir.resolve("spec.json"), JSON.writeValueAsString(brokenSpec()));
        Process lint = run(spectralDir, List.of("npx", "--yes", "@stoplight/spectral-cli@6.17.0", "lint", "spec.json", "--format", "json"));
        String output = new String(lint.getInputStream().readAllBytes());
        lint.waitFor();
        JSON.readTree(output).forEach(finding -> FIRED.add(finding.get("code").asString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "paths-are-versioned",
        "paths-are-kebab-case",
        "properties-are-camel-case",
        "errors-are-problem-json",
        "operations-declare-security",
        "operations-document-rate-limit",
        "operations-document-auth-failures",
    })
    void spectralRuleFiresOnAViolation(String rule) {
        assertThat(FIRED).contains(rule);
    }

    private int oasdiffBreaking(Path directory, JsonNode revisionSpec) throws Exception {
        Path revision = directory.resolve("revision.json");
        Files.writeString(revision, JSON.writeValueAsString(revisionSpec));
        Process check;
        try {
            // Without --flatten-allof, changes inside allOf are only warnings, and --fail-on ignores them.
            check = run(directory, List.of("oasdiff", "breaking", Contract.ROOT.resolve("contract/baseline-v1.yaml").toString(),
                    revision.toString(), "--fail-on", "ERR", "--flatten-allof"));
        } catch (IOException notInstalled) {
            assumeTrue(false, "oasdiff is not installed");
            throw notInstalled;
        }
        check.getInputStream().readAllBytes();
        return check.waitFor();
    }

    @Test
    void breakingCheckPassesOnAnAdditiveChange(@TempDir Path directory) throws Exception {
        ObjectNode spec = spec();
        ArrayNode parameters = (ArrayNode) at(spec, "paths", "/v1/cases", "get").get("parameters");
        ObjectNode added = parameters.addObject();
        added.put("name", "newFilter").put("in", "query");
        added.putObject("schema").put("type", "string");
        assertThat(oasdiffBreaking(directory, spec)).isZero();
    }

    @Test
    void breakingCheckFailsWhenAnEndpointIsRemoved(@TempDir Path directory) throws Exception {
        ObjectNode spec = spec();
        at(spec, "paths").remove("/v1/cases/{caseId}/comments");
        assertThat(oasdiffBreaking(directory, spec)).isEqualTo(BREAKING);
    }

    @Test
    void breakingCheckFailsWhenARequiredFieldIsDropped(@TempDir Path directory) throws Exception {
        ObjectNode spec = spec();
        ObjectNode fields = at(spec, "components", "schemas", "CaseFields");
        at(fields, "properties").remove("etag");
        ArrayNode required = (ArrayNode) fields.get("required");
        for (int i = 0; i < required.size(); i++) {
            if (required.get(i).asString().equals("etag")) {
                required.remove(i);
                break;
            }
        }
        assertThat(oasdiffBreaking(directory, spec)).isEqualTo(BREAKING);
    }
}
