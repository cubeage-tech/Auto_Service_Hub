package com.autoservicehub.controller;

import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.dto.DiagnosisRequestDTO;
import com.autoservicehub.dto.DiagnosisResponseDTO;
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
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
/**
 * Controller slice tests for {@code POST /api/v1/ai/diagnosis}.
 *
 * Uses {@code @WebMvcTest} with a minimal inner security configuration that:
 *   - Enables @PreAuthorize / method security (@EnableMethodSecurity)
 *   - Requires authentication on all requests
 *   - Does NOT register the real JwtAuthenticationFilter
 *   (authentication is supplied via @WithMockUser / @WithAnonymousUser)
 *
 * Test cases
 * ----------
 * CT1  Valid request, MECHANIC role           → 200, humanReviewRequired=true
 * CT2  Valid request, SERVICE_ADVISOR role    → 200, disclaimer present
 * CT3  Blank symptoms                         → 400 VALIDATION_ERROR
 * CT4  Anonymous (unauthenticated) request    → 401
 * CT5  CUSTOMER role (not in allowed list)    → 403
 * CT6  Vehicle not found (service throws)     → 404
 * CT7  BusinessRuleException from service     → 409
 * CT8  providerUnavailable flag flows through → 200
 */
@WebMvcTest(controllers = AiController.class)
@Import({AiControllerDiagnosisTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class AiControllerDiagnosisTest {

    // ── Minimal security config for the slice test ────────────────────────
    // Enables method security (@PreAuthorize), requires authentication on all
    // requests, and sends 401 for anonymous requests.
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

    private static final String URL = "/api/v1/ai/diagnosis";

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean DamageDetectionService       damageDetectionService;
    @MockBean SparePartsPredictionService sparePartsPredictionService;
    @MockBean VehicleDiagnosisService  vehicleDiagnosisService;
    // AiController now also serves Repair Cost Estimation (FR-AI-05..08), so its
    // dependency must be mocked here for the slice context to build. The
    // diagnosis tests below are unaffected.
    @MockBean RepairCostEstimationService repairCostEstimationService;
    // AiController also serves Maintenance Prediction (FR-AI-09..12); its
    // dependency must be mocked for the slice context to build.
    @MockBean MaintenancePredictionService maintenancePredictionService;
    // AiController also serves Mechanic Assignment (FR-AI-17..20); its
    // dependency must be mocked for the slice context to build.
    @MockBean MechanicAssignmentService    mechanicAssignmentService;
    @MockBean AiOrchestrationService   aiOrchestrationService;
    @MockBean JwtAuthenticationFilter  jwtAuthenticationFilter;
    @MockBean JwtTokenProvider         jwtTokenProvider;
    @MockBean CustomUserDetailsService customUserDetailsService;

    // Make the mocked JwtAuthenticationFilter actually pass requests through
    // to the next filter in the chain. Without this, the mock's doFilter is
    // a no-op and the request never reaches the dispatcher servlet.
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

    private DiagnosisRequestDTO validBody() {
        DiagnosisRequestDTO req = new DiagnosisRequestDTO();
        req.setSymptoms("Engine knocking noise above 3000 RPM");
        return req;
    }

    private DiagnosisResponseDTO successResponse() {
        return DiagnosisResponseDTO.builder()
                .vehicleId(null)
                .possibleIssue("Possible engine bearing wear")
                .recommendation("Inspect timing chain tension")
                .confidence(new BigDecimal("0.82"))
                .disclaimer(
                        "This diagnosis is AI-generated and must be reviewed and confirmed by a " +
                        "qualified technician before any repair work is carried out. " +
                        "SmartGarage AI accepts no liability for actions taken based solely on this output.")
                .humanReviewRequired(true)
                .providerUnavailable(false)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // ── CT1: Valid request, MECHANIC role → 200 ───────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT1 — valid request, MECHANIC role → 200, humanReviewRequired=true")
    void ct1_validRequest_mechanicRole_returns200() throws Exception {
        when(vehicleDiagnosisService.diagnose(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.possibleIssue").value("Possible engine bearing wear"))
                .andExpect(jsonPath("$.data.disclaimer").isNotEmpty());
    }

    // ── CT2: SERVICE_ADVISOR role → 200 ──────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT2 — valid request, SERVICE_ADVISOR role → 200, disclaimer present")
    void ct2_validRequest_serviceAdvisorRole_returns200() throws Exception {
        when(vehicleDiagnosisService.diagnose(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disclaimer").isNotEmpty())
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true));
    }

    // ── CT3: Blank symptoms → 400 ─────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT3 — blank symptoms field → 400 VALIDATION_ERROR")
    void ct3_blankSymptoms_returns400() throws Exception {
        DiagnosisRequestDTO bad = new DiagnosisRequestDTO();
        bad.setSymptoms("   "); // whitespace only — triggers @NotBlank

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    // ── CT4: Anonymous → 401 ──────────────────────────────────────────────
    // @WithAnonymousUser provides an unauthenticated principal.
    // TestSecurityConfig.authenticationEntryPoint sends 401.

    @Test
    @WithAnonymousUser
    @DisplayName("CT4 — anonymous (unauthenticated) request → 401")
    void ct4_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isUnauthorized());
    }

    // ── CT5: CUSTOMER role → 403 ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("CT5 — CUSTOMER role not authorised for diagnosis → 403")
    void ct5_customerRole_returns403() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isForbidden());
    }

    // ── CT6: Vehicle not found → 404 ──────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT6 — service throws ResourceNotFoundException → 404")
    void ct6_vehicleNotFound_returns404() throws Exception {
        when(vehicleDiagnosisService.diagnose(any()))
                .thenThrow(new ResourceNotFoundException("Vehicle not found: 99"));

        DiagnosisRequestDTO req = validBody();
        req.setVehicleId(99L);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── CT7: BusinessRuleException → 409 ──────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT7 — BusinessRuleException from service → 409, business message returned")
    void ct7_businessRuleViolation_returns409() throws Exception {
        when(vehicleDiagnosisService.diagnose(any()))
                .thenThrow(new BusinessRuleException(
                        "symptoms is too short to produce a useful diagnosis."));

        DiagnosisRequestDTO req = new DiagnosisRequestDTO();
        req.setSymptoms("Hi"); // service-level guard

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message")
                        .value("symptoms is too short to produce a useful diagnosis."));
    }

    // ── CT8: providerUnavailable=true flows through → 200 ─────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("CT8 — providerUnavailable=true flows through to client response")
    void ct8_providerUnavailable_flaggedInResponse() throws Exception {
        DiagnosisResponseDTO unavailable = DiagnosisResponseDTO.builder()
                .possibleIssue("AI provider is currently unavailable. Please proceed with manual inspection.")
                .recommendation("Please have a qualified technician inspect the vehicle.")
                .confidence(BigDecimal.ZERO)
                .disclaimer("This diagnosis is AI-generated and must be reviewed and confirmed by a qualified technician.")
                .humanReviewRequired(true)
                .providerUnavailable(true)
                .generatedAt(LocalDateTime.now())
                .build();

        when(vehicleDiagnosisService.diagnose(any())).thenReturn(unavailable);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerUnavailable").value(true))
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.confidence").value(0));
    }
}
