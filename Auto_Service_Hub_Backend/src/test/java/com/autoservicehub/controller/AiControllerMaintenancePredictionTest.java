package com.autoservicehub.controller;

import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.dto.MaintenancePredictionRequestDTO;
import com.autoservicehub.dto.MaintenancePredictionResponseDTO;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.DamageDetectionService;
import com.autoservicehub.service.MaintenancePredictionService;
import com.autoservicehub.service.MechanicAssignmentService;
import com.autoservicehub.service.RepairCostEstimationService;
import com.autoservicehub.service.SparePartsPredictionService;
import com.autoservicehub.service.VehicleDiagnosisService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for {@code POST /api/v1/ai/maintenance-prediction}
 * (SRS 5.3, FR-AI-09..12, BR-09).
 *
 * <p>Test cases: CT1/CT2 valid requests, CT3 missing vehicleId → 400,
 * CT4 non-positive vehicleId → 400, CT5 anonymous → 401,
 * CT6 INVENTORY_MANAGER → 403, CT7 CUSTOMER → 403, CT8 vehicle not found → 404,
 * CT9 provider unavailable → 200 with no fabricated items,
 * CT10 no API key or provider internals leaked in the response body.
 */
@WebMvcTest(controllers = AiController.class)
@Import({AiControllerMaintenancePredictionTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class AiControllerMaintenancePredictionTest {

    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((req, res, e) ->
                        res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                    .accessDeniedHandler((req, res, e) ->
                        res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }
    }

    private static final String URL = "/api/v1/ai/maintenance-prediction";

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean MaintenancePredictionService maintenancePredictionService;
    // AiController also serves Mechanic Assignment (FR-AI-17..20); its
    // dependency must be mocked for the slice context to build.
    @MockBean MechanicAssignmentService    mechanicAssignmentService;
    @MockBean DamageDetectionService       damageDetectionService;
    @MockBean SparePartsPredictionService sparePartsPredictionService;
    @MockBean VehicleDiagnosisService         vehicleDiagnosisService;
    @MockBean RepairCostEstimationService     repairCostEstimationService;
    @MockBean AiOrchestrationService          aiOrchestrationService;
    @MockBean JwtAuthenticationFilter         jwtAuthenticationFilter;
    @MockBean JwtTokenProvider                jwtTokenProvider;
    @MockBean CustomUserDetailsService        customUserDetailsService;

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

    private MaintenancePredictionRequestDTO validBody() {
        MaintenancePredictionRequestDTO req = new MaintenancePredictionRequestDTO();
        req.setVehicleId(3L);
        return req;
    }

    private MaintenancePredictionResponseDTO successResponse() {
        return MaintenancePredictionResponseDTO.builder()
                .vehicleId(3L)
                .summary("Routine service due soon")
                .predictedItems(List.of(new MaintenancePredictionResponseDTO.MaintenanceItemDTO(
                        "Engine oil and filter change", "MEDIUM", 55000, null, "Due soon")))
                .confidence(new BigDecimal("0.78"))
                .humanReviewRequired(true)
                .providerUnavailable(false)
                .dataLimitations(List.of("The system has no maintenance schedule or service-interval table."))
                .serviceHistorySummary(
                        new MaintenancePredictionResponseDTO.ServiceHistorySummaryDTO(3, 3,
                                java.time.LocalDateTime.of(2025, 11, 2, 10, 0), 42000))
                .disclaimer("This maintenance prediction is AI-generated and is advisory only. It is NOT a "
                        + "service schedule and NOT a safety instruction.")
                .build();
    }

    // ── CT1/CT2: Valid requests ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT1 — valid request, SERVICE_ADVISOR → 200, humanReviewRequired=true")
    void ct1_validRequest_serviceAdvisor_returns200() throws Exception {
        when(maintenancePredictionService.predict(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicleId").value(3))
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.providerUnavailable").value(false))
                .andExpect(jsonPath("$.data.confidence").value(0.78))
                .andExpect(jsonPath("$.data.predictedItems.length()").value(1))
                .andExpect(jsonPath("$.data.predictedItems[0].priority").value("MEDIUM"))
                .andExpect(jsonPath("$.data.serviceHistorySummary.completedJobCards").value(3))
                .andExpect(jsonPath("$.data.disclaimer")
                        .value(org.hamcrest.Matchers.containsString("NOT a service schedule")));
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT2 — valid request, MECHANIC → 200")
    void ct2_validRequest_mechanic_returns200() throws Exception {
        when(maintenancePredictionService.predict(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("Routine service due soon"));
    }

    // ── CT3/CT4: Validation → 400 ────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT3 — missing vehicleId → 400 VALIDATION_ERROR")
    void ct3_missingVehicleId_returns400() throws Exception {
        MaintenancePredictionRequestDTO req = new MaintenancePredictionRequestDTO();

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT4 — non-positive vehicleId → 400 VALIDATION_ERROR")
    void ct4_nonPositiveVehicleId_returns400() throws Exception {
        MaintenancePredictionRequestDTO req = validBody();
        req.setVehicleId(0L);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── CT5: Anonymous → 401 ─────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("CT5 — anonymous request → 401")
    void ct5_anonymous_returns401() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isUnauthorized());
    }

    // ── CT6/CT7: Insufficient role → 403 ─────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT6 — INVENTORY_MANAGER role is not permitted → 403")
    void ct6_inventoryManager_returns403() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("CT7 — CUSTOMER role is not permitted → 403")
    void ct7_customerRole_returns403() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isForbidden());
    }

    // ── CT8: Vehicle not found → 404 ──────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT8 — vehicle not found → 404 NOT_FOUND")
    void ct8_vehicleNotFound_returns404() throws Exception {
        when(maintenancePredictionService.predict(any()))
                .thenThrow(new ResourceNotFoundException("Vehicle not found: 99"));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── CT9: Provider unavailable → 200, nothing fabricated ──────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("CT9 — providerUnavailable → 200, empty items, no summary fabricated")
    void ct9_providerUnavailable_noFabrication() throws Exception {
        MaintenancePredictionResponseDTO unavailable = MaintenancePredictionResponseDTO.builder()
                .vehicleId(3L)
                .summary(null)
                .predictedItems(List.of())
                .confidence(null)
                .humanReviewRequired(true)
                .providerUnavailable(true)
                .dataLimitations(List.of("The AI provider is unavailable, so no maintenance prediction "
                        + "could be produced. Please perform a manual inspection."))
                .serviceHistorySummary(
                        new MaintenancePredictionResponseDTO.ServiceHistorySummaryDTO(2, 1, null, 42000))
                .disclaimer("This maintenance prediction is AI-generated and is advisory only.")
                .build();
        when(maintenancePredictionService.predict(any())).thenReturn(unavailable);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerUnavailable").value(true))
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.predictedItems.length()").value(0))
                .andExpect(jsonPath("$.data.summary").doesNotExist())
                .andExpect(jsonPath("$.data.confidence").doesNotExist())
                // History is still factual even with no provider.
                .andExpect(jsonPath("$.data.serviceHistorySummary.totalJobCards").value(2));
    }

    // ── CT10: No sensitive information leakage ────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("CT10 — response body leaks no API key, token or provider internals")
    void ct10_noSensitiveInformationLeakage() throws Exception {
        when(maintenancePredictionService.predict(any()))
                .thenThrow(new RuntimeException("Connection failed for key sk-live-TOPSECRET"));

        String body = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("sk-live-TOPSECRET")
                         .doesNotContain("Connection failed");
    }
}
