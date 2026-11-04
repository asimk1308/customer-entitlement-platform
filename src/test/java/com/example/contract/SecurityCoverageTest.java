package com.example.contract;

import static com.example.testing.TestStack.auth;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.mocks.idp.IdpController;
import com.example.testing.Api;
import com.example.testing.Contract;
import com.example.testing.Reply;
import com.example.testing.TestStack;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Driven by openapi.yaml, so that every secured operation is covered, including future ones.
 *
 * <p>This exists because a mutation test showed the fuzz run doesn't notice an auth bypass: with
 * anonymous callers allowed everywhere, Schemathesis still passed.
 */
class SecurityCoverageTest {
    @TempDir
    static Path tmp;

    private static Api api;

    @BeforeAll
    static void start() {
        api = TestStack.startApi(tmp.resolve("platform.db"), List.of());
    }

    @AfterAll
    static void stop() {
        api.close();
    }

    @BeforeEach
    void reset() {
        TestStack.resetMocks();
    }

    record Operation(String method, String path, String scope) {
        String url() {
            return path.replace("{caseId}", "CS-10231").replace("{customerId}", "ACC-007");
        }
    }

    static List<Operation> operations() {
        List<Operation> found = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : Contract.SPEC.get("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                JsonNode security = operation.getValue().get("security");
                if (!security.isEmpty()) {
                    found.add(new Operation(operation.getKey().toUpperCase(), path.getKey(),
                            security.get(0).get("oauth2").get(0).asString()));
                }
            }
        }
        return found;
    }

    private static Set<String> allScopes() {
        JsonNode scopes = Contract.SPEC.get("components").get("securitySchemes").get("oauth2")
                .get("flows").get("clientCredentials").get("scopes");
        Set<String> names = new TreeSet<>();
        scopes.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    @Test
    void everyBusinessOperationIsSecured() {
        List<String> open = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : Contract.SPEC.get("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (operation.getValue().get("security").isEmpty()) {
                    open.add(path.getKey());
                }
            }
        }
        assertThat(open).containsExactly("/healthz");
        assertThat(operations().size()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void everyDeclaredScopeIsRequiredByAnOperationOrAField() {
        Set<String> used = new HashSet<>(Set.of("customers:financials:read"));
        operations().forEach(operation -> used.add(operation.scope()));
        assertThat(used).isEqualTo(allScopes());
    }

    @ParameterizedTest
    @MethodSource("operations")
    void noTokenIs401(Operation operation) {
        assertThat(api.http().request(operation.method(), operation.url(), Map.of()).status()).isEqualTo(401);
    }

    @ParameterizedTest
    @MethodSource("operations")
    void tokenForAnotherAudienceIs401(Operation operation) {
        Map<String, String> headers = Map.of("Authorization",
                "Bearer " + TestStack.token("support", IdpController.MCP_AUDIENCE, 3600));
        assertThat(api.http().request(operation.method(), operation.url(), headers).status()).isEqualTo(401);
    }

    private static Reply callAs(String persona, String scope, Operation operation) {
        TestStack.idp().personas.put(persona, new IdpController.Persona(persona, scope, null));
        try {
            return api.http().request(operation.method(), operation.url(), auth(persona));
        } finally {
            TestStack.idp().personas.remove(persona);
        }
    }

    /** Also proves that the scope the contract declares is the one the code enforces. */
    @ParameterizedTest
    @MethodSource("operations")
    void everyScopeExceptTheRequiredOneIs403(Operation operation) {
        Set<String> others = allScopes();
        others.remove(operation.scope());
        Reply response = callAs("lacks-scope", String.join(" ", others), operation);
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().get("detail").asString()).contains(operation.scope());
    }

    @ParameterizedTest
    @MethodSource("operations")
    void onlyTheRequiredScopeGetsPastAuthorization(Operation operation) {
        assertThat(callAs("just-scope", operation.scope(), operation).status()).isNotIn(401, 403);
    }
}
