package com.autoservicehub.controller;

import com.autoservicehub.dto.PartResponseDTO;
import com.autoservicehub.entity.PartCategory;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.PartService;
import com.autoservicehub.service.StockMovementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for {@link PartController}, covering the spare-part
 * fields added in V7: category, supplier, storage location and HSN code.
 *
 * <p>Uses {@code @WebMvcTest} with the project's minimal inner security
 * configuration (method security on, JWT filter not registered), matching
 * {@code StockMovementControllerTest} and {@code PurchasingControllerTest}.
 *
 * Test cases
 * ----------
 * PC1  POST part with all four new fields -> 201 and the fields echoed back
 * PC2  POST part -> the four fields reach the service exactly as sent
 * PC3  POST part with no new fields      -> 201, nulls accepted (back-compat)
 * PC4  POST part, blank selling price    -> 400 VALIDATION_ERROR
 * PC5  POST part, unknown category       -> 400 BAD_REQUEST
 * PC6  POST part, unknown supplier       -> 404 NOT_FOUND
 * PC7  GET part                          -> 200 with all four fields
 * PC8  PUT part with the four fields     -> 200 and the service is updated
 * PC9  POST as MECHANIC                  -> 403
 * PC10 Anonymous                         -> 401
 */
@WebMvcTest(controllers = PartController.class)
@Import({PartControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class PartControllerTest {

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

    private static final String PARTS = "/api/v1/parts";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean PartService          partService;
    @MockBean StockMovementService stockMovementService;
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

    private PartResponseDTO partResponse() {
        PartResponseDTO dto = new PartResponseDTO();
        dto.setId(5L);
        dto.setSku("ACF-5503");
        dto.setName("AC Filter");
        dto.setUnit("Piece");
        dto.setSellingPrice(new BigDecimal("450.00"));
        dto.setPurchasePrice(new BigDecimal("400.00"));
        dto.setStockQty(3);
        dto.setMinStock(5);
        dto.setLowStock(true);
        dto.setCategory(PartCategory.FILTERS);
        dto.setSupplierId(3L);
        dto.setSupplierName("Mahle Filters");
        dto.setStorageLocation("Rack C2");
        dto.setHsnCode("842123");
        return dto;
    }

    /** A part carrying every field the form collects. */
    private String fullPartJson() {
        return """
                {
                  "name": "AC Filter",
                  "sku": "ACF-5503",
                  "unit": "Piece",
                  "sellingPrice": 450.00,
                  "purchasePrice": 400.00,
                  "stockQty": 3,
                  "minStock": 5,
                  "category": "Filters",
                  "supplierId": 3,
                  "storageLocation": "Rack C2",
                  "hsnCode": "842123"
                }
                """;
    }

    /** The shape sent before the four fields existed. */
    private String legacyPartJson() {
        return """
                {
                  "name": "AC Filter",
                  "sku": "ACF-5503",
                  "unit": "Piece",
                  "sellingPrice": 450.00,
                  "stockQty": 3,
                  "minStock": 5
                }
                """;
    }

    // ── PC1..PC3: create ────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("PC1 — POST creates a part and echoes category, supplier, location and HSN back")
    void pc1_create_returnsNewFields() throws Exception {
        when(partService.create(any())).thenReturn(partResponse());

        mockMvc.perform(post(PARTS).contentType(MediaType.APPLICATION_JSON).content(fullPartJson()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.success").value(true))
               .andExpect(jsonPath("$.data.category").value("Filters"))
               .andExpect(jsonPath("$.data.supplierId").value(3))
               .andExpect(jsonPath("$.data.supplierName").value("Mahle Filters"))
               .andExpect(jsonPath("$.data.storageLocation").value("Rack C2"))
               .andExpect(jsonPath("$.data.hsnCode").value("842123"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("PC2 — POST hands the four new fields to the service exactly as sent")
    void pc2_create_passesNewFieldsToService() throws Exception {
        when(partService.create(any())).thenReturn(partResponse());

        mockMvc.perform(post(PARTS).contentType(MediaType.APPLICATION_JSON).content(fullPartJson()))
               .andExpect(status().isCreated());

        ArgumentCaptor<com.autoservicehub.dto.PartRequestDTO> captor =
                ArgumentCaptor.forClass(com.autoservicehub.dto.PartRequestDTO.class);
        verify(partService).create(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo(PartCategory.FILTERS);
        assertThat(captor.getValue().getSupplierId()).isEqualTo(3L);
        assertThat(captor.getValue().getStorageLocation()).isEqualTo("Rack C2");
        assertThat(captor.getValue().getHsnCode()).isEqualTo("842123");
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("PC3 — a request without the new fields still succeeds (back-compat with older clients)")
    void pc3_createWithoutNewFields_stillCreated() throws Exception {
        PartResponseDTO response = partResponse();
        response.setCategory(null);
        response.setSupplierId(null);
        response.setSupplierName(null);
        response.setStorageLocation(null);
        response.setHsnCode(null);
        when(partService.create(any())).thenReturn(response);

        mockMvc.perform(post(PARTS).contentType(MediaType.APPLICATION_JSON).content(legacyPartJson()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.data.category").doesNotExist())
               .andExpect(jsonPath("$.data.supplierId").doesNotExist())
               .andExpect(jsonPath("$.data.storageLocation").doesNotExist())
               .andExpect(jsonPath("$.data.hsnCode").doesNotExist());
    }

    // ── PC4..PC6: rejection ──────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("PC4 — a missing sellingPrice is still a 400 VALIDATION_ERROR")
    void pc4_createWithoutSellingPrice_400() throws Exception {
        mockMvc.perform(post(PARTS).contentType(MediaType.APPLICATION_JSON).content("""
                        { "name": "AC Filter", "sku": "ACF-5503", "category": "Filters" }
                        """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("PC5 — an unrecognised category is rejected with 400, not stored")
    void pc5_createWithUnknownCategory_400() throws Exception {
        mockMvc.perform(post(PARTS).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "name": "AC Filter", "sku": "ACF-5503",
                          "sellingPrice": 450.00, "category": "Nonsense"
                        }
                        """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("PC6 — an unknown supplierId surfaces the service's 404")
    void pc6_createWithUnknownSupplier_404() throws Exception {
        when(partService.create(any()))
                .thenThrow(new ResourceNotFoundException("Supplier not found: 99"));

        mockMvc.perform(post(PARTS).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "name": "AC Filter", "sku": "ACF-5503",
                          "sellingPrice": 450.00, "supplierId": 99
                        }
                        """))
               .andExpect(status().isNotFound())
               .andExpect(jsonPath("$.code").value("NOT_FOUND"))
               .andExpect(jsonPath("$.message").value("Supplier not found: 99"));
    }

    // ── PC7: read ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("PC7 — GET returns the part with all four fields")
    void pc7_getById_returnsNewFields() throws Exception {
        when(partService.getById(5L)).thenReturn(partResponse());

        mockMvc.perform(get(PARTS + "/5"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.category").value("Filters"))
               .andExpect(jsonPath("$.data.supplierId").value(3))
               .andExpect(jsonPath("$.data.supplierName").value("Mahle Filters"))
               .andExpect(jsonPath("$.data.storageLocation").value("Rack C2"))
               .andExpect(jsonPath("$.data.hsnCode").value("842123"));
    }

    // ── PC8: update ──────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("PC8 — PUT updates the part with all four fields")
    void pc8_update_appliesNewFields() throws Exception {
        when(partService.update(any(Long.class), any())).thenReturn(partResponse());

        mockMvc.perform(put(PARTS + "/5").contentType(MediaType.APPLICATION_JSON).content(fullPartJson()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.category").value("Filters"))
               .andExpect(jsonPath("$.data.supplierId").value(3))
               .andExpect(jsonPath("$.data.storageLocation").value("Rack C2"))
               .andExpect(jsonPath("$.data.hsnCode").value("842123"));

        ArgumentCaptor<com.autoservicehub.dto.PartRequestDTO> captor =
                ArgumentCaptor.forClass(com.autoservicehub.dto.PartRequestDTO.class);
        verify(partService).update(any(Long.class), captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo(PartCategory.FILTERS);
        assertThat(captor.getValue().getSupplierId()).isEqualTo(3L);
    }

    // ── PC9..PC10: authorization ─────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("PC9 — a MECHANIC cannot create a part")
    void pc9_createAsMechanic_403() throws Exception {
        mockMvc.perform(post(PARTS).contentType(MediaType.APPLICATION_JSON).content(fullPartJson()))
               .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("PC10 — an anonymous create is 401")
    void pc10_createAnonymous_401() throws Exception {
        mockMvc.perform(post(PARTS).contentType(MediaType.APPLICATION_JSON).content(fullPartJson()))
               .andExpect(status().isUnauthorized());
    }
}