package com.example.platform.security;

import com.example.platform.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs before Spring validates a handler's arguments, so a caller without the right scope learns
 * nothing from validation errors.
 */
@Component
public class ScopeInterceptor implements HandlerInterceptor {
    private final RateLimiter limiter;

    public ScopeInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return true; // A public route, or none at all. The security chain guards /v1.
        }
        Principal principal = fromClaims(jwt);
        request.setAttribute(Principal.ATTRIBUTE, principal);
        limiter.check(response, principal);
        if (handler instanceof HandlerMethod method) {
            RequiresScope required = method.getMethodAnnotation(RequiresScope.class);
            if (required != null && !principal.has(required.value())) {
                throw ApiProblem.of(ApiProblem.Code.FORBIDDEN, "This operation requires the " + required.value() + " scope.");
            }
        }
        return true;
    }

    static Principal fromClaims(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        Set<String> scopes = scope == null || scope.isBlank() ? Set.of() : Set.copyOf(List.of(scope.trim().split("\\s+")));
        Map<String, Object> act = jwt.getClaimAsMap("act");
        String agent = act == null ? null : (String) act.get("sub");
        List<String> accounts = jwt.getClaimAsStringList("accounts");
        return new Principal(jwt.getSubject(), scopes, agent, accounts == null ? null : Set.copyOf(accounts));
    }
}
