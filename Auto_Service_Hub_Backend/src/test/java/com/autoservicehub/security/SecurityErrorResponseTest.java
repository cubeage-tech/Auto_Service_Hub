package com.autoservicehub.security;

import com.autoservicehub.config.SecurityConfig;
import com.autoservicehub.controller.CustomerController;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.service.CustomerService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SRS 9.1 / SRS 15: authentication and authorization failures must return the
 * project's standard {@code ApiErrorResponse} JSON instead of Spring's default
 * {@code sendError} body.
 *
 * <p>Uses the REAL {@link SecurityConfig} so the production entry point, the
 * production access-denied handler and the production {@code permitAll} rules are all
 * exercised, while the JWT filter itself is mocked to pass requests through. This
 * mirrors the slice pattern already used by CustomerControllerAuthorizationTest.
 */
@WebMvcTest(controllers = CustomerController.class)
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        // @WebMvcTest does not component-scan @Component classes, so the two
        // production handlers are imported explicitly. In production they are picked
        // up by the application's normal component scan. The ObjectMapper they need is
        // auto-configured by @WebMvcTest.
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class
})
class SecurityErrorResponseTest {

    /** Requires ADMIN/OWNER on CustomerController. */
    private static final String PROTECTED_URL = "/api/v1/customers/42";

    @Autowired MockMvc mockMvc;

    @MockBean CustomerService          customerService;
    @MockBean JwtAuthenticationFilter  jwtAuthenticationFilter;
    @MockBean JwtTokenProvider         jwtTokenProvider;
    @MockBean CustomUserDetailsService customUserDetailsService;

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    // ── 401 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SEC401-1 - anonymous request to a protected endpoint returns 401 JSON envelope")
    void anonymousRequestReturns401Json() throws Exception {
        mockMvc.perform(get(PROTECTED_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("SEC401-2 - the 401 body does not leak internals or secrets")
    void unauthorizedBodyDoesNotLeakInternals() throws Exception {
        String body = mockMvc.perform(get(PROTECTED_URL))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("Exception"), "must not contain exception details: " + body);
        assertFalse(body.contains("com.autoservicehub"), "must not contain internal class names: " + body);
        assertFalse(body.contains("Bearer"), "must not contain token material: " + body);
        assertFalse(body.toLowerCase().contains("password"), "must not contain credential hints: " + body);
    }

    @Test
    @DisplayName("SEC403-1 - authenticated user without the role gets 403 JSON envelope")
    void authenticatedButUnauthorizedReturns403Json() throws Exception {
        mockMvc.perform(delete(PROTECTED_URL)
                        .with(SecurityMockMvcRequestPostProcessors.user("mechanic-a").roles("MECHANIC")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("SEC403-2 - the 403 body does not leak internals")
    void forbiddenBodyDoesNotLeakInternals() throws Exception {
        String body = mockMvc.perform(delete(PROTECTED_URL)
                        .with(SecurityMockMvcRequestPostProcessors.user("mechanic-a").roles("MECHANIC")))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("Exception"), body);
        assertFalse(body.contains("com.autoservicehub"), body);
    }

    @Test
    @DisplayName("SEC403-3 - an authorized role is still allowed through (rules unchanged)")
    void authorizedRoleStillSucceeds() throws Exception {
        mockMvc.perform(delete(PROTECTED_URL)
                        .with(SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("SECP-1 - permitAll /api/v1/auth/** is not blocked by security")
    void permitAllPathIsNotBlockedBySecurity() throws Exception {
        // No controller is mapped here in the slice, so any non-401 / non-403 result
        // proves the security chain let the request through the permitAll rule.
        int status = mockMvc.perform(get("/api/v1/auth/phase1-public"))
                .andReturn().getResponse().getStatus();

        assertNotEquals(401, status, "permitAll path must not require authentication");
        assertNotEquals(403, status, "permitAll path must not require a role");
    }
}
