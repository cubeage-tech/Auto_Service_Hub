package com.autoservicehub.controller;

import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.CustomerService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization slice tests for {@link CustomerController}.
 *
 * <p>Focus: the hard-delete endpoint must be reachable by ADMIN and OWNER only
 * (D1 fix), while the soft-deactivate endpoint keeps its existing permissions.
 * Also guards against regressions in the deactivate flow and request validation.
 *
 * <p>Uses {@code @WebMvcTest} with a minimal inner security configuration that
 * enables method security (so {@code @PreAuthorize} is evaluated), requires
 * authentication, and does NOT register the real JwtAuthenticationFilter logic.
 */
@WebMvcTest(controllers = CustomerController.class)
@Import({CustomerControllerAuthorizationTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class CustomerControllerAuthorizationTest {

    // ── Minimal security config for the slice test ────────────────────────
    // Enables method security (@PreAuthorize), requires authentication on all
    // requests, and sends 401 for anonymous / 403 for denied requests.
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((req, res, e) ->
                        res.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                    .accessDeniedHandler((req, res, e) ->
                        res.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }
    }

    private static final String CUSTOMER_URL = "/api/v1/customers";
    private static final Long   CUSTOMER_ID = 42L;

    @Autowired MockMvc mockMvc;

    @MockBean CustomerService          customerService;
    @MockBean JwtAuthenticationFilter  jwtAuthenticationFilter;
    @MockBean JwtTokenProvider         jwtTokenProvider;
    @MockBean CustomUserDetailsService customUserDetailsService;

    // Make the mocked JwtAuthenticationFilter actually pass requests through
    // to the next filter in the chain. Without this, the mock's doFilter is
    // a no-op and the request never reaches the dispatcher servlet.
    @BeforeEach
    void configureFilterPassthrough() throws ServletException, IOException {
        org.mockito.Mockito.doAnswer(invocation -> {
            HttpServletRequest  req   = invocation.getArgument(0);
            HttpServletResponse res   = invocation.getArgument(1);
            FilterChain         chain = invocation.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class),
                any(FilterChain.class));
    }

    // ── AT1: DELETE as ADMIN → 204 ────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AT1 — DELETE customer as ADMIN → 204, service invoked")
    void at1_deleteAsAdmin_returns204AndInvokesService() throws Exception {
        mockMvc.perform(delete(CUSTOMER_URL + "/" + CUSTOMER_ID))
                .andExpect(status().isNoContent());

        verify(customerService, times(1)).delete(CUSTOMER_ID);
    }

    // ── AT2: DELETE as OWNER → 204 ────────────────────────────────────────

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("AT2 — DELETE customer as OWNER → 204, service invoked")
    void at2_deleteAsOwner_returns204AndInvokesService() throws Exception {
        mockMvc.perform(delete(CUSTOMER_URL + "/" + CUSTOMER_ID))
                .andExpect(status().isNoContent());

        verify(customerService, times(1)).delete(CUSTOMER_ID);
    }

    // ── AT3: DELETE as MANAGER → 403 (the D1 fix) ─────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("AT3 — DELETE customer as MANAGER → 403, service NOT invoked")
    void at3_deleteAsManager_returns403AndServiceNotInvoked() throws Exception {
        mockMvc.perform(delete(CUSTOMER_URL + "/" + CUSTOMER_ID))
                .andExpect(status().isForbidden());

        verify(customerService, never()).delete(any());
    }

    // ── AT4: DELETE as SERVICE_ADVISOR → 403 (the D1 fix) ─────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("AT4 — DELETE customer as SERVICE_ADVISOR → 403, service NOT invoked")
    void at4_deleteAsServiceAdvisor_returns403AndServiceNotInvoked() throws Exception {
        mockMvc.perform(delete(CUSTOMER_URL + "/" + CUSTOMER_ID))
                .andExpect(status().isForbidden());

        verify(customerService, never()).delete(any());
    }

    // ── AT5: DELETE as MECHANIC → 403 ──────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("AT5 — DELETE customer as MECHANIC → 403, service NOT invoked")
    void at5_deleteAsMechanic_returns403AndServiceNotInvoked() throws Exception {
        mockMvc.perform(delete(CUSTOMER_URL + "/" + CUSTOMER_ID))
                .andExpect(status().isForbidden());

        verify(customerService, never()).delete(any());
    }

    // ── AT6: DELETE as BILLING_USER → 403 ─────────────────────────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("AT6 — DELETE customer as BILLING_USER → 403, service NOT invoked")
    void at6_deleteAsBillingUser_returns403AndServiceNotInvoked() throws Exception {
        mockMvc.perform(delete(CUSTOMER_URL + "/" + CUSTOMER_ID))
                .andExpect(status().isForbidden());

        verify(customerService, never()).delete(any());
    }

    // ── AT7: DELETE as INVENTORY_MANAGER → 403 ────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("AT7 — DELETE customer as INVENTORY_MANAGER → 403, service NOT invoked")
    void at7_deleteAsInventoryManager_returns403AndServiceNotInvoked() throws Exception {
        mockMvc.perform(delete(CUSTOMER_URL + "/" + CUSTOMER_ID))
                .andExpect(status().isForbidden());

        verify(customerService, never()).delete(any());
    }

    // ── AT8: DELETE anonymous → 401 ───────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("AT8 — DELETE customer anonymously → 401, service NOT invoked")
    void at8_deleteAnonymous_returns401AndServiceNotInvoked() throws Exception {
        mockMvc.perform(delete(CUSTOMER_URL + "/" + CUSTOMER_ID))
                .andExpect(status().isUnauthorized());

        verify(customerService, never()).delete(any());
    }

    // ── AT9: DELETE as ADMIN with a missing customer → 404 ────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AT9 — DELETE customer as ADMIN, service throws not-found → 404")
    void at9_deleteAsAdmin_serviceThrowsNotFound_returns404() throws Exception {
        doThrow(new com.autoservicehub.exception.ResourceNotFoundException(
                        "Customer not found: " + CUSTOMER_ID))
                .when(customerService).delete(eq(CUSTOMER_ID));

        mockMvc.perform(delete(CUSTOMER_URL + "/" + CUSTOMER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── AT10: PATCH deactivate as SERVICE_ADVISOR → 204 (preserved) ────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("AT10 — PATCH deactivate as SERVICE_ADVISOR → 204 (existing flow preserved)")
    void at10_deactivateAsServiceAdvisor_returns204() throws Exception {
        mockMvc.perform(patch(CUSTOMER_URL + "/" + CUSTOMER_ID + "/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Customer deactivated"));

        verify(customerService, times(1)).deactivate(CUSTOMER_ID);
    }

    // ── AT11: PATCH deactivate as MECHANIC → 403 (preserved) ──────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("AT11 — PATCH deactivate as MECHANIC → 403 (existing restriction preserved)")
    void at11_deactivateAsMechanic_returns403() throws Exception {
        mockMvc.perform(patch(CUSTOMER_URL + "/" + CUSTOMER_ID + "/deactivate"))
                .andExpect(status().isForbidden());

        verify(customerService, never()).deactivate(any());
    }

    // ── AT12: POST blank phone → 400 (validation preserved) ────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("AT12 — POST customer with blank phone → 400 VALIDATION_ERROR")
    void at12_createWithBlankPhone_returns400() throws Exception {
        String body = "{\"name\":\"Arjun Mehta\",\"phone\":\"   \",\"email\":\"arjun@example.com\"}";

        mockMvc.perform(post(CUSTOMER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(customerService, never()).create(any());
    }
}
