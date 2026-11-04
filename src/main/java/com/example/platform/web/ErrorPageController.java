package com.example.platform.web;

import com.example.platform.web.ApiProblem.Code;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles what fails before Spring MVC sees a request, such as a URL the security firewall rejects.
 * Replaces Boot's default error page, so that these failures are problem+json too.
 */
@RestController
class ErrorPageController implements ErrorController {
    @RequestMapping("/error")
    ResponseEntity<ProblemBody> error(HttpServletRequest request, HttpServletResponse response) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = status instanceof Integer number ? number : 500;
        ApiProblem problem = ApiProblem.of(switch (code) {
            case 401 -> Code.UNAUTHORIZED;
            case 403 -> Code.FORBIDDEN;
            case 404 -> Code.NOT_FOUND;
            case 405 -> Code.METHOD_NOT_ALLOWED;
            default -> code < 500 ? Code.VALIDATION_ERROR : Code.INTERNAL_ERROR;
        });
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return ResponseEntity.status(problem.code.status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.of(problem, uri == null ? "/" : uri.toString(),
                        // The filter has finished and cleared the logging context, but set this header.
                        response.getHeader(CorrelationFilter.CORRELATION_HEADER)));
    }
}
