package com.example.mocks.idp;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.testing.Http;
import com.example.testing.Reply;
import com.example.testing.TestStack;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The exchange enforces that an agent acts for a user and never exceeds that user's access. */
class IdpTest {
    private final Http client = TestStack.IDP_HTTP;

    private static String userToken(String persona, String audience, int ttl) {
        return TestStack.token(persona, audience, ttl);
    }

    private static String userToken(String persona) {
        return userToken(persona, IdpController.MCP_AUDIENCE, 3600);
    }

    private Reply exchange(String subject, String... overrides) {
        Map<String, String> form = new HashMap<>(Map.of(
                "grant_type", IdpController.TOKEN_EXCHANGE,
                "client_id", "support-copilot",
                "client_secret", "dev-only-agent-secret",
                "subject_token", subject,
                "subject_token_type", IdpController.ACCESS_TOKEN_TYPE,
                "audience", IdpController.API_AUDIENCE));
        for (int i = 0; i < overrides.length; i += 2) {
            form.put(overrides[i], overrides[i + 1]);
        }
        return client.postForm("/token", form);
    }

    private static JWTClaimsSet claims(Reply response) throws Exception {
        return SignedJWT.parse(response.json().get("access_token").asString()).getJWTClaimsSet();
    }

    private static String error(Reply response) {
        return response.json().get("error").asString();
    }

    @Test
    void exchangedTokenKeepsTheUserAndNamesTheAgent() throws Exception {
        JWTClaimsSet decoded = claims(exchange(userToken("support")));
        assertThat(decoded.getSubject()).isEqualTo("jdoe");
        assertThat(decoded.getJSONObjectClaim("act")).isEqualTo(Map.of("sub", "support-copilot"));
        assertThat(decoded.getAudience()).containsExactly(IdpController.API_AUDIENCE);
        long lifetime = (decoded.getExpirationTime().getTime() - decoded.getIssueTime().getTime()) / 1000;
        assertThat(lifetime).isEqualTo(IdpController.EXCHANGED_TOKEN_SECONDS);
    }

    @Test
    void agentCannotGainAScopeItsUserLacks() throws Exception {
        Reply response = exchange(userToken("support"), "scope", "customers:financials:read cases:read");
        assertThat(claims(response).getStringClaim("scope")).isEqualTo("cases:read");
    }

    @Test
    void requestForOnlyForbiddenScopesIsRefused() {
        Reply response = exchange(userToken("support"), "scope", "customers:financials:read");
        assertThat(response.status()).isEqualTo(400);
        assertThat(error(response)).isEqualTo("invalid_scope");
    }

    @Test
    void agentCeilingLimitsAUserWithBroaderAccess() {
        Reply response = exchange(userToken("platform-admin"));
        assertThat(response.status()).isEqualTo(400);
        assertThat(error(response)).isEqualTo("invalid_scope");
    }

    @Test
    void accountRestrictionSurvivesTheExchange() throws Exception {
        assertThat(claims(exchange(userToken("customer-success"))).getStringListClaim("accounts"))
                .isEqualTo(List.of("ACC-012", "ACC-019"));
    }

    @Test
    void tokenIssuedForAnotherAudienceCannotBeExchanged() {
        Reply response = exchange(userToken("support", IdpController.API_AUDIENCE, 3600));
        assertThat(response.status()).isEqualTo(400);
        assertThat(error(response)).isEqualTo("invalid_grant");
    }

    @Test
    void expiredSubjectTokenCannotBeExchanged() {
        assertThat(error(exchange(userToken("support", IdpController.MCP_AUDIENCE, -10)))).isEqualTo("invalid_grant");
    }

    @Test
    void forgedSubjectTokenCannotBeExchanged() throws Exception {
        SignedJWT forged = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .subject("ceo").audience(IdpController.MCP_AUDIENCE).issuer("http://idp.test")
                .claim("scope", "cases:escalate").expirationTime(new Date(System.currentTimeMillis() + 60_000)).build());
        forged.sign(new MACSigner("attacker-key-that-is-long-enough-for-hmac-sha256"));
        assertThat(error(exchange(forged.serialize()))).isEqualTo("invalid_grant");
    }

    @Test
    void badClientSecretIsRefused() {
        assertThat(exchange(userToken("support"), "client_secret", "wrong").status()).isEqualTo(401);
    }

    @Test
    void serviceClientCannotUseTokenExchange() {
        Reply response = exchange(userToken("support"), "client_id", "ipaas-flow", "client_secret", "dev-only-ipaas-secret");
        assertThat(error(response)).isEqualTo("unauthorized_client");
    }

    @Test
    void serviceClientGetsItsOwnIdentityAndFixedScope() throws Exception {
        JWTClaimsSet decoded = claims(client.postForm("/token", Map.of(
                "grant_type", "client_credentials",
                "client_id", "ipaas-flow",
                "client_secret", "dev-only-ipaas-secret")));
        assertThat(decoded.getSubject()).isEqualTo("ipaas-flow");
        assertThat(decoded.getClaim("act")).isNull();
    }

    @Test
    void publishedKeyVerifiesIssuedTokens() throws Exception {
        RSAKey key = (RSAKey) JWKSet.parse(client.get("/jwks.json").text()).getKeys().getFirst();
        SignedJWT token = SignedJWT.parse(userToken("support", IdpController.API_AUDIENCE, 3600));
        assertThat(token.verify(new RSASSAVerifier(key.toRSAPublicKey()))).isTrue();
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo("jdoe");
    }
}
