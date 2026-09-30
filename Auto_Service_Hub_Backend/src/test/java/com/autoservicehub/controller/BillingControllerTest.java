package com.autoservicehub.controller;

import com.autoservicehub.dto.EstimateRequestDTO;
import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.EstimateService;
import com.autoservicehub.service.InvoiceService;
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
 * Controller slice tests for the billing endpoints on
 * {@code EstimateController} and {@code InvoiceController}, covering validation,
 * error mapping and the @PreAuthorize role guards.
 *
 * Uses {@code @WebMvcTest} with the project's minimal inner security config
 * (method security on, JWT filter not registered).
 *
 * Test cases
 * ----------
 * CT1  create estimate with items   → 201, server-calculated totals returned
 * CT2  estimate without items       → 400
 * CT3  estimate without jobCardId   → 400
 * CT4  item without description     → 400 (nested @Valid)
 * CT5  item with zero quantity      → 400
 * CT6  create invoice with items    → 201
 * CT7  invoice without items        → 400
 * CT8  unknown job card             → 404
 * CT9  discount above subtotal      → 409
 * CT10 outstanding amount          → 200
 * CT11 MECHANIC may not write billing → 403
 * CT12 anonymous                    → 401
 */
@WebMvcTest(controllers = {EstimateController.class, InvoiceController.class})
@Import({BillingControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class BillingControllerTest {

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

    private static final String ESTIMATES = "/api/v1/estimates";
    private static final String INVOICES  = "/api/v1/invoices";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean EstimateService       estimateService;
    @MockBean InvoiceService        invoiceService;
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

    private String validEstimateJson() {
        return """
                {
                  "jobCardId": 55,
                  "items": [
                    { "description": "Brake pads", "quantity": 2, "unitPrice": 500.00 },
                    { "description": "Labour",     "quantity": 2, "unitPrice": 150.00 }
                  ]
                }
                """;
    }

    private com.autoservicehub.dto.EstimateResponseDTO estimateResponse() {
        com.autoservicehub.dto.EstimateResponseDTO dto =
                new com.autoservicehub.dto.EstimateResponseDTO();
        dto.setId(42L);
        dto.setJobCardId(55L);
        dto.setSubtotal(new BigDecimal("1300.00"));
        dto.setDiscount(new BigDecimal("0.00"));
        dto.setTax(new BigDecimal("234.00"));
        dto.setTotal(new BigDecimal("1534.00"));
        dto.setStatus("DRAFT");
        dto.setItems(List.of());
        return dto;
    }

    private InvoiceResponseDTO invoiceResponse() {
        InvoiceResponseDTO dto = new InvoiceResponseDTO();
        dto.setId(42L);
        dto.setJobCardId(55L);
        dto.setSubtotal(new BigDecimal("1300.00"));
        dto.setDiscount(new BigDecimal("0.00"));
        dto.setGst(new BigDecimal("234.00"));
        dto.setTotal(new BigDecimal("1534.00"));
        dto.setStatus("PENDING");
        dto.setItems(List.of());
        dto.setAmountPaid(new BigDecimal("0.00"));
        dto.setOutstandingAmount(new BigDecimal("1534.00"));
        return dto;
    }

    // ── CT1: estimate creation with server-calculated totals ──────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT1 — create an estimate with line items → 201, server totals returned")
    void ct1_createEstimate_returns201() throws Exception {
        when(estimateService.create(any())).thenReturn(estimateResponse());

        mockMvc.perform(post(ESTIMATES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEstimateJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.subtotal").value(1300.00))
                .andExpect(jsonPath("$.data.tax").value(234.00));
    }

    // ── CT2..CT5: request validation → 400 ────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT2 — an estimate with no line items → 400 VALIDATION_ERROR")
    void ct2_estimateWithoutItems_returns400() throws Exception {
        mockMvc.perform(post(ESTIMATES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"jobCardId\": 55, \"items\": [] }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT3 — an estimate without jobCardId → 400 VALIDATION_ERROR")
    void ct3_estimateWithoutJobCardId_returns400() throws Exception {
        mockMvc.perform(post(ESTIMATES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"items\": [ { \"description\": \"x\", \"quantity\": 1, \"unitPrice\": 1.00 } ] }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT4 — a line item without description → 400, nested @Valid applied")
    void ct4_itemWithoutDescription_returns400() throws Exception {
        mockMvc.perform(post(ESTIMATES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"jobCardId\": 55, \"items\": [ { \"quantity\": 1, \"unitPrice\": 10.00 } ] }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT5 — a line item with zero quantity → 400 VALIDATION_ERROR")
    void ct5_itemWithZeroQuantity_returns400() throws Exception {
        mockMvc.perform(post(ESTIMATES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"jobCardId\": 55, \"items\": [ { \"description\": \"x\", \"quantity\": 0, \"unitPrice\": 10.00 } ] }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── CT6 / CT7: invoice creation ───────────────────────────────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT6 — create an invoice with line items → 201")
    void ct6_createInvoice_returns201() throws Exception {
        when(invoiceService.create(any())).thenReturn(invoiceResponse());

        mockMvc.perform(post(INVOICES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEstimateJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.total").value(1534.00));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT7 — an invoice with no line items → 400 VALIDATION_ERROR")
    void ct7_invoiceWithoutItems_returns400() throws Exception {
        mockMvc.perform(post(INVOICES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"jobCardId\": 55, \"items\": [] }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── CT8 / CT9: error mapping ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT8 — an unknown job card → 404 NOT_FOUND")
    void ct8_unknownJobCard_returns404() throws Exception {
        when(invoiceService.create(any()))
                .thenThrow(new ResourceNotFoundException("JobCard not found: 55"));

        mockMvc.perform(post(INVOICES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEstimateJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT9 — a discount larger than the subtotal → 409 BUSINESS_RULE_VIOLATION")
    void ct9_discountAboveSubtotal_returns409() throws Exception {
        when(invoiceService.create(any())).thenThrow(new BusinessRuleException(
                "discount (5000.00) cannot exceed the subtotal (100.00)."));

        mockMvc.perform(post(INVOICES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEstimateJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    // ── CT10: outstanding amount ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT10 — outstanding amount for an invoice → 200")
    void ct10_outstandingAmount_returns200() throws Exception {
        when(invoiceService.getOutstandingAmount(42L)).thenReturn(new BigDecimal("1000.00"));

        mockMvc.perform(get(INVOICES + "/42/outstanding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1000.00));
    }

    // ── CT11 / CT12: authorization ────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT11 — MECHANIC may read billing but not create it → 403")
    void ct11_mechanicCannotCreateBilling_returns403() throws Exception {
        mockMvc.perform(post(INVOICES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEstimateJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("CT12 — anonymous request → 401")
    void ct12_anonymous_returns401() throws Exception {
        mockMvc.perform(get(INVOICES)).andExpect(status().isUnauthorized());
    }
}
