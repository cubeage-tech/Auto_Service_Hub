package com.autoservicehub.controller;

import com.autoservicehub.dto.InvoiceItemResponseDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import com.autoservicehub.entity.BillingItemCategory;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the estimate → invoice conversion endpoint.
 *
 * <p>The endpoint is reached through {@code EstimateService.convertToInvoice},
 * which delegates to {@code InvoiceService.convertFromEstimate}, so both are
 * mocked: this slice is about the HTTP contract — routing, status codes, roles
 * and the response shape — not about the conversion rules, which
 * {@code EstimateConversionTest} proves against a real database.
 *
 * Test cases
 * ----------
 * EC1  POST convert                    -> 201 with the invoice
 * EC2  POST convert unknown estimate    -> 404
 * EC3  POST convert twice              -> 409
 * EC4  POST convert, no items          -> 409
 * EC5  POST convert as MECHANIC        -> 403
 * EC6  POST convert as SERVICE_ADVISOR -> 403
 * EC7  Anonymous                       -> 401
 */
@WebMvcTest(controllers = {EstimateController.class})
@Import({EstimateConversionControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class EstimateConversionControllerTest {

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

    private static final String CONVERT = "/api/v1/estimates/42/convert";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean EstimateService          estimateService;
    @MockBean InvoiceService           invoiceService;
    @MockBean JwtAuthenticationFilter  jwtAuthenticationFilter;
    @MockBean JwtTokenProvider         jwtTokenProvider;
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
            org.mockito.ArgumentMatchers.any(HttpServletRequest.class),
            org.mockito.ArgumentMatchers.any(HttpServletResponse.class),
            org.mockito.ArgumentMatchers.any(FilterChain.class));
    }

    private InvoiceResponseDTO convertedInvoice() {
        InvoiceResponseDTO dto = new InvoiceResponseDTO();
        dto.setId(900L);
        dto.setJobCardId(55L);
        dto.setEstimateId(42L);
        dto.setCustomerName("Priya Sharma");
        dto.setSubtotal(new BigDecimal("1500.00"));
        dto.setDiscount(BigDecimal.ZERO);
        dto.setGst(new BigDecimal("270.00"));
        dto.setTotal(new BigDecimal("1770.00"));
        dto.setStatus("PENDING");
        dto.setItems(List.of(
                item(1L, "Brake pads", 2, "500.00", "1000.00", BillingItemCategory.PART),
                item(2L, "Replace brake pads", 1, "500.00", "500.00", BillingItemCategory.LABOUR)));
        return dto;
    }

    private InvoiceItemResponseDTO item(Long id, String description, int qty, String unitPrice,
                                       String lineAmount, BillingItemCategory category) {
        InvoiceItemResponseDTO dto = new InvoiceItemResponseDTO();
        dto.setId(id);
        dto.setDescription(description);
        dto.setQuantity(qty);
        dto.setUnitPrice(new BigDecimal(unitPrice));
        dto.setLineAmount(new BigDecimal(lineAmount));
        dto.setCategory(category);
        return dto;
    }

// ── EC1..EC4: the endpoint ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("EC1 - POST convert -> 201 with the invoice it produced")
    void ec1_convert_returns201() throws Exception {
        when(estimateService.convertToInvoice(42L)).thenReturn(convertedInvoice());

        mockMvc.perform(post(CONVERT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Converted to invoice"))
                .andExpect(jsonPath("$.data.id").value(900))
                .andExpect(jsonPath("$.data.estimateId").value(42))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.subtotal").value(1500.00))
                .andExpect(jsonPath("$.data.gst").value(270.00))
                .andExpect(jsonPath("$.data.total").value(1770.00))
                .andExpect(jsonPath("$.data.items[0].category").value("PART"))
                .andExpect(jsonPath("$.data.items[1].category").value("LABOUR"));

        verify(estimateService).convertToInvoice(42L);
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("EC2 - POST convert on an unknown estimate -> 404")
    void ec2_convertUnknown_returns404() throws Exception {
        when(estimateService.convertToInvoice(anyLong()))
                .thenThrow(new ResourceNotFoundException("Estimate not found: 42"));

        mockMvc.perform(post(CONVERT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("EC3 - converting twice -> 409")
    void ec3_convertTwice_returns409() throws Exception {
        when(estimateService.convertToInvoice(anyLong()))
                .thenThrow(new BusinessRuleException(
                        "Estimate 42 has already been converted to an invoice and cannot be "
                        + "converted again."));

        mockMvc.perform(post(CONVERT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("EC4 - converting an estimate with no lines -> 409")
    void ec4_convertNoItems_returns409() throws Exception {
        when(estimateService.convertToInvoice(anyLong()))
                .thenThrow(new BusinessRuleException(
                        "Estimate 42 has no line items and cannot be converted."));

        mockMvc.perform(post(CONVERT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

// ── EC5..EC7: authorisation ─────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("EC5 - converting as MECHANIC -> 403")
    void ec5_convertAsMechanic_returns403() throws Exception {
        mockMvc.perform(post(CONVERT)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("EC6 - converting as SERVICE_ADVISOR -> 403 (billing roles only)")
    void ec6_convertAsServiceAdvisor_returns403() throws Exception {
        mockMvc.perform(post(CONVERT)).andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("EC7 - anonymous request -> 401")
    void ec7_anonymous_returns401() throws Exception {
        mockMvc.perform(post(CONVERT)).andExpect(status().isUnauthorized());
    }
}