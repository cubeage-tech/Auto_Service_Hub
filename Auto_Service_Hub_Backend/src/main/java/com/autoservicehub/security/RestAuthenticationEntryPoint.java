package com.autoservicehub.security;

import com.autoservicehub.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Returns the project's standard {@link ApiErrorResponse} JSON body for unauthenticated
 * requests (HTTP 401) instead of Spring's default {@code sendError} page.
 *
 * <p>Supports SRS 9.1 (consistent error JSON: code, message, timestamp, request/correlation
 * ID) and SRS 15 (consistent API error objects so the client can render errors uniformly).
 * No exception detail, token or header value is ever written to the response.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        String correlationId = UUID.randomUUID().toString();

        if (response.isCommitted()) {
            // Never write a second body; the response is already on the wire.
            log.debug("401 entry point skipped for committed response correlationId={} method={} uri={}",
                    correlationId, request.getMethod(), request.getRequestURI());
            return;
        }

        // Logged at DEBUG deliberately: 401s are routine for anonymous probing and
        // the auth exception message can contain user input.
        log.debug("Unauthenticated request rejected correlationId={} method={} uri={} reason={}",
                correlationId, request.getMethod(), request.getRequestURI(),
                authException == null ? "unknown" : authException.getClass().getSimpleName());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiErrorResponse body = new ApiErrorResponse(
                "UNAUTHORIZED",
                "Authentication is required to access this resource.",
                correlationId,
                LocalDateTime.now());

        objectMapper.writeValue(response.getWriter(), body);
        response.flushBuffer();
    }
}
