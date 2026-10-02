package com.autoservicehub.controller;

import com.autoservicehub.dto.PartConsumptionRequestDTO;
import com.autoservicehub.dto.StockMovementRequestDTO;
import com.autoservicehub.dto.StockMovementResponseDTO;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.JobCardService;
import com.autoservicehub.service.JobTaskService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the inventory stock-movement endpoints on
 * {@code PartController} and {@code JobCardController}.
 *
 * Uses {@code @WebMvcTest} with the project's minimal inner security
 * configuration (method security on, JWT filter not registered).
 *
 * Test cases
 * ----------
 * CT1  POST IN movement        → 201, movement returned
 * CT2  POST OUT movement       → 201
 * CT3  POST missing quantity   → 400 VALIDATION_ERROR
 * CT4  POST zero quantity      → 400 VALIDATION_ERROR
 * CT5  POST negative quantity  → 400 VALIDATION_ERROR
 * CT6  POST unknown part       → 404
 * CT7  POST insufficient stock → 409
 * CT8  POST by MECHANIC        → 403 (inventory roles only)
 * CT9  GET movement history    → 200
 * CT10 GET low-stock list      → 200
 * CT11 Consume part for job card → 201
 * CT12 Consumption insufficient stock → 409
 * CT13 Consumption unknown part → 404
 * CT14 Consumption zero quantity → 400
 * CT15 Anonymous               → 401
 */
@WebMvcTest(controllers = {PartController.class, JobCardController.class})
@Import({StockMovementControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class StockMovementControllerTest {

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

    private static final String MOVEMENTS = "/api/v1/parts/1/stock-movements";
    private static final String CONSUME   = "/api/v1/job-cards/55/parts";
    private static final String LOW_STOCK = "/api/v1/parts/low-stock";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean PartService          partService;
    @MockBean StockMovementService stockMovementService;
    @MockBean JobCardService       jobCardService;
    // JobCardController also serves the repair-task endpoints, so its service
    // is a constructor dependency of that controller and must be mocked here
    // too. This slice is about stock movements, not tasks.
    @MockBean JobTaskService       jobTaskService;
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

    private StockMovementResponseDTO movementResponse() {
        StockMovementResponseDTO dto = new StockMovementResponseDTO();
        dto.setId(500L);
        dto.setPartId(1L);
        dto.setPartSku("BRK-PAD-01");
        dto.setPartName("Front Brake Pad Set");
        dto.setMovementType("OUT");
        dto.setQuantity(2);
        dto.setStockBefore(10);
        dto.setStockAfter(8);
        dto.setJobCardId(55L);
        dto.setJobCardNumber("JC-20260930120000");
        return dto;
    }

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    // ── CT1 / CT2: movement creation ──────────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT1 — recording an IN movement → 201 with the resulting stock")
    void ct1_inMovement_returns201() throws Exception {
        when(stockMovementService.create(any())).thenReturn(movementResponse());

        String body = """
                { "movementType": "IN", "quantity": 2, "reason": "Goods received" }
                """;

        mockMvc.perform(post(MOVEMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.stockAfter").value(8));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT1b — the path id is used as the part, overriding any body partId")
    void ct1b_pathIdIsAuthoritative() throws Exception {
        when(stockMovementService.create(any())).thenReturn(movementResponse());

        String body = """
                { "partId": 999, "movementType": "OUT", "quantity": 1 }
                """;

        mockMvc.perform(post(MOVEMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        org.mockito.ArgumentCaptor<StockMovementRequestDTO> captor =
                org.mockito.ArgumentCaptor.forClass(StockMovementRequestDTO.class);
        verify(stockMovementService).create(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPartId()).isEqualTo(1L);
    }

    // ── CT3 / CT4 / CT5: validation → 400 ─────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT3 — missing quantity → 400 VALIDATION_ERROR")
    void ct3_missingQuantity_returns400() throws Exception {
        mockMvc.perform(post(MOVEMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"movementType\": \"IN\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT4 — zero quantity → 400 VALIDATION_ERROR")
    void ct4_zeroQuantity_returns400() throws Exception {
        mockMvc.perform(post(MOVEMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"movementType\": \"OUT\", \"quantity\": 0 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT5 — negative quantity → 400 VALIDATION_ERROR")
    void ct5_negativeQuantity_returns400() throws Exception {
        mockMvc.perform(post(MOVEMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"movementType\": \"OUT\", \"quantity\": -2 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── CT6 / CT7: error mapping ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT6 — unknown part → 404 NOT_FOUND")
    void ct6_unknownPart_returns404() throws Exception {
        when(stockMovementService.create(any()))
                .thenThrow(new ResourceNotFoundException("Part not found: 1"));

        mockMvc.perform(post(MOVEMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"movementType\": \"IN\", \"quantity\": 1 }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT7 — insufficient stock → 409 BUSINESS_RULE_VIOLATION")
    void ct7_insufficientStock_returns409() throws Exception {
        when(stockMovementService.create(any())).thenThrow(new BusinessRuleException(
                "Insufficient stock for part BRK-PAD-01: requested 5 but only 2 on hand."));

        mockMvc.perform(post(MOVEMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"movementType\": \"OUT\", \"quantity\": 5 }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    // ── CT8: role guard ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT8 — MECHANIC may consume parts but not record stock movements → 403")
    void ct8_mechanicCannotRecordMovement_returns403() throws Exception {
        mockMvc.perform(post(MOVEMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"movementType\": \"IN\", \"quantity\": 1 }"))
                .andExpect(status().isForbidden());
    }

    // ── CT9 / CT10: read endpoints ────────────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT9 — movement history for a part → 200")
    void ct9_movementHistory_returns200() throws Exception {
        when(stockMovementService.listByPart(anyLong(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(movementResponse()),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mockMvc.perform(get(MOVEMENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].movementType").value("OUT"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT10 — low-stock part list → 200")
    void ct10_lowStockList_returns200() throws Exception {
        when(partService.listLowStock(any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get(LOW_STOCK))
                .andExpect(status().isOk());
    }

    // ── CT11..CT14: job-card consumption ──────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT11 — consuming a part for a job card → 201")
    void ct11_consumePart_returns201() throws Exception {
        when(stockMovementService.consumeForJobCard(anyLong(), anyLong(), any(), any()))
                .thenReturn(movementResponse());

        mockMvc.perform(post(CONSUME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"partId\": 1, \"quantity\": 2 }"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobCardId").value(55))
                .andExpect(jsonPath("$.data.movementType").value("OUT"));
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT12 — consumption with insufficient stock → 409")
    void ct12_consumeInsufficientStock_returns409() throws Exception {
        when(stockMovementService.consumeForJobCard(anyLong(), anyLong(), any(), any()))
                .thenThrow(new BusinessRuleException(
                        "Insufficient stock for part BRK-PAD-01: requested 5 but only 2 on hand."));

        mockMvc.perform(post(CONSUME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"partId\": 1, \"quantity\": 5 }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT13 — consumption of an unknown part → 404")
    void ct13_consumeUnknownPart_returns404() throws Exception {
        when(stockMovementService.consumeForJobCard(anyLong(), anyLong(), any(), any()))
                .thenThrow(new ResourceNotFoundException("Part not found: 1"));

        mockMvc.perform(post(CONSUME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"partId\": 1, \"quantity\": 1 }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT14 — consumption with a zero quantity → 400")
    void ct14_consumeZeroQuantity_returns400() throws Exception {
        mockMvc.perform(post(CONSUME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"partId\": 1, \"quantity\": 0 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── CT15: authentication ──────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("CT15 — anonymous request → 401")
    void ct15_anonymous_returns401() throws Exception {
        mockMvc.perform(get(MOVEMENTS)).andExpect(status().isUnauthorized());
    }
}
