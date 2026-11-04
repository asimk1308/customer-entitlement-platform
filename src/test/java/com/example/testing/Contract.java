package com.example.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates real payloads against the schemas in openapi.yaml. */
public final class Contract {
    public static final Path ROOT = Path.of("").toAbsolutePath();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final JsonNode SPEC;
    private static final SchemaRegistry REGISTRY;

    static {
        try {
            // SnakeYAML directly, because Jackson's YAML parser leaves the spec's anchors unresolved.
            Object spec = new Yaml().load(Files.readString(ROOT.resolve("openapi.yaml")));
            SPEC = JSON.valueToTree(spec);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder ->
                builder.schemas(Map.of("urn:spec", JSON.writeValueAsString(SPEC))));
    }

    private Contract() {}

    public static List<String> errors(String schemaName, JsonNode payload) {
        return messages(REGISTRY.getSchema(SchemaLocation.of("urn:spec#/components/schemas/" + schemaName)), payload);
    }

    /** For an inline schema, whose references point into the spec. */
    public static List<String> errorsAgainst(JsonNode schema, JsonNode payload) {
        String rewritten = JSON.writeValueAsString(schema).replace("\"$ref\":\"#/", "\"$ref\":\"urn:spec#/");
        return messages(REGISTRY.getSchema(rewritten), payload);
    }

    private static List<String> messages(Schema schema, JsonNode payload) {
        return schema.validate(payload).stream().map(Error::toString).toList();
    }

    public static void assertSchema(String schemaName, JsonNode payload) {
        assertThat(errors(schemaName, payload)).as(schemaName).isEmpty();
    }
}
