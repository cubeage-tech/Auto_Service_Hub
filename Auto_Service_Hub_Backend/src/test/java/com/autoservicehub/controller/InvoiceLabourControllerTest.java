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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the labour-to-invoice endpoint (FR-BILL-2).
 *
 * <p>Uses {@code @WebMvcTest} with the project's minimal inner security
 * configuration, matching {@code StockMovementControllerTest}.
 *
 * Test cases
 * ----------
 * LC1  POST labour                   -> 201 with the LABOUR line and recalculated totals
 * LC2  POST labour, no task id       -> 400
 * LC3  POST labour, unknown invoice  -> 404
 * LC4  POST labour, wrong job card   -> 409
 * LC5  POST labour, already billed   -> 409
 * LC6  POST labour to a PAID invoice -> 409
 * LC7  GET invoice shows category and jobTaskId
 * LC8  POST labour as MECHANIC       -> 403
 * LC9  Anonymous                     -> 401
 */
@WebMvcTest(controllers = {InvoiceController.class})
@Import({InvoiceLabourControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class InvoiceLabourControllerTest {

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

    private static final String INVOICE = "/api/v1/invoices/42";
    private static final String LABOUR  = "/api/v1/invoices/42/labour";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean InvoiceService           invoiceService;
    @MockBean com.autoservicehub.service.InvoiceDocumentService invoiceDocumentService;
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
            any(HttpServletRequest.class),
            any(HttpServletResponse.class),
            any(FilterChain.class));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private InvoiceResponseDTO invoiceResponse() {
        InvoiceResponseDTO dto = new InvoiceResponseDTO();
        dto.setId(42L);
        dto.setJobCardId(55L);
        dto.setCustomerName("Priya Sharma");
        dto.setVehicleInfo("MH-12-AB-1234 | Swift VXI");
        dto.setSubtotal(new BigDecimal("1500.00"));
        dto.setDiscount(BigDecimal.ZERO);
        dto.setGst(new BigDecimal("270.00"));
        dto.setTotal(new BigDecimal("1770.00"));
        dto.setStatus("PENDING");
        dto.setItems(List.of(
                item(1L, "Brake pads", 2, "500.00", "1000.00", BillingItemCategory.PART, null),
                item(2L, "Replace front brake pads", 1, "500.00", "500.00",
                        BillingItemCategory.LABOUR, 7L)));
        return dto;
    }

    private InvoiceItemResponseDTO item(Long id, String description, int qty, String unitPrice,
                                       String lineAmount, BillingItemCategory category,
                                       Long jobTaskId) {
        InvoiceItemResponseDTO dto = new InvoiceItemResponseDTO();
        dto.setId(id);
        dto.setDescription(description);
        dto.setQuantity(qty);
        dto.setUnitPrice(new BigDecimal(unitPrice));
        dto.setLineAmount(new BigDecimal(lineAmount));
        dto.setCategory(category);
        dto.setJobTaskId(jobTaskId);
        return dto;
    }

    // ── LC1..LC4: the endpoint ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("LC1 - POST labour -> 201 with the LABOUR line and recalculated totals")
    void lc1_addLabour_returns201() throws Exception {
        when(invoiceService.addLabourItem(anyLong(), any())).thenReturn(invoiceResponse());

        mockMvc.perform(post(LABOUR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"jobTaskId\": 7 }"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Labour added"))
                .andExpect(jsonPath("$.data.subtotal").value(1500.00))
                .andExpect(jsonPath("$.data.gst").value(270.00))
                .andExpect(jsonPath("$.data.total").value(1770.00))
                .andExpect(jsonPath("$.data.items[0].category").value("PART"))
                .andExpect(jsonPath("$.data.items[1].category").value("LABOUR"))
                .andExpect(jsonPath("$.data.items[1].jobTaskId").value(7))
                .andExpect(jsonPath("$.data.items[1].lineAmount").value(500.00));

        verify(invoiceService).addLabourItem(org.mockito.ArgumentMatchers.eq(42L), any());
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("LC2 - POST labour with no jobTaskId -> 400")
    void lc2_noJobTaskId_returns400() throws Exception {
        mockMvc.perform(post(LABOUR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("LC3 - POST labour to an unknown invoice -> 404")
    void lc3_unknownInvoice_returns404() throws Exception {
        when(invoiceService.addLabourItem(anyLong(), any()))
                .thenThrow(new ResourceNotFoundException("Invoice not found: 42"));

        mockMvc.perform(post(LABOUR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"jobTaskId\": 7 }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("LC4 - POST labour from another job card -> 409")
    void lc4_wrongJobCard_returns409() throws Exception {
        when(invoiceService.addLabourItem(anyLong(), any()))
                .thenThrow(new BusinessRuleException(
                        "JobTask 7 belongs to job card 99 and cannot be billed on invoice 42, "
                        + "which bills job card 55."));

        mockMvc.perform(post(LABOUR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"jobTaskId\": 7 }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    // ── LC5..LC7: the remaining guards and the read side ───────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("LC5 - POST the same labour twice -> 409")
    void lc5_duplicate_returns409() throws Exception {
        when(invoiceService.addLabourItem(anyLong(), any()))
                .thenThrow(new BusinessRuleException(
                        "JobTask 7 has already been billed on invoice 42."));

        mockMvc.perform(post(LABOUR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"jobTaskId\": 7 }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("LC6 - POST labour to a PAID invoice -> 409")
    void lc6_paidInvoice_returns409() throws Exception {
        when(invoiceService.addLabourItem(anyLong(), any()))
                .thenThrow(new BusinessRuleException(
                        "Invoice 42 is already PAID and can no longer be modified or deleted."));

        mockMvc.perform(post(LABOUR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"jobTaskId\": 7 }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("LC7 - GET an invoice reports each item's category and task")
    void lc7_getInvoice_reportsCategories() throws Exception {
        when(invoiceService.getById(42L)).thenReturn(invoiceResponse());

        mockMvc.perform(get(INVOICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].category").value("PART"))
                .andExpect(jsonPath("$.data.items[0].jobTaskId").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].category").value("LABOUR"))
                .andExpect(jsonPath("$.data.items[1].jobTaskId").value(7));
    }

    // ── LC8..LC9: authorisation ─────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("LC8 - POST labour as MECHANIC -> 403")
    void lc8_addLabourAsMechanic_returns403() throws Exception {
        mockMvc.perform(post(LABOUR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"jobTaskId\": 7 }"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("LC9 - anonymous request -> 401")
    void lc9_anonymous_returns401() throws Exception {
        mockMvc.perform(post(LABOUR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"jobTaskId\": 7 }"))
                .andExpect(status().isUnauthorized());
    }
}