package com.autoservicehub.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.autoservicehub.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SRS 15 (Error Handling): unexpected errors must be logged server-side with a
 * correlation id and must never expose stack traces, tokens or credentials to the
 * client; concurrent-edit conflicts must return 409 rather than an opaque 500.
 *
 * <p>Drives the handler directly and captures real Logback output through a
 * {@link ListAppender}, so the logging assertions are evidence-based.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private ObjectMapper         objectMapper;

    private Logger                     handlerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        objectMapper = new ObjectMapper().findAndRegisterModules();

        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(appender);
        appender.stop();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/invoices");
        request.setRequestURI("/api/v1/invoices");
        return request;
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    private String formatted() {
        return events().stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    // ── Item 3: unexpected exceptions are logged with a correlation id ────

    @Test
    @DisplayName("EXC-1 - unhandled exception returns 500 with a generic client message")
    void unhandledExceptionReturns500GenericMessage() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleGeneric(new IllegalStateException("internal detail"), request());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", response.getBody().getCode());
        assertEquals("Something went wrong. Please try again.", response.getBody().getMessage());
    }

    @Test
    @DisplayName("EXC-2 - the 500 log entry contains correlation id, method, URI and exception type")
    void unhandledExceptionIsLoggedWithCorrelationId() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleGeneric(new IllegalStateException("boom"), request());

        String correlationId = response.getBody().getCorrelationId();
        assertNotNull(correlationId);
        assertFalse(correlationId.isBlank());

        assertEquals(1, events().size(), "exactly one ERROR log entry is expected");
        assertEquals(Level.ERROR, events().get(0).getLevel());
        assertTrue(formatted().contains(correlationId), "log must contain the correlation id");
        assertTrue(formatted().contains("POST"), "log must contain the HTTP method");
        assertTrue(formatted().contains("/api/v1/invoices"), "log must contain the request URI");
        assertTrue(formatted().contains("IllegalStateException"), "log must contain the exception type");
    }

    @Test
    @DisplayName("EXC-3 - the client response never contains the exception message or a stack trace")
    void clientResponseHidesExceptionDetail() throws Exception {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleGeneric(new IllegalStateException("connection string leaked"), request());

        String json = objectMapper.writeValueAsString(response.getBody());
        assertFalse(json.contains("connection string leaked"), json);
        assertFalse(json.contains("IllegalStateException"), json);
        assertFalse(json.contains("\\tat "), "must not contain a stack trace: " + json);
        assertFalse(json.contains("com.autoservicehub"), json);
    }

    @Test
    @DisplayName("EXC-4 - no Authorization header or bearer token is written to the log")
    void logEntryDoesNotLeakAuthorizationData() {
        handler.handleGeneric(new IllegalStateException("failure"), request());

        String structured = formatted();
        assertFalse(structured.contains("Authorization"), structured);
        assertFalse(structured.contains("Bearer "), structured);
    }

    // ── Item 4: optimistic locking -> 409 ─────────────────────────────────

    @Test
    @DisplayName("EXC409-1 - ObjectOptimisticLockingFailureException maps to 409 CONCURRENT_MODIFICATION")
    void optimisticLockingFailureMapsTo409() {
        ResponseEntity<ApiErrorResponse> response = handler.handleOptimisticLocking(
                new ObjectOptimisticLockingFailureException("Invoice", 42L), request());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("CONCURRENT_MODIFICATION", response.getBody().getCode());
        assertTrue(response.getBody().getMessage().toLowerCase().contains("reload"),
                "message should ask the user to reload: " + response.getBody().getMessage());
        assertNotNull(response.getBody().getCorrelationId());
        assertNotNull(response.getBody().getTimestamp());
    }

    @Test
    @DisplayName("EXC409-2 - the base OptimisticLockingFailureException is also mapped to 409")
    void baseOptimisticLockingFailureMapsTo409() {
        ResponseEntity<ApiErrorResponse> response = handler.handleOptimisticLocking(
                new OptimisticLockingFailureException("stale version"), request());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("CONCURRENT_MODIFICATION", response.getBody().getCode());
    }

    @Test
    @DisplayName("EXC409-3 - the conflict is logged at WARN with a correlation id")
    void optimisticLockingIsLoggedAtWarn() {
        ResponseEntity<ApiErrorResponse> response = handler.handleOptimisticLocking(
                new ObjectOptimisticLockingFailureException("Invoice", 42L), request());

        assertEquals(1, events().size());
        assertEquals(Level.WARN, events().get(0).getLevel());
        assertTrue(formatted().contains(response.getBody().getCorrelationId()));
    }

    @Test
    @DisplayName("EXC409-4 - the 409 body does not leak entity or stack internals")
    void conflictBodyDoesNotLeakInternals() throws Exception {
        ResponseEntity<ApiErrorResponse> response = handler.handleOptimisticLocking(
                new ObjectOptimisticLockingFailureException("Invoice", 42L), request());

        String json = objectMapper.writeValueAsString(response.getBody());
        assertFalse(json.contains("Exception"), json);
        assertFalse(json.contains("com.autoservicehub"), json);
    }

    // ── Existing behaviour preserved ──────────────────────────────────────

    @Test
    @DisplayName("EXC-5 - 404 and business-rule mappings are unchanged")
    void existingMappingsUnchanged() {
        assertEquals(HttpStatus.NOT_FOUND,
                handler.handleNotFound(new ResourceNotFoundException("Customer not found: 7")).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                handler.handleBusinessRule(new BusinessRuleException("Invalid transition")).getStatusCode());
    }

    @Test
    @DisplayName("EXC-6 - unrelated exceptions are not swallowed by the 409 handler")
    void nonOptimisticExceptionIsNotMappedTo409() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleGeneric(new IllegalArgumentException("plain failure"), request());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", response.getBody().getCode());
    }

    // ── Integrity violations: only the QC attempt constraint is a conflict ────

    /** A QC attempt-number uniqueness collision, as raised by the DB backstop. */
    private DataIntegrityViolationException qcAttemptCollision() {
        // Real Hibernate overload: (String message, SQLException root, String sql, String constraintName)
        org.hibernate.exception.ConstraintViolationException hibernate =
                new org.hibernate.exception.ConstraintViolationException(
                        "could not execute statement",
                        new java.sql.SQLException(
                                "Duplicate entry '55-1' for key 'uq_quality_checks_job_card_attempt'"),
                        "insert into quality_checks values (?,?,?,?,?,?)",
                        "uq_quality_checks_job_card_attempt");
        return new DataIntegrityViolationException("insert failed", hibernate);
    }

    /** A genuine defect elsewhere, e.g. a NOT NULL violation. */
    private DataIntegrityViolationException unrelatedIntegrityViolation() {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new java.sql.SQLException("Column 'total' cannot be null"));
    }

    @Test
    @DisplayName("EXC7 - the QC attempt-number constraint maps to 409 QC_ATTEMPT_CONFLICT")
    void qcAttemptCollisionIsAConflict() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleDataIntegrityViolation(qcAttemptCollision(), request());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("QC_ATTEMPT_CONFLICT", response.getBody().getCode());
        assertNotNull(response.getBody().getCorrelationId());
    }

    @Test
    @DisplayName("EXC8 - an unrelated integrity violation is a 500, NOT a retryable conflict")
    void unrelatedIntegrityViolationIsNotAConflict() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleDataIntegrityViolation(unrelatedIntegrityViolation(), request());

        // The whole point: a real data defect must not be disguised as a concurrency
        // conflict the client would keep retrying.
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", response.getBody().getCode());
    }

    @Test
    @DisplayName("EXC9 - the collision response never leaks SQL, table or column detail")
    void collisionResponseDoesNotLeakDatabaseDetail() throws Exception {
        String json = objectMapper.writeValueAsString(
                handler.handleDataIntegrityViolation(qcAttemptCollision(), request()).getBody());

        assertFalse(json.contains("uq_quality_checks"), "must not name the constraint: " + json);
        assertFalse(json.contains("Duplicate entry"), "must not echo the driver message: " + json);
        assertFalse(json.contains("SQL"), "must not mention SQL: " + json);
        assertFalse(json.contains("jdbc:"), "must not leak a connection string: " + json);
    }

    @Test
    @DisplayName("EXC10 - the unrelated-violation response stays generic too")
    void unrelatedViolationResponseStaysGeneric() throws Exception {
        String json = objectMapper.writeValueAsString(
                handler.handleDataIntegrityViolation(unrelatedIntegrityViolation(), request()).getBody());

        assertFalse(json.contains("cannot be null"), "must not echo the DB message: " + json);
        assertFalse(json.contains("total"), "must not name the column: " + json);
    }
}