package com.example.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.slf4j.MDC;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemBody(
        String type,
        String title,
        int status,
        String instance,
        String code,
        String traceId,
        boolean retryable,
        String detail,
        String upstream,
        List<ApiProblem.FieldError> errors) {

    public static ProblemBody of(ApiProblem problem, String path) {
        return of(problem, path, MDC.get(CorrelationFilter.CORRELATION_ID));
    }

    public static ProblemBody of(ApiProblem problem, String path, String traceId) {
        ApiProblem.Code code = problem.code;
        return new ProblemBody(
                "https://api.example.com/problems/" + code.wire().replace('_', '-'),
                code.title,
                code.status,
                path,
                code.wire(),
                traceId,
                code.retryable,
                problem.detail,
                problem.upstream,
                problem.errors);
    }
}
