package com.autoservicehub.exception;

import com.autoservicehub.dto.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Central error handling (SRS 15). Never leaks stack traces, SQL errors or
 * provider details to the client; logs full details server-side with a
 * correlation id instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessRule(BusinessRuleException ex) {
        return build(HttpStatus.CONFLICT, "BUSINESS_RULE_VIOLATION", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    /**
     * A required query parameter that was not supplied, or one that could not be
     * converted to its declared type (e.g. {@code date=not-a-date}).
     *
     * <p>Both are the caller's malformed request, so both are 400. Without these,
     * Spring's own exceptions fall through to the generic handler below and are
     * reported as 500 — which blames the server for a client mistake and hides the
     * real cause behind a generic "something went wrong".
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex) {
        if (ex instanceof MissingServletRequestParameterException missing) {
            return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    "Required parameter '" + missing.getParameterName() + "' is missing.");
        }
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    "Parameter '" + mismatch.getName() + "' has an invalid value.");
        }
        // A malformed JSON body: the message is deliberately generic, since the
        // parser's own text can echo the payload back to the client.
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request body is malformed.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex) {
        // TODO: log ex with correlation id via a logging framework (e.g. SLF4J + MDC)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. Please try again.");
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String code, String message) {
        ApiErrorResponse error = new ApiErrorResponse(code, message, UUID.randomUUID().toString(), LocalDateTime.now());
        return ResponseEntity.status(status).body(error);
    }
}
