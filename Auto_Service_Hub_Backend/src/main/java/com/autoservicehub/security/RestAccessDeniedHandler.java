package com.autoservicehub.security;

import com.autoservicehub.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Returns the project's standard {@link ApiErrorResponse} JSON body for authenticated
 * users who lack permission (HTTP 403) instead of Spring's default {@code sendError} page.
 *
 * <p>Supports SRS 9.1 and SRS 15. The message wording matches the existing
 * {@code GlobalExceptionHandler} 403 response so client behaviour is unchanged. No
 * exception detail, token or header value is written to the response.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        String correlationId = UUID.randomUUID().toString();

        if (response.isCommitted()) {
            log.debug("403 handler skipped for committed response correlationId={} method={} uri={}",
                    correlationId, request.getMethod(), request.getRequestURI());
            return;
        }

        log.debug("Access denied correlationId={} method={} uri={} principal={} reason={}",
                correlationId, request.getMethod(), request.getRequestURI(),
                currentPrincipalName(),
                accessDeniedException == null ? "unknown" : accessDeniedException.getClass().getSimpleName());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiErrorResponse body = new ApiErrorResponse(
                "FORBIDDEN",
                "You do not have permission to perform this action.",
                correlationId,
                LocalDateTime.now());

        objectMapper.writeValue(response.getWriter(), body);
        response.flushBuffer();
    }

    /** Username only, for server-side diagnostics. Never sent to the client. */
    private String currentPrincipalName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "anonymous";
        }
        return authentication.getName() == null ? "anonymous" : authentication.getName();
    }
}
