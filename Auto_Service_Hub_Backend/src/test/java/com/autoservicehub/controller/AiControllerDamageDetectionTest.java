package com.autoservicehub.controller;

import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.dto.DamageDetectionRequestDTO;
import com.autoservicehub.dto.DamageDetectionResponseDTO;
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
 * Controller slice tests for {@code POST /api/v1/ai/damage-detection}
 * (SRS 5.6, FR-AI-13..16, BR-09).
 *
 * <p>Test cases: CT1/CT2 valid requests, CT3 blank description → 400,
 * CT4 missing vehicleId → 400, CT5 vehicle not found → 404,
 * CT6 job card not found → 404, CT7 business rule → 409, CT8 anonymous → 401,
 * CT9 CUSTOMER → 403, CT10 INVENTORY_MANAGER → 403, CT11 provider unavailable,
 * CT12 response never claims image analysis, CT13 no leak.
 */
@WebMvcTest(controllers = AiController.class)
@Import({AiControllerDamageDetectionTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class AiControllerDamageDetectionTest {

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

    private static final String URL = "/api/v1/ai/damage-detection";

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean DamageDetectionService        damageDetectionService;
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

    private DamageDetectionRequestDTO validBody() {
        DamageDetectionRequestDTO req = new DamageDetectionRequestDTO();
        req.setVehicleId(3L);
        req.setDamageDescription("Front-left wing panel is scraped and dented. Bumper cover is cracked.");
        return req;
    }

    private DamageDetectionResponseDTO successResponse() {
        return DamageDetectionResponseDTO.builder()
                .vehicleId(3L)
                .summary("Damage appears concentrated on the front-left corner")
                .affectedAreas(List.of(new DamageDetectionResponseDTO.DamageAreaDTO(
                        "Front-left wing panel", "MODERATE", "Surface scraping and denting",
                        "The description states the panel is scraped and dented.")))
                .confidence(new BigDecimal("0.62"))
                .humanReviewRequired(true)
                .providerUnavailable(false)
                .dataLimitations(List.of(
                        "Damage assessment is based on the reported description and job context only. " +
                        "No images or photographs were analysed — the system has no image upload or " +
                        "vision capability.",
                        "Inspections are not linked to vehicles or job cards in the current data model, " +
                        "so inspection records could not be consulted.",
                        "No damage type taxonomy or severity scale is defined in the system, so provider " +
                        "severity levels could not be validated against a standard."))
                .disclaimer("This damage assessment is AI-generated from a WRITTEN description of the " +
                        "damage and is advisory only. NO photographs, images or video were analysed — " +
                        "the system has no image upload or vision capability. This output must NOT be " +
                        "used to settle an insurance claim, and must NOT be used to declare a vehicle " +
                        "safe or unsafe.")
                .build();
    }

    // ── CT1/CT2: Valid requests ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT1 — valid request, SERVICE_ADVISOR → 200, humanReviewRequired=true")
    void ct1_validRequest_serviceAdvisor_returns200() throws Exception {
        when(damageDetectionService.assess(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicleId").value(3))
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.providerUnavailable").value(false))
                .andExpect(jsonPath("$.data.confidence").value(0.62))
                .andExpect(jsonPath("$.data.affectedAreas.length()").value(1))
                .andExpect(jsonPath("$.data.affectedAreas[0].severity").value("MODERATE"));
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT2 — valid request, MECHANIC → 200")
    void ct2_validRequest_mechanic_returns200() throws Exception {
        when(damageDetectionService.assess(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary")
                        .value("Damage appears concentrated on the front-left corner"));
    }

    // ── CT3..CT7: Validation, not-found and business rules ───────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT3 — blank damageDescription → 400 VALIDATION_ERROR")
    void ct3_blankDescription_returns400() throws Exception {
        DamageDetectionRequestDTO req = new DamageDetectionRequestDTO();
        req.setVehicleId(3L);
        req.setDamageDescription("");

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT4 — missing vehicleId → 400 VALIDATION_ERROR")
    void ct4_missingVehicleId_returns400() throws Exception {
        DamageDetectionRequestDTO req = new DamageDetectionRequestDTO();
        req.setDamageDescription("Front bumper is cracked and hanging loose.");

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT5 — vehicle not found → 404 NOT_FOUND")
    void ct5_vehicleNotFound_returns404() throws Exception {
        when(damageDetectionService.assess(any()))
                .thenThrow(new ResourceNotFoundException("Vehicle not found: 99"));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT6 — job card not found → 404 NOT_FOUND")
    void ct6_jobCardNotFound_returns404() throws Exception {
        when(damageDetectionService.assess(any()))
                .thenThrow(new ResourceNotFoundException("JobCard not found: 88"));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT7 — job card from another vehicle → 409 BUSINESS_RULE_VIOLATION")
    void ct7_jobCardFromAnotherVehicle_returns409() throws Exception {
        when(damageDetectionService.assess(any()))
                .thenThrow(new BusinessRuleException(
                        "JobCard 7 belongs to a different vehicle and cannot be used to assess damage " +
                        "for vehicle 3."));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    // ── CT8/CT9/CT10: Authorization ─────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("CT8 — anonymous request → 401")
    void ct8_anonymous_returns401() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isUnauthorized());
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

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("CT10 — INVENTORY_MANAGER role is not permitted → 403")
    void ct10_inventoryManager_returns403() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isForbidden());
    }

    // ── CT11/CT12/CT13 ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("CT11 — provider unavailable → 200, no damage areas fabricated")
    void ct11_providerUnavailable_noDamageAreas() throws Exception {
        DamageDetectionResponseDTO unavailable = DamageDetectionResponseDTO.builder()
                .vehicleId(3L)
                .summary(null)
                .affectedAreas(List.of())
                .confidence(null)
                .humanReviewRequired(true)
                .providerUnavailable(true)
                .dataLimitations(List.of(
                        "Damage assessment is based on the reported description and job context only. " +
                        "No images or photographs were analysed — the system has no image upload or " +
                        "vision capability.",
                        "The AI provider is unavailable, so no damage assessment could be produced."))
                .disclaimer("This damage assessment is AI-generated from a WRITTEN description. " +
                        "NO photographs, images or video were analysed.")
                .build();
        when(damageDetectionService.assess(any())).thenReturn(unavailable);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerUnavailable").value(true))
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.affectedAreas.length()").value(0))
                .andExpect(jsonPath("$.data.summary").doesNotExist())
                .andExpect(jsonPath("$.data.confidence").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT12 — response never claims an image or photo was analysed")
    void ct12_neverClaimsImageAnalysis() throws Exception {
        when(damageDetectionService.assess(any())).thenReturn(successResponse());

        String body = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("No images or photographs were analysed");
        assertThat(body).contains("must NOT be used to settle an insurance claim");
        assertThat(body).doesNotContain("vision analysis performed")
                         .doesNotContain("image analysed by the AI");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("CT13 — response body leaks no API key or provider internals")
    void ct13_noSensitiveInformationLeakage() throws Exception {
        when(damageDetectionService.assess(any()))
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
