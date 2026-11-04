package com.example.platform.web;

import com.example.platform.web.ApiProblem.Code;
import com.example.platform.web.ApiProblem.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;

/** Turns everything raised while handling a request into problem+json. */
@RestControllerAdvice
public class ProblemHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ProblemBody> onProblem(ApiProblem problem, HttpServletRequest request) {
        return render(problem, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemBody> onInvalidParameters(HandlerMethodValidationException error, HttpServletRequest request) {
        List<FieldError> errors = new ArrayList<>();
        for (ParameterValidationResult result : error.getParameterValidationResults()) {
            String location = location(result.getMethodParameter());
            if (result instanceof org.springframework.validation.method.ParameterErrors fields) {
                addFieldErrors(errors, fields);
            } else {
                for (MessageSourceResolvable item : result.getResolvableErrors()) {
                    errors.add(new FieldError(location, item.getDefaultMessage()));
                }
            }
        }
        return render(ApiProblem.invalid(errors), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemBody> onInvalidBody(MethodArgumentNotValidException error, HttpServletRequest request) {
        List<FieldError> errors = new ArrayList<>();
        addFieldErrors(errors, error.getBindingResult());
        return render(ApiProblem.invalid(errors), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemBody> onMissingHeader(MissingRequestHeaderException error, HttpServletRequest request) {
        return render(ApiProblem.invalid("header." + error.getHeaderName(), "This header is required."), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemBody> onWrongType(MethodArgumentTypeMismatchException error, HttpServletRequest request) {
        return render(ApiProblem.invalid(location(error.getParameter()), "This value has the wrong type."), request);
    }

    /** Found by fuzzing: Tomcat throws on a query string such as {@code ?=}, which is the client's mistake. */
    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<ProblemBody> onMalformedQuery(InvalidParameterException error, HttpServletRequest request) {
        return render(ApiProblem.invalid("query", "The query string is malformed."), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemBody> onUnreadableBody(HttpMessageNotReadableException error, HttpServletRequest request) {
        return render(ApiProblem.invalid(bodyField(error), "This value is missing or malformed."), request);
    }

    /** Deliberately doesn't use Jackson's message, which quotes the input. */
    private static String bodyField(HttpMessageNotReadableException error) {
        if (error.getCause() instanceof DatabindException mapping && !mapping.getPath().isEmpty()) {
            StringBuilder field = new StringBuilder("body");
            mapping.getPath().forEach(step -> field.append('.').append(
                    step.getPropertyName() != null ? step.getPropertyName() : String.valueOf(step.getIndex())));
            return field.toString();
        }
        return "body";
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ProblemBody> onNoRoute(Exception error, HttpServletRequest request) {
        return render(ApiProblem.of(Code.NOT_FOUND), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemBody> onWrongMethod(HttpRequestMethodNotSupportedException error, HttpServletRequest request) {
        String allowed = String.join(", ", error.getSupportedMethods() == null ? new String[0] : error.getSupportedMethods());
        return render(ApiProblem.withHeaders(Code.METHOD_NOT_ALLOWED, null, Map.of("Allow", allowed)), request);
    }

    /**
     * Anything else the framework raises, for example an unsupported media type. Each status keeps its
     * meaning: a malformed request must not be reported as a missing resource.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemBody> onAnythingElse(Exception error, HttpServletRequest request) {
        if (error instanceof ErrorResponse framework && HttpStatus.valueOf(framework.getStatusCode().value()).is4xxClientError()) {
            return render(ApiProblem.of(Code.VALIDATION_ERROR), request);
        }
        LOG.atError().setCause(error).log("unhandled_error");
        return render(ApiProblem.of(Code.INTERNAL_ERROR), request);
    }

    private static ResponseEntity<ProblemBody> render(ApiProblem problem, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.code.status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        problem.headers.forEach(response::header);
        return response.body(ProblemBody.of(problem, request.getRequestURI()));
    }

    private static void addFieldErrors(List<FieldError> errors, Errors source) {
        for (org.springframework.validation.FieldError item : source.getFieldErrors()) {
            errors.add(new FieldError("body." + item.getField(), item.getDefaultMessage()));
        }
        if (source instanceof BindingResult && source.getFieldErrorCount() == 0) {
            errors.add(new FieldError("body", "This value is invalid."));
        }
    }

    private static String location(MethodParameter parameter) {
        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null) {
            return "query." + (query.name().isEmpty() ? parameter.getParameterName() : query.name());
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null) {
            return "path." + (path.name().isEmpty() ? parameter.getParameterName() : path.name());
        }
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null) {
            return "header." + (header.name().isEmpty() ? parameter.getParameterName() : header.name());
        }
        return "body";
    }
}
