package com.autoservicehub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request a correlation id (SRS 15, 19).
 *
 * <p>SRS 15 requires technical detail to be logged server-side against a
 * request/correlation id so a user can quote one value from an error response and
 * the matching log line can be found. Previously the id existed only on the error
 * body and was generated at throw time, so it never appeared in any log and could
 * not connect a response to a request.
 *
 * <p>The id is generated once per request, put in the SLF4J {@link MDC} so every
 * log line emitted while handling it is tagged automatically, and echoed back as
 * the {@code X-Correlation-Id} response header. The error body already carries a
 * {@code correlationId} field, so nothing about the response shape changes.
 *
 * <p>An inbound {@code X-Correlation-Id} is honoured when present, so a trace id
 * from an upstream proxy or gateway survives into the application logs. It is
 * length-limited and sanitised: the value is echoed into a response header and a
 * log file, so an unbounded attacker-supplied string would be a response-splitting
 * and log-forging vector.
 *
 * <p>Ordered highest so it wraps the security and JWT filters — an authentication
 * failure is exactly the kind of event that needs to be traceable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationIdFilter extends OncePerRequestFilter {

    /** Header used both to accept an upstream id and to echo ours back. */
    public static final String HEADER = "X-Correlation-Id";

    /** MDC key, so it lines up with the {@code correlationId} field on the error body. */
    public static final String MDC_KEY = "correlationId";

    /** Request attribute, so the exception handler can read the id without MDC. */
    public static final String ATTRIBUTE = RequestCorrelationIdFilter.class.getName() + ".correlationId";

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String correlationId = sanitise(request.getHeader(HEADER));
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Cleared in a finally block: on a pooled request thread a leaked id
            // would be attached to an unrelated later request's log lines.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Accepts a caller-supplied id only if it is short and purely alphanumeric
     * with dashes, underscores or dots. Anything else is discarded and a fresh id
     * generated, which keeps CR/LF out of both the response header and the log file.
     */
    private String sanitise(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH) {
            return null;
        }
        String trimmed = candidate.trim();
        return trimmed.matches("[A-Za-z0-9._-]+") ? trimmed : null;
    }
}