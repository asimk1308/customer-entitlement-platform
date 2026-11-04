package com.example.platform;

import static com.example.testing.Contract.assertSchema;
import static com.example.testing.TestStack.auth;
import static com.example.testing.TestStack.token;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.mocks.idp.IdpController;
import com.example.testing.Api;
import com.example.testing.Contract;
import com.example.testing.Reply;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Each test is an attempt to get in with a token that must not work. */
class AuthTest extends ApiTest {
    private static final String URL = "/v1/cases";

    private static void assertUnauthorized(Reply response) {
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.contentType()).isEqualTo("application/problem+json");
        assertThat(response.header("WWW-Authenticate")).contains("Bearer");
        assertSchema("Problem", response.json());
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    @Test
    void noToken() {
        assertUnauthorized(api().http().get(URL));
    }

    @Test
    void notAToken() {
        assertUnauthorized(api().http().get(URL, bearer("nonsense")));
    }

    @Test
    void expiredToken() {
        Reply response = api().http().get(URL, bearer(token("support", IdpController.API_AUDIENCE, -10)));
        assertUnauthorized(response);
        assertThat(response.json().get("detail").asString()).contains("expired");
    }

    /** This is what stops token passthrough: the MCP server can't forward its user's token. */
    @Test
    void tokenIssuedForTheMcpServerIsRejected() {
        assertUnauthorized(api().http().get(URL, bearer(token("support", IdpController.MCP_AUDIENCE, 3600))));
    }

    @Test
    void unsignedTokenIsRejected() throws Exception {
        SignedJWT real = SignedJWT.parse(token("support"));
        String forged = encode("{\"alg\":\"none\",\"kid\":\"" + real.getHeader().getKeyID() + "\"}") + "."
                + encode(real.getJWTClaimsSet().toString()) + ".";
        assertUnauthorized(api().http().get(URL, bearer(forged)));
    }

    @Test
    void tokenWithTamperedClaimsIsRejected() {
        String[] parts = token("support").split("\\.");
        String claims = decode(parts[1]).replace("\"scope\":\"", "\"scope\":\"customers:financials:read ");
        String forged = parts[0] + "." + encode(claims) + "." + parts[2];
        assertUnauthorized(api().http().get(URL, bearer(forged)));
    }

    @Test
    void symmetricallySignedTokenUsingAKnownKeyIdIsRejected() throws Exception {
        String kid = SignedJWT.parse(token("support")).getHeader().getKeyID();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(kid).build(),
                new JWTClaimsSet.Builder().subject("ceo").audience("platform-api").issuer("http://idp.test")
                        .expirationTime(new Date(System.currentTimeMillis() + 60_000)).build());
        forged.sign(new MACSigner("attacker-key-that-is-long-enough-for-hmac-sha256"));
        assertUnauthorized(api().http().get(URL, bearer(forged.serialize())));
    }

    @Test
    void tokenSignedByAnUnknownKeyIsRejected() throws Exception {
        RSAKey attacker = new RSAKeyGenerator(2048).keyID("attacker").generate();
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("attacker").type(JOSEObjectType.JWT).build(),
                new JWTClaimsSet.Builder().subject("ceo").audience("platform-api").issuer("http://idp.test")
                        .expirationTime(new Date(System.currentTimeMillis() + 60_000)).build());
        forged.sign(new RSASSASigner(attacker));
        assertUnauthorized(api().http().get(URL, bearer(forged.serialize())));
    }

    /**
     * A provider the test controls, because a forged signature fails before the claims are read. Only
     * a correctly signed token can show that each claim rule works on its own.
     */
    private final class TrustedSigner implements AutoCloseable {
        final RSAKey key = new RSAKeyGenerator(2048).keyID("trusted").generate();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final Api api;

        TrustedSigner() throws Exception {
            byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks.json", exchange -> {
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
                exchange.close();
            });
            server.start();
            api = make("platform.idp-url=http://127.0.0.1:" + server.getAddress().getPort());
        }

        JWTClaimsSet.Builder validClaims() {
            return new JWTClaimsSet.Builder().subject("jdoe").audience("platform-api").issuer("http://idp.test")
                    .claim("scope", "cases:read").expirationTime(new Date(System.currentTimeMillis() + 60_000));
        }

        Reply call(JWTClaimsSet.Builder claims) throws Exception {
            SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("trusted").build(), claims.build());
            token.sign(new RSASSASigner(key));
            return api.http().get(URL, bearer(token.serialize()));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void correctlySignedTokenWithWrongClaimsIsRejected() throws Exception {
        try (TrustedSigner signer = new TrustedSigner()) {
            assertThat(signer.call(signer.validClaims()).status()).isEqualTo(200); // Otherwise the rest prove nothing.
            assertUnauthorized(signer.call(signer.validClaims().issuer("http://evil")));
            assertUnauthorized(signer.call(signer.validClaims().audience("copilot-mcp")));
            assertUnauthorized(signer.call(signer.validClaims().expirationTime(null)));
            assertUnauthorized(signer.call(signer.validClaims().subject(null)));
            assertUnauthorized(signer.call(signer.validClaims().expirationTime(new Date(System.currentTimeMillis() - 1000))
                    .issueTime(new Date(System.currentTimeMillis() - 60_000))));
        }
    }

    /** An outage at the provider isn't the caller's fault, so it must not look like a bad token. */
    @Test
    void unreachableKeyServerIsA503RatherThanA401() {
        Api api = make("platform.idp-url=http://localhost:1");
        Reply response = api.http().get(URL, auth());
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.json().get("code").asString()).isEqualTo("upstream_unavailable");
        assertThat(response.header("Retry-After")).isEqualTo("5");
        assertSchema("Problem", response.json());
    }

    @Test
    void missingScopeIs403AndNamesTheScope() {
        Reply response = api().http().get("/v1/customers/ACC-007", auth("platform-admin"));
        assertThat(response.status()).isEqualTo(403);
        assertSchema("Problem", response.json());
        assertThat(response.json().get("detail").asString()).contains("customers:read");
    }

    /** An anonymous caller must not be able to probe the API through validation messages. */
    @Test
    void authenticationHappensBeforeValidation() {
        assertThat(api().http().get("/v1/cases?limit=9999").status()).isEqualTo(401);
    }

    @Test
    void authorizationHappensBeforeValidation() {
        assertThat(api().http().get("/v1/cases?limit=9999", auth("platform-admin")).status()).isEqualTo(403);
    }

    @Test
    void rateLimitIsPerUserAndReportsHeaders() {
        Api api = make("platform.rate-limit-per-minute=2"); // The test clock is frozen, so one window.
        Map<String, String> support = auth("support");
        Reply first = api.http().get("/v1/customers/ACC-007", support);
        assertThat(first.header("RateLimit-Limit")).isEqualTo("2");
        assertThat(first.header("RateLimit-Remaining")).isEqualTo("1");
        api.http().get("/v1/customers/ACC-007", support);
        Reply limited = api.http().get("/v1/customers/ACC-007", support);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isGreaterThanOrEqualTo(1);
        assertSchema("Problem", limited.json());
        assertThat(api.http().get("/v1/customers/ACC-007", auth("finance")).status()).isEqualTo(200);
    }

    @Test
    void specIsServedUnchanged() throws Exception {
        assertThat(api().http().get("/openapi.yaml").text())
                .isEqualTo(Files.readString(Contract.ROOT.resolve("openapi.yaml")));
    }

    @Test
    void healthNeedsNoToken() {
        assertThat(api().http().get("/healthz").status()).isEqualTo(200);
    }

    @Test
    void unknownRouteAndWrongMethodAreProblems() {
        Reply missing = api().http().get("/v1/nothing", auth());
        assertThat(missing.status()).isEqualTo(404);
        assertSchema("Problem", missing.json());
        Reply wrong = api().http().request("DELETE", "/v1/cases", auth());
        assertThat(wrong.status()).isEqualTo(405);
        assertThat(wrong.json().get("code").asString()).isEqualTo("method_not_allowed");
        assertThat(wrong.header("Allow")).contains("GET");
    }

    @Test
    void anUnknownMethodIsA405WithAllow() {
        Reply response = api().http().request("QUERY", "/v1/cases", auth());
        assertThat(response.status()).isEqualTo(405);
        assertThat(response.header("Allow")).contains("GET");
        assertSchema("Problem", response.json());
    }

    @Test
    void anUnknownMethodStillNeedsAToken() {
        assertUnauthorized(api().http().request("QUERY", "/v1/cases", Map.of()));
    }

    @Test
    void aMalformedQueryStringIsAValidationProblemNotA500() {
        Reply response = api().http().get("/v1/cases?=", auth());
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").asString()).isEqualTo("validation_error");
        assertSchema("Problem", response.json());
    }

    /** Found by fuzzing. Nothing here takes multipart, so a malformed one must not reach a parser. */
    @Test
    void aMalformedMultipartContentTypeIsNotAServerError() {
        Reply read = api().http().get("/v1/cases", with(auth(), "Content-Type", "multipart/form-data"));
        assertThat(read.status()).isEqualTo(200);
        Reply write = api().http().postRaw("/v1/cases/CS-10231/comments", "{\"body\":\"x\"}".getBytes(StandardCharsets.UTF_8),
                with(auth(), "Content-Type", "multipart/form-data", "Idempotency-Key", "key-0000000000000009"));
        assertThat(write.status()).isEqualTo(400);
        assertSchema("Problem", write.json());
    }

    /** The firewall runs before Spring MVC, and its refusals must be problems as well. */
    @Test
    void urlTheFirewallRejectsIsAProblem() {
        Reply response = api().http().get("/v1/cases/%2e%2e/x", auth());
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.contentType()).isEqualTo("application/problem+json");
    }
}
