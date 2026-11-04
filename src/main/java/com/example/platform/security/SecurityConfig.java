package com.example.platform.security;

import com.example.platform.Settings;
import com.example.platform.web.ApiProblem;
import com.example.platform.web.ApiProblem.Code;
import com.example.platform.web.CorrelationFilter;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import jakarta.servlet.DispatcherType;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

@Configuration
public class SecurityConfig {
    private static final String BEARER_CHALLENGE = "Bearer error=\"invalid_token\"";
    private static final int JWKS_TIMEOUT_MILLIS = 2_000;
    private static final int JWKS_MAX_BYTES = 64 * 1024;
    private static final long JWKS_COOL_DOWN_MILLIS = 30_000;

    /**
     * RS256 is fixed here, never read from the token, which stops algorithm-confusion attacks. The key
     * source is built by hand because Spring's default turns off Nimbus's rate limit, which let every
     * token with a made-up key ID cause a request to the provider.
     */
    @Bean
    JwtDecoder jwtDecoder(Settings settings) throws MalformedURLException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keySource(settings))
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(settings.issuer()),
                audience(settings.audience()),
                requiredClaims(),
                wellFormedActor())));
        return decoder;
    }

    /**
     * During the cool-down, an unknown key ID is treated as an unknown key, so a forged token gets a
     * 401. The cost: a token signed by a newly rotated key can get a 401 for up to 30 seconds. Until
     * keys have loaded once, the limit means "provider unavailable", which keeps its 503.
     */
    private static JWKSource<SecurityContext> keySource(Settings settings) throws MalformedURLException {
        JWKSource<SecurityContext> limited = JWKSourceBuilder
                .<SecurityContext>create(URI.create(settings.idpUrl() + "/jwks.json").toURL(),
                        new DefaultResourceRetriever(JWKS_TIMEOUT_MILLIS, JWKS_TIMEOUT_MILLIS, JWKS_MAX_BYTES))
                .refreshAheadCache(false)
                .rateLimited(JWKS_COOL_DOWN_MILLIS)
                .build();
        AtomicBoolean loaded = new AtomicBoolean();
        return (selector, context) -> {
            try {
                List<JWK> keys = limited.get(selector, context);
                loaded.set(true);
                return keys;
            } catch (RateLimitReachedException limit) {
                if (loaded.get()) {
                    return List.of();
                }
                throw limit;
            }
        };
    }

    private static OAuth2TokenValidator<Jwt> audience(String expected) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(expected)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
    }

    /** The timestamp validator alone would accept a token that never expires. */
    private static OAuth2TokenValidator<Jwt> requiredClaims() {
        return jwt -> jwt.getExpiresAt() != null && jwt.getSubject() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing claim", null));
    }

    /** An `act` claim with no usable subject must not pass as a person, who has fewer limits. */
    private static OAuth2TokenValidator<Jwt> wellFormedActor() {
        return jwt -> {
            Object act = jwt.getClaim("act");
            boolean valid = act == null
                    || (act instanceof Map<?, ?> actor && actor.get("sub") instanceof String agent && !agent.isBlank());
            return valid
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Malformed act claim", null));
        };
    }

    private static boolean isExpiry(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException invalid && invalid.getErrors().stream()
                    .anyMatch(error -> String.valueOf(error.getDescription()).contains("expired"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Found by fuzzing: the firewall answers methods it doesn't know, such as QUERY, with a 400. Spring
     * MVC should decide, because it can return the correct 405 and Allow header. The firewall's other
     * checks stay on.
     */
    @Bean
    HttpFirewall firewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setUnsafeAllowAnyHttpMethod(true);
        return firewall;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, CorrelationFilter problems) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, failure) -> {
            String detail;
            if (request.getHeader("Authorization") == null) {
                detail = "A bearer token is required.";
            } else if (isExpiry(failure)) {
                detail = "The access token has expired.";
            } else {
                detail = "The access token is invalid.";
            }
            problems.writeProblem(request, response, ApiProblem.withHeaders(Code.UNAUTHORIZED, detail,
                    Map.of("WWW-Authenticate", BEARER_CHALLENGE)));
        };
        return http
                // Bearer tokens aren't sent automatically by browsers, so CSRF doesn't apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/healthz", "/openapi.yaml").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .authenticationEntryPoint(unauthorized)
                        .jwt(jwt -> {}))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized))
                .build();
    }
}
