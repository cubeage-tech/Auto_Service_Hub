package com.autoservicehub.exception;

import com.autoservicehub.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Central error handling (SRS 15). Never leaks stack traces, SQL errors or
 * provider details to the client; logs full details server-side with a
 * correlation id instead.
 *
 * <p><strong>What is never logged (SRS 15, 19):</strong> authorization headers,
 * bearer tokens, passwords, credentials, request bodies and any other secret.
 * Only the correlation id, exception type, HTTP method and request URI are
 * recorded, which is sufficient to locate the failure in the server log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Name of the quality-check uniqueness backstop declared in
     * {@code db/phase3_additive.sql}. Only this constraint is reported as a
     * retryable conflict; every other integrity violation stays a server error.
     */
    private static final String QC_ATTEMPT_UNIQUE_CONSTRAINT = "uq_quality_checks_job_card_attempt";

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

    /**
     * Concurrent edits on a record that uses the {@code @Version} optimistic-lock
     * column declared on {@code BaseEntity}. SRS 15 requires a conflict response
     * asking the user to refresh; without this handler it surfaced as an opaque 500.
     *
     * <p>Scoped deliberately to {@link OptimisticLockingFailureException} and its
     * subclasses (for example {@code ObjectOptimisticLockingFailureException}) so no
     * unrelated exception is swallowed by this branch.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLocking(
            OptimisticLockingFailureException ex, HttpServletRequest request) {

        String correlationId = UUID.randomUUID().toString();
        log.warn("Concurrent modification detected correlationId={} method={} uri={} exception={}",
                correlationId, methodOf(request), uriOf(request), ex.getClass().getName());

        ApiErrorResponse error = new ApiErrorResponse(
                "CONCURRENT_MODIFICATION",
                "This record was modified by someone else. Please reload the latest version and try again.",
                correlationId,
                LocalDateTime.now());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * A pessimistic row lock could not be acquired or was lost (lock timeout,
     * deadlock victim, or a concurrent writer holding the same job-card row).
     * Reported as a conflict with an actionable message rather than an opaque 500,
     * because it is a transient, caller-retryable condition. No automatic retry is
     * performed: the client decides when to resubmit.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handlePessimisticLocking(
            PessimisticLockingFailureException ex, HttpServletRequest request) {

        String correlationId = UUID.randomUUID().toString();
        log.warn("Pessimistic lock conflict correlationId={} method={} uri={} exception={}",
                correlationId, methodOf(request), uriOf(request), ex.getClass().getName());

        ApiErrorResponse error = new ApiErrorResponse(
                "CONCURRENT_MODIFICATION",
                "This record is being updated by another request. Please wait a moment and try again.",
                correlationId,
                LocalDateTime.now());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Database constraint handling.
     *
     * <p><strong>Deliberately narrow.</strong> A {@link DataIntegrityViolationException}
     * can mean a genuine data defect anywhere in the application (NOT NULL, foreign
     * key, length, check). Reporting those as a retryable "concurrent modification"
     * would tell the caller to retry a request that can never succeed, and would hide
     * a real bug behind a benign-looking 409.
     *
     * <p>Therefore only one specific case is treated as a conflict: the
     * {@code uq_quality_checks_job_card_attempt} uniqueness backstop firing because two
     * writers still computed the same quality-check attempt number despite the
     * pessimistic job-card lock. It is detected by inspecting the constraint name
     * reported by the provider, falling back to the constraint token in the message
     * when the provider does not expose the name.
     *
     * <p>Anything else falls through to a genuine 500 that is logged at ERROR, so the
     * defect stays visible instead of being silently reclassified. The client-facing
     * body is always the generic message: no SQL, table names, column values,
     * connection strings or credentials are ever returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {

        String correlationId = UUID.randomUUID().toString();

        if (isQualityCheckAttemptCollision(ex)) {
            log.warn("Quality check attempt number collision correlationId={} method={} uri={} exception={}",
                    correlationId, methodOf(request), uriOf(request), ex.getClass().getName());

            ApiErrorResponse conflict = new ApiErrorResponse(
                    "QC_ATTEMPT_CONFLICT",
                    "Another quality check for this job card was recorded at the same time. "
                            + "Please reload the history and try again.",
                    correlationId,
                    LocalDateTime.now());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(conflict);
        }

        // Not a known concurrency backstop: this is a real data-integrity defect.
        // Logged at ERROR with the throwable for diagnosis; the client still gets only
        // the generic message.
        log.error("Unmapped data integrity violation correlationId={} method={} uri={} exception={}",
                correlationId, methodOf(request), uriOf(request), ex.getClass().getName(), ex);

        ApiErrorResponse error = new ApiErrorResponse(
                "INTERNAL_ERROR",
                "Something went wrong. Please try again.",
                correlationId,
                LocalDateTime.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * True only for the quality-check attempt-number uniqueness backstop.
     *
     * <p>Primary signal is the constraint name exposed by Hibernate's
     * {@code ConstraintViolationException}. Some drivers/configurations do not populate
     * it, so the constraint token in the exception message is used as a fallback. Both
     * signals require the exact constraint identifier, so a NOT NULL, foreign-key or
     * unrelated unique violation is not matched.
     */
    private boolean isQualityCheckAttemptCollision(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException hibernate) {
                String name = hibernate.getConstraintName();
                if (name != null && name.equalsIgnoreCase(QC_ATTEMPT_UNIQUE_CONSTRAINT)) {
                    return true;
                }
            }
            String message = cause.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains(QC_ATTEMPT_UNIQUE_CONSTRAINT)) {
                return true;
            }
            cause = (cause == cause.getCause()) ? null : cause.getCause();
        }
        return false;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        String correlationId = UUID.randomUUID().toString();

        // Server-side only. The stack trace stays in the log; the client receives a
        // generic message and the correlation id needed to find this entry.
        log.error("Unhandled exception correlationId={} method={} uri={} exception={}",
                correlationId, methodOf(request), uriOf(request), ex.getClass().getName(), ex);

        ApiErrorResponse error = new ApiErrorResponse(
                "INTERNAL_ERROR",
                "Something went wrong. Please try again.",
                correlationId,
                LocalDateTime.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String code, String message) {
        ApiErrorResponse error = new ApiErrorResponse(code, message, UUID.randomUUID().toString(), LocalDateTime.now());
        return ResponseEntity.status(status).body(error);
    }

    private String methodOf(HttpServletRequest request) {
        return request == null ? "unknown" : request.getMethod();
    }

    private String uriOf(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String uri = request.getRequestURI();
        return uri == null ? "unknown" : uri;
    }
}
