package com.autoservicehub.controller;

import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.dto.RepairCostEstimationRequestDTO;
import com.autoservicehub.dto.RepairCostEstimationResponseDTO;
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
 * Controller slice tests for {@code POST /api/v1/ai/repair-cost-estimation}
 * (SRS 5.2, FR-AI-05..08, BR-09).
 *
 * <p>Mirrors AiControllerDiagnosisTest: a minimal inner security config enables
 * {@code @PreAuthorize} and requires authentication, without registering the
 * real JWT filter.
 *
 * <p>Test cases: CT1/CT2 valid requests, CT3 blank description → 400,
 * CT4 non-positive vehicleId → 400, CT5 anonymous → 401,
 * CT6 MECHANIC → 403, CT7 CUSTOMER → 403, CT8 not found → 404,
 * CT9 business rule → 409, CT10 provider unavailable → 200,
 * CT11 no provider/secret leakage in the response body.
 */
@WebMvcTest(controllers = AiController.class)
@Import({AiControllerRepairCostEstimationTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class AiControllerRepairCostEstimationTest {

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

    private static final String URL = "/api/v1/ai/repair-cost-estimation";

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean RepairCostEstimationService repairCostEstimationService;
    // AiController also serves Maintenance Prediction (FR-AI-09..12); its
    // dependency must be mocked for the slice context to build.
    @MockBean MaintenancePredictionService maintenancePredictionService;
    // AiController also serves Mechanic Assignment (FR-AI-17..20); its
    // dependency must be mocked for the slice context to build.
    @MockBean MechanicAssignmentService    mechanicAssignmentService;
    @MockBean DamageDetectionService       damageDetectionService;
    @MockBean SparePartsPredictionService sparePartsPredictionService;
    @MockBean VehicleDiagnosisService     vehicleDiagnosisService;
    @MockBean AiOrchestrationService      aiOrchestrationService;
    @MockBean JwtAuthenticationFilter     jwtAuthenticationFilter;
    @MockBean JwtTokenProvider            jwtTokenProvider;
    @MockBean CustomUserDetailsService    customUserDetailsService;

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

    private RepairCostEstimationRequestDTO validBody() {
        RepairCostEstimationRequestDTO req = new RepairCostEstimationRequestDTO();
        req.setServiceDescription("Front brake disc and pad replacement");
        return req;
    }

    private RepairCostEstimationResponseDTO successResponse() {
        return RepairCostEstimationResponseDTO.builder()
                .estimatedTotalCost(new BigDecimal("8450.00"))
                .currency("INR")
                .costBreakdown(List.of())
                .confidence(new BigDecimal("0.82"))
                .disclaimer("This cost estimate is an approximation only, NOT a guaranteed price. "
                        + "It must be reviewed by a service advisor or manager.")
                .humanReviewRequired(true)
                .providerUnavailable(false)
                .dataLimitations(List.of("The system has no labour-rate table."))
                .build();
    }

    // ── CT1: Valid request, SERVICE_ADVISOR → 200 ────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT1 — valid request, SERVICE_ADVISOR → 200, humanReviewRequired=true")
    void ct1_validRequest_serviceAdvisor_returns200() throws Exception {
        when(repairCostEstimationService.estimate(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.providerUnavailable").value(false))
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.estimatedTotalCost").value(8450.00))
                .andExpect(jsonPath("$.data.disclaimer")
                        .value(org.hamcrest.Matchers.containsString("NOT a guaranteed price")));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("CT2 — valid request, BILLING_USER → 200")
    void ct2_validRequest_billingUser_returns200() throws Exception {
        when(repairCostEstimationService.estimate(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confidence").value(0.82));
    }

    // ── CT3/CT4: Validation → 400 ────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT3 — blank serviceDescription → 400 VALIDATION_ERROR")
    void ct3_blankDescription_returns400() throws Exception {
        RepairCostEstimationRequestDTO req = new RepairCostEstimationRequestDTO();
        req.setServiceDescription("");

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
        RepairCostEstimationRequestDTO req = validBody();
        req.setVehicleId(-1L);

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
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT6 — MECHANIC role is not permitted → 403")
    void ct6_mechanicRole_returns403() throws Exception {
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

    // ── CT8: Entity not found → 404 ──────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT8 — vehicle not found → 404 NOT_FOUND")
    void ct8_vehicleNotFound_returns404() throws Exception {
        when(repairCostEstimationService.estimate(any()))
                .thenThrow(new ResourceNotFoundException("Vehicle not found: 99"));

        RepairCostEstimationRequestDTO req = validBody();
        req.setVehicleId(99L);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── CT9: Business rule → 409 ─────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT9 — BusinessRuleException → 409")
    void ct9_businessRule_returns409() throws Exception {
        when(repairCostEstimationService.estimate(any()))
                .thenThrow(new BusinessRuleException(
                        "serviceDescription is too short to produce a meaningful cost estimate."));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    // ── CT10: Provider unavailable → 200, no figure invented ─────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("CT10 — providerUnavailable → 200 with null total and human review required")
    void ct10_providerUnavailable_flaggedInResponse() throws Exception {
        RepairCostEstimationResponseDTO unavailable = RepairCostEstimationResponseDTO.builder()
                .estimatedTotalCost(null)
                .currency("INR")
                .costBreakdown(List.of())
                .confidence(null)
                .disclaimer("This cost estimate is an approximation only, NOT a guaranteed price.")
                .humanReviewRequired(true)
                .providerUnavailable(true)
                .dataLimitations(List.of("The AI provider is unavailable, so no cost figure could be produced."))
                .build();
        when(repairCostEstimationService.estimate(any())).thenReturn(unavailable);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerUnavailable").value(true))
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.estimatedTotalCost").doesNotExist());
    }

    // ── CT11: No sensitive information leakage ────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("CT11 — response body leaks no API key, token or provider internals")
    void ct11_noSensitiveInformationLeakage() throws Exception {
        when(repairCostEstimationService.estimate(any()))
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
