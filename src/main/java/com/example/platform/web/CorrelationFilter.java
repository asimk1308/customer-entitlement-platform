package com.example.platform.web;

import com.example.platform.security.Principal;
import com.example.platform.web.ApiProblem.Code;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outermost, so that requests refused by the security filters are logged and correlated too. Callers
 * log named fields, never bodies, so customer text and ARR can't reach the logs by accident.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    public static final String CORRELATION_ID = "correlationId";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationFilter.class);

    private final JsonMapper json;

    public CorrelationFilter(JsonMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(CORRELATION_HEADER);
        String correlationId = supplied != null && VALID_ID.matcher(supplied).matches()
                ? supplied : UUID.randomUUID().toString().replace("-", "");
        MDC.put(CORRELATION_ID, correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } catch (AuthenticationServiceException unavailable) {
            // Nimbus could not fetch signing keys. Not the caller's fault, and retrying can work.
            LOG.atError().addKeyValue("error", unavailable.getMessage()).log("jwks_fetch_failed");
            write(response, request, ApiProblem.withHeaders(Code.UPSTREAM_UNAVAILABLE,
                    "Tokens can't be verified right now.", Map.of("Retry-After", "5")));
        } catch (Exception error) { // Last line of defence: log and return a problem.
            LOG.atError().setCause(error).log("unhandled_error");
            write(response, request, ApiProblem.of(Code.INTERNAL_ERROR));
        } finally {
            Object caller = request.getAttribute(Principal.ATTRIBUTE);
            Principal principal = caller instanceof Principal known ? known : null;
            LOG.atInfo()
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("durationMs", Math.round((System.nanoTime() - started) / 100_000.0) / 10.0)
                    .addKeyValue("subject", principal == null ? null : principal.subject())
                    .addKeyValue("agent", principal == null ? null : principal.agent())
                    .log("request_completed");
            MDC.remove(CORRELATION_ID);
        }
    }

    private void write(HttpServletResponse response, HttpServletRequest request, ApiProblem problem) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(problem.code.status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        problem.headers.forEach(response::setHeader);
        response.getOutputStream().write(json.writeValueAsBytes(ProblemBody.of(problem, request.getRequestURI())));
    }

    /** Used by the security filter chain's entry point, which runs outside MVC. */
    public void writeProblem(HttpServletRequest request, HttpServletResponse response, ApiProblem problem)
            throws IOException {
        write(response, request, problem);
    }
}
