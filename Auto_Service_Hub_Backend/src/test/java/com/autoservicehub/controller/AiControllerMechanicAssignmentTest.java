package com.autoservicehub.controller;

import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.dto.MechanicAssignmentRequestDTO;
import com.autoservicehub.dto.MechanicAssignmentResponseDTO;
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
 * Controller slice tests for {@code POST /api/v1/ai/mechanic-assignment}
 * (SRS 5.4, FR-AI-17..20, BR-09).
 *
 * <p>Test cases: CT1/CT2 valid requests, CT3 missing jobCardId → 400,
 * CT4 job card not found → 404, CT5 business rule → 409, CT6 anonymous → 401,
 * CT7 MECHANIC → 403, CT8 CUSTOMER → 403, CT9 no recommendation → 200 with
 * assignmentPersisted=false, CT10 no assignment persisted, CT11 no leak.
 */
@WebMvcTest(controllers = AiController.class)
@Import({AiControllerMechanicAssignmentTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class AiControllerMechanicAssignmentTest {

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

    private static final String URL = "/api/v1/ai/mechanic-assignment";

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean MechanicAssignmentService    mechanicAssignmentService;
    @MockBean MaintenancePredictionService maintenancePredictionService;
    @MockBean DamageDetectionService       damageDetectionService;
    @MockBean SparePartsPredictionService sparePartsPredictionService;
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

    private MechanicAssignmentRequestDTO validBody() {
        MechanicAssignmentRequestDTO req = new MechanicAssignmentRequestDTO();
        req.setJobCardId(7L);
        return req;
    }

    private MechanicAssignmentResponseDTO successResponse() {
        return MechanicAssignmentResponseDTO.builder()
                .jobCardId(7L)
                .recommendedMechanicId(4L)
                .recommendedMechanicEmployeeCode("MEC-004")
                .recommendedMechanicName("Ravi Kumar")
                .rationale("Lowest current open workload among active mechanics.")
                .confidence(new BigDecimal("0.71"))
                .humanReviewRequired(true)
                .assignmentPersisted(false)
                .providerUnavailable(false)
                .dataLimitations(List.of("No mechanic rating, availability schedule or attendance data is available."))
                .candidatePoolSummary(
                        new MechanicAssignmentResponseDTO.CandidatePoolSummaryDTO(3, "RECEIVED", false))
                .disclaimer("This mechanic assignment is AI-generated and is a RECOMMENDATION ONLY. "
                        + "No assignment has been made.")
                .build();
    }

    // ── CT1/CT2: Valid requests ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT1 — valid request, SERVICE_ADVISOR → 200, humanReviewRequired=true")
    void ct1_validRequest_serviceAdvisor_returns200() throws Exception {
        when(mechanicAssignmentService.recommend(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobCardId").value(7))
                .andExpect(jsonPath("$.data.recommendedMechanicId").value(4))
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.assignmentPersisted").value(false))
                .andExpect(jsonPath("$.data.confidence").value(0.71))
                .andExpect(jsonPath("$.data.candidatePoolSummary.activeCandidateCount").value(3))
                .andExpect(jsonPath("$.data.disclaimer")
                        .value(org.hamcrest.Matchers.containsString("RECOMMENDATION ONLY")));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("CT2 — valid request, MANAGER → 200")
    void ct2_validRequest_manager_returns200() throws Exception {
        when(mechanicAssignmentService.recommend(any())).thenReturn(successResponse());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendedMechanicName").value("Ravi Kumar"));
    }

    // ── CT3/CT4/CT5: Validation and not-found ────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT3 — missing jobCardId → 400 VALIDATION_ERROR")
    void ct3_missingJobCardId_returns400() throws Exception {
        MechanicAssignmentRequestDTO req = new MechanicAssignmentRequestDTO();

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT4 — job card not found → 404 NOT_FOUND")
    void ct4_jobCardNotFound_returns404() throws Exception {
        when(mechanicAssignmentService.recommend(any()))
                .thenThrow(new ResourceNotFoundException("JobCard not found: 99"));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT5 — inactive candidate → 409 BUSINESS_RULE_VIOLATION")
    void ct5_inactiveCandidate_returns409() throws Exception {
        when(mechanicAssignmentService.recommend(any()))
                .thenThrow(new BusinessRuleException("Mechanic 9 is not active and cannot be assigned work."));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    // ── CT6: Anonymous → 401 ─────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("CT6 — anonymous request → 401")
    void ct6_anonymous_returns401() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isUnauthorized());
    }

    // ── CT7/CT8: Insufficient role → 403 ─────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT7 — MECHANIC role is not permitted → 403")
    void ct7_mechanicRole_returns403() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("CT8 — CUSTOMER role is not permitted → 403")
    void ct8_customerRole_returns403() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isForbidden());
    }

    // ── CT9/CT10: No recommendation, and no assignment persisted ──────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("CT9 — provider unavailable → 200, no mechanic recommended")
    void ct9_providerUnavailable_noRecommendation() throws Exception {
        MechanicAssignmentResponseDTO unavailable = MechanicAssignmentResponseDTO.builder()
                .jobCardId(7L)
                .recommendedMechanicId(null)
                .recommendedMechanicName(null)
                .rationale(null)
                .confidence(null)
                .humanReviewRequired(true)
                .assignmentPersisted(false)
                .providerUnavailable(true)
                .dataLimitations(List.of("The AI provider is unavailable, so no mechanic could be "
                        + "recommended. Please assign the work manually."))
                .candidatePoolSummary(
                        new MechanicAssignmentResponseDTO.CandidatePoolSummaryDTO(2, "RECEIVED", false))
                .disclaimer("This mechanic assignment is AI-generated and is a RECOMMENDATION ONLY.")
                .build();
        when(mechanicAssignmentService.recommend(any())).thenReturn(unavailable);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerUnavailable").value(true))
                .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.data.recommendedMechanicId").doesNotExist())
                .andExpect(jsonPath("$.data.confidence").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT10 — a recommendation never reports a persisted assignment")
    void ct10_assignmentPersisted_isAlwaysFalse() throws Exception {
        when(mechanicAssignmentService.recommend(any())).thenReturn(successResponse());

        String body = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validBody())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The response explicitly declares that no assignment was made.
        assertThat(body).contains("\"assignmentPersisted\":false");
    }

    // ── CT11: No sensitive information leakage ────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("CT11 — response body leaks no API key or provider internals")
    void ct11_noSensitiveInformationLeakage() throws Exception {
        when(mechanicAssignmentService.recommend(any()))
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
