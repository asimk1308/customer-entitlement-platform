package com.example.mocks.idp;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signs RS256 JWTs, publishes JWKS, and does RFC 8693 token exchange.
 *
 * <p>The exchange is where "an agent acts for a user, never as a super-user" is enforced. It only
 * ever narrows access: the new token gets the scopes in user ∩ agent ceiling ∩ requested.
 */
@RestController
public class IdpController {
    public static final String API_AUDIENCE = "platform-api";
    public static final String MCP_AUDIENCE = "copilot-mcp";
    public static final String APPROVAL_AUDIENCE = "copilot-approval";
    public static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    public static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
    public static final int EXCHANGED_TOKEN_SECONDS = 60;

    public record Persona(String subject, String scope, List<String> accounts) {}

    /** Mutable so that tests can add a persona for one scenario. */
    public final Map<String, Persona> personas = new ConcurrentHashMap<>(Map.of(
            "support", new Persona("jdoe", "customers:read cases:read cases:comment cases:escalate", null),
            // A second support user, to show that one user can't use another's approval.
            "other-support", new Persona("other", "customers:read cases:read cases:comment cases:escalate", null),
            "finance", new Persona("fkhan", "customers:read customers:financials:read cases:read", null),
            "customer-success", new Persona("cmorgan", "customers:read cases:read cases:comment",
                    List.of("ACC-012", "ACC-019")),
            "platform-admin", new Persona("padmin", "webhooks:manage", null),
            // Every scope, so that contract tests can reach every operation.
            "contract-test", new Persona("contract-test", "customers:read customers:financials:read cases:read "
                    + "cases:comment cases:escalate webhooks:manage", null)));

    private record Client(String secret, String kind, String scope) {}

    // Dev-only values: this mock never holds real credentials.
    private static final Map<String, Client> CLIENTS = Map.of(
            "support-copilot", new Client("dev-only-agent-secret", "agent",
                    "customers:read customers:financials:read cases:read cases:comment cases:escalate"),
            "ipaas-flow", new Client("dev-only-ipaas-secret", "service", "customers:read cases:read"));

    private final String issuer;
    private final RSAKey key;

    public IdpController(@Value("${idp.issuer:http://localhost:9100}") String issuer) throws JOSEException {
        this.issuer = issuer;
        this.key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    }

    @GetMapping(value = "/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }

    public record DevTokenRequest(String persona, String audience, Integer ttlSeconds) {}

    /** Replaces the interactive login a real provider would run. Dev only. */
    @PostMapping("/dev/token")
    ResponseEntity<Map<String, Object>> devToken(@RequestBody DevTokenRequest request) throws JOSEException {
        Persona persona = personas.get(request.persona());
        if (persona == null) {
            return error("invalid_request", "unknown persona", 400);
        }
        String audience = request.audience() == null ? API_AUDIENCE : request.audience();
        if (!Set.of(API_AUDIENCE, MCP_AUDIENCE, APPROVAL_AUDIENCE).contains(audience)) {
            return error("invalid_target", "unknown audience", 400);
        }
        int ttl = request.ttlSeconds() == null ? 3600 : request.ttlSeconds();
        JWTClaimsSet.Builder claims = claims(persona.subject(), audience, persona.scope());
        if (persona.accounts() != null) {
            claims.claim("accounts", persona.accounts());
        }
        return ResponseEntity.ok(tokenResponse(sign(claims, ttl), ttl, persona.scope()));
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Map<String, Object>> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam(value = "subject_token", required = false) String subjectToken,
            @RequestParam(value = "subject_token_type", required = false) String subjectTokenType,
            @RequestParam(required = false) String audience,
            @RequestParam(required = false) String scope) throws JOSEException {
        Client client = CLIENTS.get(clientId);
        if (client == null || !client.secret().equals(clientSecret)) {
            return error("invalid_client", "unknown client or bad secret", 401);
        }
        if (grantType.equals("client_credentials") && client.kind().equals("service")) {
            return ResponseEntity.ok(tokenResponse(sign(claims(clientId, API_AUDIENCE, client.scope()), 3600),
                    3600, client.scope()));
        }
        if (!grantType.equals(TOKEN_EXCHANGE) || !client.kind().equals("agent")) {
            return error("unauthorized_client", "grant not allowed for this client", 400);
        }
        if (subjectToken == null || !ACCESS_TOKEN_TYPE.equals(subjectTokenType)) {
            return error("invalid_request", "an access token subject_token is required", 400);
        }
        if (!API_AUDIENCE.equals(audience)) {
            return error("invalid_target", "can only exchange for " + API_AUDIENCE, 400);
        }
        JWTClaimsSet subject;
        try {
            subject = verifySubject(subjectToken);
        } catch (Exception rejected) {
            return error("invalid_grant", "subject token rejected: " + rejected.getMessage(), 400);
        }

        Set<String> granted = new TreeSet<>(words(subject.getClaim("scope")));
        granted.retainAll(words(client.scope()));
        if (scope != null) {
            granted.retainAll(words(scope));
        }
        if (granted.isEmpty()) {
            return error("invalid_scope", "no requested scope is allowed for this user", 400);
        }
        String grantedScope = String.join(" ", granted);
        JWTClaimsSet.Builder claims = claims(subject.getSubject(), API_AUDIENCE, grantedScope)
                .claim("act", Map.of("sub", clientId));
        if (subject.getClaim("accounts") != null) {
            claims.claim("accounts", subject.getClaim("accounts"));
        }
        return ResponseEntity.ok(tokenResponse(sign(claims, EXCHANGED_TOKEN_SECONDS),
                EXCHANGED_TOKEN_SECONDS, grantedScope));
    }

    /**
     * Only a token issued for the MCP server can be exchanged by it. This stops the server being used
     * to launder a token that was meant for some other service.
     */
    private JWTClaimsSet verifySubject(String token) throws ParseException, JOSEException {
        SignedJWT jwt = SignedJWT.parse(token);
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
                || !jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))) {
            throw new IllegalArgumentException("bad signature");
        }
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        if (!issuer.equals(claims.getIssuer()) || !claims.getAudience().contains(MCP_AUDIENCE)) {
            throw new IllegalArgumentException("wrong issuer or audience");
        }
        if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
            throw new IllegalArgumentException("expired");
        }
        return claims;
    }

    private JWTClaimsSet.Builder claims(String subject, String audience, String scope) {
        return new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience).claim("scope", scope);
    }

    private String sign(JWTClaimsSet.Builder claims, int ttlSeconds) throws JOSEException {
        // A negative lifetime asks for an already expired token, which was still issued before it expired.
        Instant now = Instant.now();
        Instant issued = ttlSeconds > 0 ? now : now.plusSeconds(ttlSeconds - 60L);
        JWTClaimsSet complete = claims
                .issueTime(Date.from(issued))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), complete);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static Set<String> words(Object value) {
        return value == null ? Set.of() : new TreeSet<>(Arrays.asList(value.toString().split(" ")));
    }

    private static Map<String, Object> tokenResponse(String token, int ttl, String scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token);
        body.put("token_type", "Bearer");
        body.put("expires_in", ttl);
        body.put("scope", scope);
        body.put("issued_token_type", ACCESS_TOKEN_TYPE);
        return body;
    }

    private static ResponseEntity<Map<String, Object>> error(String code, String description, int status) {
        return ResponseEntity.status(status).body(Map.of("error", code, "error_description", description));
    }
}
