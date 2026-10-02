package com.autoservicehub.controller;

import com.autoservicehub.dto.PaymentRequestDTO;
import com.autoservicehub.dto.PaymentResponseDTO;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the Payment → Invoice endpoints on
 * {@code PaymentController}, covering validation, error mapping and the
 * @PreAuthorize role guards.
 *
 * Uses {@code @WebMvcTest} with the project's minimal inner security config
 * (method security on, JWT filter not registered).
 *
 * Test cases
 * ----------
 * CT1  valid payment             → 201, invoice status and outstanding returned
 * CT2–CT5 invalid payloads       → 400
 * CT6  unknown invoice           → 404
 * CT7  over-payment              → 409
 * CT8  cancelled invoice         → 409
 * CT9  payment history           → 200
 * CT10 history, unknown invoice  → 404
 * CT11 MECHANIC may not record   → 403
 * CT12 anonymous                 → 401
 */
@WebMvcTest(controllers = PaymentController.class)
@Import({PaymentControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class PaymentControllerTest {

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

    private static final String BASE    = "/api/v1/payments";
    private static final String HISTORY = BASE + "/invoice/42";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean PaymentService        service;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean JwtTokenProvider        jwtTokenProvider;
    @MockBean CustomUserDetailsService customUserDetailsService;

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

    // ── Helpers ──────────────────────────────────────────────────────────

    private PaymentResponseDTO paymentResponse() {
        PaymentResponseDTO dto = new PaymentResponseDTO();
        dto.setId(100L);
        dto.setInvoiceId(42L);
        dto.setAmount(new BigDecimal("500.00"));
        dto.setStatus("SUCCESS");
        dto.setInvoiceStatus("PARTIALLY_PAID");
        dto.setInvoiceOutstandingAmount(new BigDecimal("680.00"));
        return dto;
    }

    private String validBody() {
        PaymentRequestDTO req = new PaymentRequestDTO();
        req.setInvoiceId(42L);
        req.setAmount(new BigDecimal("500.00"));
        req.setMode("UPI");
        try {
            return mapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── CT1: a valid payment ──────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT1 — a valid payment → 201, invoice status and outstanding returned")
    void ct1_validPayment_returns201() throws Exception {
        when(service.create(any())).thenReturn(paymentResponse());

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.amount").value(500.00))
                .andExpect(jsonPath("$.data.invoiceStatus").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.data.invoiceOutstandingAmount").value(680.00));
    }

    // ── CT2–CT5: request validation → 400 ─────────────────────────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT2 — a payment without amount → 400 VALIDATION_ERROR")
    void ct2_missingAmount_returns400() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"invoiceId\": 42 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT3 — a zero amount → 400 VALIDATION_ERROR")
    void ct3_zeroAmount_returns400() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"invoiceId\": 42, \"amount\": 0 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT4 — a negative amount → 400 VALIDATION_ERROR")
    void ct4_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"invoiceId\": 42, \"amount\": -100.00 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT5 — a payment without invoiceId → 400 VALIDATION_ERROR")
    void ct5_missingInvoiceId_returns400() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"amount\": 100.00 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── CT6–CT8: error mapping ────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT6 — an unknown invoice → 404 NOT_FOUND")
    void ct6_unknownInvoice_returns404() throws Exception {
        when(service.create(any()))
                .thenThrow(new ResourceNotFoundException("Invoice not found: 42"));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT7 — a payment beyond the outstanding amount → 409 BUSINESS_RULE_VIOLATION")
    void ct7_overPayment_returns409() throws Exception {
        when(service.create(any())).thenThrow(new BusinessRuleException(
                "Payment of 900.00 exceeds the outstanding amount of 680.00 on invoice 42."));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT8 — a CANCELLED invoice → 409 BUSINESS_RULE_VIOLATION")
    void ct8_cancelledInvoice_returns409() throws Exception {
        when(service.create(any())).thenThrow(new BusinessRuleException(
                "Invoice 42 is CANCELLED and cannot accept a payment."));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    // ── CT9 / CT10: payment history ───────────────────────────────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT9 — payment history for an invoice → 200")
    void ct9_paymentHistory_returns200() throws Exception {
        when(service.listByInvoice(anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of(paymentResponse()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(HISTORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].invoiceId").value(42))
                .andExpect(jsonPath("$.data.content[0].amount").value(500.00))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT10 — history for an unknown invoice → 404")
    void ct10_historyUnknownInvoice_returns404() throws Exception {
        when(service.listByInvoice(anyLong(), any()))
                .thenThrow(new ResourceNotFoundException("Invoice not found: 42"));

        mockMvc.perform(get(HISTORY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── CT11 / CT12: authorization ────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT11 — MECHANIC may not record payments → 403")
    void ct11_mechanicCannotRecordPayment_returns403() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("CT12 — anonymous request → 401")
    void ct12_anonymous_returns401() throws Exception {
        mockMvc.perform(get(HISTORY)).andExpect(status().isUnauthorized());
    }
}
