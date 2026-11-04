package com.example.platform.web;

import java.util.List;
import java.util.Map;

/** The RFC 9457 error model from openapi.yaml. Every error leaves through here. */
public class ApiProblem extends RuntimeException {
    /** @param retryable whether the same request may succeed later */
    public enum Code {
        VALIDATION_ERROR(400, "Request validation failed", false),
        INVALID_CURSOR(400, "Invalid cursor", false),
        WEBHOOK_URL_NOT_ALLOWED(400, "Webhook URL not allowed", false),
        UNAUTHORIZED(401, "Authentication required", false),
        FORBIDDEN(403, "Insufficient scope", false),
        NOT_FOUND(404, "Not found", false),
        METHOD_NOT_ALLOWED(405, "Method not allowed", false),
        CASE_ALREADY_ESCALATED(409, "Case already escalated", false),
        IDEMPOTENCY_IN_PROGRESS(409, "Original request still in progress", true),
        STALE_VERSION(412, "The case has changed", false),
        CASE_NOT_ESCALATABLE(422, "Case can't be escalated", false),
        ESCALATION_NOT_ELIGIBLE(422, "Not eligible for escalation", false),
        IDEMPOTENCY_KEY_REUSED(422, "Idempotency key reused with a different request", false),
        PRECONDITION_REQUIRED(428, "If-Match header required", false),
        RATE_LIMITED(429, "Too many requests", true),
        INTERNAL_ERROR(500, "Internal error", true),
        UPSTREAM_ERROR(502, "Upstream system error", true),
        UPSTREAM_UNAVAILABLE(503, "Upstream system unavailable", true),
        UPSTREAM_TIMEOUT(504, "Upstream system timed out", true);

        final int status;
        final String title;
        final boolean retryable;

        Code(int status, String title, boolean retryable) {
            this.status = status;
            this.title = title;
            this.retryable = retryable;
        }

        public String wire() {
            return name().toLowerCase();
        }
    }

    public record FieldError(String field, String message) {}

    public final Code code;
    public final String detail;
    public final String upstream;
    public final List<FieldError> errors;
    public final Map<String, String> headers;

    private ApiProblem(Code code, String detail, String upstream, List<FieldError> errors,
                       Map<String, String> headers) {
        super(code.wire(), null, false, false);
        this.code = code;
        this.detail = detail;
        this.upstream = upstream;
        this.errors = errors;
        this.headers = headers;
    }

    public static ApiProblem of(Code code) {
        return new ApiProblem(code, null, null, null, Map.of());
    }

    public static ApiProblem of(Code code, String detail) {
        return new ApiProblem(code, detail, null, null, Map.of());
    }

    public static ApiProblem withHeaders(Code code, String detail, Map<String, String> headers) {
        return new ApiProblem(code, detail, null, null, headers);
    }

    public static ApiProblem upstream(Code code, String source, String detail, Map<String, String> headers) {
        return new ApiProblem(code, detail, source, null, headers);
    }

    /** Only locations and messages: echoing input could put sensitive values in logs and clients. */
    public static ApiProblem invalid(List<FieldError> errors) {
        int count = errors.size();
        String detail = count + " field" + (count == 1 ? " is" : "s are") + " invalid.";
        return new ApiProblem(Code.VALIDATION_ERROR, detail, null, errors, Map.of());
    }

    public static ApiProblem invalid(String field, String message) {
        return invalid(List.of(new FieldError(field, message)));
    }
}
