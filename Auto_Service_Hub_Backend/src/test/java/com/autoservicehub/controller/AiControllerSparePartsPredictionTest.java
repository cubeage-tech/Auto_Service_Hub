package com.autoservicehub.controller;

import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.dto.SparePartsPredictionRequestDTO;
import com.autoservicehub.dto.SparePartsPredictionResponseDTO;
import com.autoservicehub.exception.BusinessRuleException;
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
 * Controller slice tests for {@code POST /api/v1/ai/spare-parts-prediction}
 * (SRS 5.5, FR-AI-21..24, BR-09).
 *
 * <p>Test cases: CT1/CT2 valid requests, CT3 blank description → 400,
 * CT4 job card not found → 404, CT5 part not found → 404, CT6 business rule → 409,
 * CT7 anonymous → 401, CT8 MECHANIC → 403, CT9 CUSTOMER → 403,
 * CT10 no prediction → 200, CT11 inventoryModified always false, CT12 no leak.
 */
@WebMvcTest(controllers = AiController.class)
@Import({AiControllerSparePartsPredictionTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class AiControllerSparePartsPredictionTest {

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

    private static final String URL = "/api/v1/ai/spare-parts-prediction";

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean DamageDetectionService       damageDetectionService;
    @MockBean SparePartsPredictionService  sparePartsPredictionService;
    @MockBean MechanicAssignmentService    mechanicAssignmentService;
    @MockBean MaintenancePredictionService maintenancePredictionService;
    @MockBean VehicleDiagnosisService      vehicleDiagnosisService;
    @MockBean RepairCostEstimationService  repairCostEstimationService;
    @MockBean AiOrchestrationService       aiOrchestrationService;
    @MockBean JwtAuthenticationFilter      jwtAuthenticationFilter;
    @MockBean JwtTokenProvider             jwtTokenProvider;
    @MockBean CustomUserDetailsService     customUserDetailsService;

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

    private SparePartsPredictionRequestDTO validBody() {
        SparePartsPredictionRequestDTO req = new SparePartsPredictionRequestDTO();
        req.setServiceDescription("Front brake disc and pad replacement");
        return req;
    }

    private SparePartsPredictionResponseDTO successResponse() {
        return SparePartsPredictionResponseDTO.builder()
                .summary("Brake pads and a disc are typically required")
                .predictedParts(List.of(new SparePartsPredictionResponseDTO.PredictedPartDTO(
                        4L, "Front brake pad set", "BRK-PAD-01", 1, "set", 12, true,
                        "Pads are replaced with discs.")))
                .confidence(new BigDecimal("0.69"))
                .humanReviewRequired(true)
                .inventoryModified(false)
                .providerUnavailable(false)
                .dataLimitations(List.of("Parts are not linked to job cards, so this workshop's historical " +
                        "parts usage could not be analysed."))
                .inventorySnapshot(
                        new SparePartsPredictionResponseDTO.InventorySnapshotDTO(42, 3, 5))
                .disclaimer("This spare parts prediction is AI-generated and is advisory only. No stock has " +
                        "been reserved, deducted or ordered.")
                .build();
    }

    // ── CT1/CT2: Valid requests ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT1 — valid request, INVENTORY_MANAGER → 200, no inventory modified")
    void ct1_validRequest_inventoryManager_returns200() throws Exception {
        when(sparePartsPredictionService.predict(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.inventoryModified").value(false))
                .andExpect(jsonPath("$.data.providerUnavailable").value(false))
                .andExpect(jsonPath("$.data.confidence").value(0.69))
                .andExpect(jsonPath("$.data.predictedParts.length()").value(1))
                .andExpect(jsonPath("$.data.predictedParts[0].partId").value(4))
                .andExpect(jsonPath("$.data.predictedParts[0].partSku").value("BRK-PAD-01"))
                .andExpect(jsonPath("$.data.inventorySnapshot.catalogueSize").value(42))
                .andExpect(jsonPath("$.data.disclaimer")
                        .value(org.hamcrest.Matchers.containsString("No stock has been reserved")));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT2 — valid request, SERVICE_ADVISOR → 200")
    void ct2_validRequest_serviceAdvisor_returns200() throws Exception {
        when(sparePartsPredictionService.predict(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary")
                        .value("Brake pads and a disc are typically required"));
    }

    // ── CT3..CT6: Validation, not-found and business rules ───────────────

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT3 — blank serviceDescription → 400 VALIDATION_ERROR")
    void ct3_blankDescription_returns400() throws Exception {
        SparePartsPredictionRequestDTO req = new SparePartsPredictionRequestDTO();
        req.setServiceDescription("");

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT4 — job card not found → 404 NOT_FOUND")
    void ct4_jobCardNotFound_returns404() throws Exception {
        when(sparePartsPredictionService.predict(any()))
                .thenThrow(new ResourceNotFoundException("JobCard not found: 99"));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT5 — requested part not found → 404 NOT_FOUND")
    void ct5_partNotFound_returns404() throws Exception {
        when(sparePartsPredictionService.predict(any()))
                .thenThrow(new ResourceNotFoundException("Part not found: 77"));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT6 — business rule violation → 409")
    void ct6_businessRule_returns409() throws Exception {
        when(sparePartsPredictionService.predict(any()))
                .thenThrow(new BusinessRuleException(
                        "serviceDescription is too short to predict parts for."));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    // ── CT7/CT8/CT9: Authorization ──────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("CT7 — anonymous request → 401")
    void ct7_anonymous_returns401() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT8 — MECHANIC role is not permitted → 403")
    void ct8_mechanicRole_returns403() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("CT9 — CUSTOMER role is not permitted → 403")
    void ct9_customerRole_returns403() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isForbidden());
    }

    // ── CT10/CT11/CT12 ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("CT10 — provider unavailable → 200, no parts predicted, no fabrication")
    void ct10_providerUnavailable_noPrediction() throws Exception {
        SparePartsPredictionResponseDTO unavailable = SparePartsPredictionResponseDTO.builder()
                .summary(null)
                .predictedParts(List.of())
                .confidence(null)
                .humanReviewRequired(true)
                .inventoryModified(false)
                .providerUnavailable(true)
                .dataLimitations(List.of("The AI provider is unavailable, so no parts could be predicted."))
                .inventorySnapshot(
                        new SparePartsPredictionResponseDTO.InventorySnapshotDTO(42, 3, 5))
                .disclaimer("This spare parts prediction is AI-generated and is advisory only.")
                .build();
        when(sparePartsPredictionService.predict(any())).thenReturn(unavailable);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerUnavailable").value(true))
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.predictedParts.length()").value(0))
                .andExpect(jsonPath("$.data.summary").doesNotExist())
                .andExpect(jsonPath("$.data.confidence").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT11 — a prediction never reports a modified inventory")
    void ct11_inventoryModified_isAlwaysFalse() throws Exception {
        when(sparePartsPredictionService.predict(any())).thenReturn(successResponse());

        String body = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The response explicitly declares that no stock was touched.
        assertThat(body).contains("\"inventoryModified\":false");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("CT12 — response body leaks no API key or provider internals")
    void ct12_noSensitiveInformationLeakage() throws Exception {
        when(sparePartsPredictionService.predict(any()))
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
