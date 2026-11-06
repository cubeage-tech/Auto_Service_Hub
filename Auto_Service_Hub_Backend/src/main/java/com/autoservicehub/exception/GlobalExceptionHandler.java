package com.autoservicehub.exception;

import com.autoservicehub.config.RequestCorrelationIdFilter;
import com.autoservicehub.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * A rejected refresh token: 401 rather than 409, because the caller is
     * unauthenticated (SRS 9.1) rather than in conflict with a business rule.
     *
     * <p>Deliberately separate from {@link #handleAuthentication}, whose single
     * generic message is what stops the login route revealing whether a username
     * exists; reusing it here would report a bad refresh token as a bad password.
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessRule(BusinessRuleException ex) {
        return build(HttpStatus.CONFLICT, "BUSINESS_RULE_VIOLATION", ex.getMessage());
    }

    /**
     * A Government ID was submitted but the server has no encryption key for it.
     * Reported as 503 rather than a generic 500: the request is valid and the
     * caller is authorised, but this capability is unavailable until an operator
     * sets MECHANIC_GOVID_ENCRYPTION_KEY, so the message stays actionable.
     */
    @ExceptionHandler(GovernmentIdKeyNotConfiguredException.class)
    public ResponseEntity<ApiErrorResponse> handleGovIdKeyNotConfigured(
            GovernmentIdKeyNotConfiguredException ex) {
        log.error("Government ID rejected: encryption key not configured [{}]",
                currentCorrelationId());
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "GOV_ID_ENCRYPTION_KEY_NOT_CONFIGURED", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String authorities = authentication == null
                ? ""
                : authentication.getAuthorities().stream()
                        .map(authority -> authority.getAuthority())
                        .sorted()
                        .collect(Collectors.joining(","));
        log.warn("Authorization denied path={} authenticated={} principalType={} authorities={}",
                request.getRequestURI(),
                authentication != null && authentication.isAuthenticated(),
                authentication == null || authentication.getPrincipal() == null
                        ? "none"
                        : authentication.getPrincipal().getClass().getSimpleName(),
                authorities);
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
     * A request the database refused — a foreign key, a unique index or a
     * not-null column.
     *
     * <p>Reported as 409 rather than falling through to the generic handler.
     * Without this, deleting an invoice that still had payments answered "500
     * Something went wrong", which blames the server for what is really the
     * caller asking for something the current data does not allow. The specific
     * business rules are checked before the write (see {@code InvoiceServiceImpl}
     * and {@code EstimateServiceImpl}); this handler exists so that any rule not
     * yet anticipated degrades to an honest conflict instead of a server fault.
     *
     * <p>Still no SQL, constraint name or stack trace in the response.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Database constraint rejected a write [{}]: {}",
                currentCorrelationId(), rootMessage(ex));
        return build(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "This change conflicts with data that already exists and was not applied.");
    }

    /** The deepest message of a cause chain, for the server-side log only. */
    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /**
     * Failed authentication — bad username or password.
     *
     * <p>Without this handler Spring Security's {@code BadCredentialsException}
     * falls through to the generic {@link Exception} handler below and is
     * reported as 500, which blames the server for what is a rejected credential.
     * It also hid the real problem behind "Something went wrong", so a client
     * could not tell a wrong password from a genuine outage.
     *
     * <p>The message is deliberately generic: naming whether the account exists
     * would let an attacker enumerate valid usernames.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Invalid username or password.");
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
        //
        // The exception is narrowed to our own FractionalQuantityException, whose
        // message is written here and contains only the field name and the value
        // the caller itself sent. Jackson's text is still never echoed.
        String fractionalQuantityMessage = fractionalQuantityMessage(ex);
        if (fractionalQuantityMessage != null) {
            return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", fractionalQuantityMessage);
        }
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request body is malformed.");
    }

    /**
     * The message of a {@link FractionalQuantityException} anywhere in the cause
     * chain, or null when this is some other kind of malformed body.
     *
     * <p>Jackson wraps anything thrown from a deserialiser, so the cause chain
     * has to be walked rather than the top-level type inspected.
     */
    private String fractionalQuantityMessage(Throwable ex) {
        for (Throwable cause = ex; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof FractionalQuantityException fractional) {
                return fractional.getMessage();
            }
        }
        return null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex) {
        // SRS 15: the detail is logged server-side against the correlation id,
        // and only a generic message reaches the client, so stack traces, SQL and
        // provider details are never exposed.
        log.error("Unhandled exception [{}]", currentCorrelationId(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. Please try again.");
    }

    /**
     * The correlation id of the request being handled, falling back to a fresh one.
     *
     * <p>Read from the current request rather than generated per error, so the id
     * the client sees in the response body is the same one that tagged the log line
     * above — which is the whole point of SRS 15. The fallback covers an error
     * raised outside a servlet request (e.g. during startup or in a plain unit test).
     */
    private String currentCorrelationId() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            // ServletRequestAttributes#getAttribute takes a scope, so the request is
            // read directly for the plain attribute the filter set on it.
            Object id = attributes.getRequest().getAttribute(RequestCorrelationIdFilter.ATTRIBUTE);
            if (id instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return UUID.randomUUID().toString();
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String code, String message) {
        ApiErrorResponse error = new ApiErrorResponse(code, message, currentCorrelationId(), LocalDateTime.now());
        return ResponseEntity.status(status).body(error);
    }
}
