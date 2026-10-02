package com.autoservicehub.controller;

import com.autoservicehub.dto.PurchaseResponseDTO;
import com.autoservicehub.dto.SupplierResponseDTO;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.PurchaseService;
import com.autoservicehub.service.SupplierService;
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
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the supplier and purchase endpoints.
 *
 * <p>Uses {@code @WebMvcTest} with the project's minimal inner security
 * configuration (method security on, JWT filter not registered), matching
 * {@code StockMovementControllerTest}.
 *
 * Test cases
 * ----------
 * CT1  POST supplier          -> 201
 * CT2  POST supplier, no name -> 400 VALIDATION_ERROR
 * CT3  POST supplier, bad email -> 400 VALIDATION_ERROR
 * CT4  POST duplicate supplier -> 409
 * CT5  GET supplier           -> 200
 * CT6  GET unknown supplier   -> 404
 * CT7  PUT supplier           -> 200
 * CT8  DELETE supplier        -> 204
 * CT9  DELETE with history    -> 409
 * CT10 POST purchase          -> 201 with the server-side total
 * CT11 POST purchase, zero quantity -> 400
 * CT12 POST purchase, negative price -> 400
 * CT13 POST purchase, no items -> 400
 * CT14 POST purchase, unknown supplier -> 404
 * CT15 GET purchase           -> 200
 * CT16 POST receive           -> 200 RECEIVED
 * CT17 POST receive twice     -> 409
 * CT18 POST receive unknown   -> 404
 * CT19 POST purchase by MECHANIC -> 403
 * CT20 Anonymous              -> 401
 */
@WebMvcTest(controllers = {SupplierController.class, PurchaseController.class})
@Import({PurchasingControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class PurchasingControllerTest {

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

    private static final String SUPPLIERS  = "/api/v1/suppliers";
    private static final String SUPPLIER_1 = "/api/v1/suppliers/1";
    private static final String PURCHASES  = "/api/v1/purchases";
    private static final String RECEIVE_10 = "/api/v1/purchases/10/receive";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean SupplierService          supplierService;
    @MockBean PurchaseService          purchaseService;
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

    private SupplierResponseDTO supplierResponse() {
        SupplierResponseDTO dto = new SupplierResponseDTO();
        dto.setId(1L);
        dto.setName("Bharat Auto");
        dto.setPhone("+91 98000 00000");
        dto.setEmail("sales@bharatauto.example");
        dto.setAddress("Industrial Estate");
        return dto;
    }

    private PurchaseResponseDTO purchaseResponse(String status) {
        PurchaseResponseDTO dto = new PurchaseResponseDTO();
        dto.setId(10L);
        dto.setSupplierId(1L);
        dto.setSupplierName("Bharat Auto");
        dto.setPurchaseDate(LocalDate.of(2026, 1, 15));
        dto.setTotalAmount(new BigDecimal("1102.00"));
        dto.setStatus(status);
        dto.setItems(List.of());
        return dto;
    }

    private String supplierJson() {
        return """
                {
                  "name": "Bharat Auto",
                  "phone": "+91 98000 00000",
                  "email": "sales@bharatauto.example",
                  "address": "Industrial Estate"
                }
                """;
    }

    private String purchaseJson() {
        return """
                {
                  "supplierId": 1,
                  "items": [
                    { "partId": 1, "quantity": 2, "unitPrice": 300.00 },
                    { "partId": 2, "quantity": 4, "unitPrice": 125.50 }
                  ]
                }
                """;
    }

    // ── Supplier CRUD ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT1 - POST a supplier -> 201")
    void ct1_createSupplier_returns201() throws Exception {
        when(supplierService.create(any())).thenReturn(supplierResponse());

        mockMvc.perform(post(SUPPLIERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("Bharat Auto"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT2 - POST a supplier with no name -> 400 VALIDATION_ERROR")
    void ct2_createSupplierNoName_returns400() throws Exception {
        mockMvc.perform(post(SUPPLIERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"phone\": \"+91 98000 00000\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT3 - POST a supplier with a malformed email -> 400 VALIDATION_ERROR")
    void ct3_createSupplierBadEmail_returns400() throws Exception {
        mockMvc.perform(post(SUPPLIERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"Bharat Auto\", \"email\": \"not-an-email\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT4 - POST a duplicate supplier name -> 409")
    void ct4_createDuplicateSupplier_returns409() throws Exception {
        when(supplierService.create(any()))
                .thenThrow(new BusinessRuleException("A supplier named 'Bharat Auto' already exists."));

        mockMvc.perform(post(SUPPLIERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT5 - GET a supplier -> 200")
    void ct5_getSupplier_returns200() throws Exception {
        when(supplierService.getById(1L)).thenReturn(supplierResponse());

        mockMvc.perform(get(SUPPLIER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Bharat Auto"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT6 - GET an unknown supplier -> 404")
    void ct6_getUnknownSupplier_returns404() throws Exception {
        when(supplierService.getById(99L))
                .thenThrow(new ResourceNotFoundException("Supplier not found: 99"));

        mockMvc.perform(get("/api/v1/suppliers/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT7 - PUT a supplier -> 200")
    void ct7_updateSupplier_returns200() throws Exception {
        when(supplierService.update(anyLong(), any())).thenReturn(supplierResponse());

        mockMvc.perform(put(SUPPLIER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));

        verify(supplierService).update(org.mockito.ArgumentMatchers.eq(1L), any());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("CT8 - DELETE a supplier -> 204")
    void ct8_deleteSupplier_returns204() throws Exception {
        mockMvc.perform(delete(SUPPLIER_1)).andExpect(status().isNoContent());

        verify(supplierService).delete(1L);
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("CT9 - DELETE a supplier with purchase history -> 409")
    void ct9_deleteSupplierWithHistory_returns409() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessRuleException(
                        "Supplier Bharat Auto cannot be deleted because it has purchase history."))
                .when(supplierService).delete(1L);

        mockMvc.perform(delete(SUPPLIER_1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT9b - DELETE a supplier is refused for INVENTORY_MANAGER -> 403")
    void ct9b_deleteSupplierAsInventoryManager_returns403() throws Exception {
        mockMvc.perform(delete(SUPPLIER_1)).andExpect(status().isForbidden());
    }

    // ── Purchase creation ──────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT10 - POST a purchase -> 201 with the server-calculated total")
    void ct10_createPurchase_returns201() throws Exception {
        when(purchaseService.create(any()))
                .thenReturn(purchaseResponse("PENDING"));

        mockMvc.perform(post(PURCHASES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.supplierId").value(1))
                .andExpect(jsonPath("$.data.totalAmount").value(1102.00))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT11 - POST a purchase with a zero quantity -> 400")
    void ct11_createPurchaseZeroQuantity_returns400() throws Exception {
        mockMvc.perform(post(PURCHASES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"supplierId\": 1, \"items\": [ { \"partId\": 1, \"quantity\": 0, \"unitPrice\": 300.00 } ] }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT12 - POST a purchase with a negative unit price -> 400")
    void ct12_createPurchaseNegativePrice_returns400() throws Exception {
        mockMvc.perform(post(PURCHASES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"supplierId\": 1, \"items\": [ { \"partId\": 1, \"quantity\": 2, \"unitPrice\": -5.00 } ] }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT13 - POST a purchase with no items -> 400")
    void ct13_createPurchaseNoItems_returns400() throws Exception {
        mockMvc.perform(post(PURCHASES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"supplierId\": 1, \"items\": [] }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT14 - POST a purchase for an unknown supplier -> 404")
    void ct14_createPurchaseUnknownSupplier_returns404() throws Exception {
        when(purchaseService.create(any()))
                .thenThrow(new ResourceNotFoundException("Supplier not found: 1"));

        mockMvc.perform(post(PURCHASES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT15 - GET a purchase -> 200")
    void ct15_getPurchase_returns200() throws Exception {
        when(purchaseService.getById(10L)).thenReturn(purchaseResponse("RECEIVED"));

        mockMvc.perform(get("/api/v1/purchases/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));
    }

    // ── Receiving ──────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT16 - POST receive -> 200 with the purchase RECEIVED")
    void ct16_receive_returns200() throws Exception {
        when(purchaseService.receive(10L)).thenReturn(purchaseResponse("RECEIVED"));

        mockMvc.perform(post(RECEIVE_10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Received"))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));

        verify(purchaseService).receive(10L);
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT17 - receiving an already-received purchase -> 409")
    void ct17_receiveTwice_returns409() throws Exception {
        when(purchaseService.receive(10L))
                .thenThrow(new BusinessRuleException(
                        "Purchase 10 has already been received and cannot be received again."));

        mockMvc.perform(post(RECEIVE_10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT18 - receiving an unknown purchase -> 404")
    void ct18_receiveUnknown_returns404() throws Exception {
        when(purchaseService.receive(999L))
                .thenThrow(new ResourceNotFoundException("Purchase not found: 999"));

        mockMvc.perform(post("/api/v1/purchases/999/receive"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── Authorisation ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT19 - creating a purchase as MECHANIC -> 403")
    void ct19_createPurchaseAsMechanic_returns403() throws Exception {
        mockMvc.perform(post(PURCHASES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT19b - reading purchases as MECHANIC -> 200")
    void ct19b_readPurchasesAsMechanic_returns200() throws Exception {
        when(purchaseService.list(any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get(PURCHASES)).andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("CT20 - anonymous request -> 401")
    void ct20_anonymous_returns401() throws Exception {
        mockMvc.perform(get(SUPPLIERS)).andExpect(status().isUnauthorized());
    }

    // ── Supplier search and purchase history ────────────────────────────

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT21 - a supplier's purchase count is reported on its detail")
    void ct21_supplierReportsPurchaseCount() throws Exception {
        SupplierResponseDTO withHistory = supplierResponse();
        withHistory.setPurchaseCount(3);
        when(supplierService.getById(1L)).thenReturn(withHistory);

        mockMvc.perform(get(SUPPLIER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purchaseCount").value(3));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT22 - supplier search passes the term to the service -> 200")
    void ct22_search_returns200() throws Exception {
        when(supplierService.search(org.mockito.ArgumentMatchers.eq("bharat"), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/suppliers/search").param("q", "bharat"))
                .andExpect(status().isOk());

        verify(supplierService).search(org.mockito.ArgumentMatchers.eq("bharat"), any());
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT23 - supplier purchase history is reachable from the supplier -> 200")
    void ct23_purchaseHistory_returns200() throws Exception {
        when(supplierService.purchaseHistory(org.mockito.ArgumentMatchers.eq(1L), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/suppliers/1/purchases"))
                .andExpect(status().isOk());

        verify(supplierService).purchaseHistory(org.mockito.ArgumentMatchers.eq(1L), any());
    }

    /**
     * Search carries the same read roles as the supplier list.
     *
     * <p>Asserted as 200 rather than 403 on purpose: search only filters the list a
     * mechanic can already read, so refusing it while allowing the unfiltered list
     * would be theatre. Confirmed here so the role set cannot drift silently.
     */
    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT24 - supplier search uses the same read roles as the list -> 200")
    void ct24_searchAsMechanic_returns200() throws Exception {
        when(supplierService.search(org.mockito.ArgumentMatchers.eq("bharat"), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/suppliers/search").param("q", "bharat"))
                .andExpect(status().isOk());
    }
}
