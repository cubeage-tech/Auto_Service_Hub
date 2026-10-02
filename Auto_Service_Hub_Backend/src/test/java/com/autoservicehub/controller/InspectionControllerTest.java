package com.autoservicehub.controller;

import com.autoservicehub.dto.InspectionItemResponseDTO;
import com.autoservicehub.dto.InspectionRequestDTO;
import com.autoservicehub.dto.InspectionResponseDTO;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.InspectionService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for {@code InspectionController}, covering the
 * Inspection → Job Card integration.
 *
 * Uses {@code @WebMvcTest} with a minimal inner security configuration that
 *   - Enables @PreAuthorize / method security (@EnableMethodSecurity)
 *   - Requires authentication on all requests
 *   - Does NOT register the real JwtAuthenticationFilter
 *   (authentication is supplied via @WithMockUser / @WithAnonymousUser)
 *
 * Test cases
 * ----------
 * CT1  Create with vehicleId              → 201, vehicle echoed back
 * CT2  Create without vehicleId           → 400 VALIDATION_ERROR
 * CT3  Create with blank complaint         → 400 VALIDATION_ERROR
 * CT4  Item missing checklistItem          → 400 VALIDATION_ERROR (nested @Valid)
 * CT5  Create with negative estimatedCost → 400 VALIDATION_ERROR
 * CT6  Job card of a different vehicle    → 409 BUSINESS_RULE_VIOLATION
 * CT7  Inspection not found on getById    → 404
 * CT8  GET /vehicle/{id}                  → 200 paged history
 * CT9  GET /job-card/{id} linked          → 200 with findings
 * CT10 GET /job-card/{id} not linked      → 404
 * CT11 Anonymous request                  → 401
 * CT12 MECHANIC may read but not create   → 403 on POST
 */
@WebMvcTest(controllers = InspectionController.class)
@Import({InspectionControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class InspectionControllerTest {

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

    private static final String BASE   = "/api/v1/inspections";
    private static final String BY_VEH = BASE + "/vehicle/10";
    private static final String BY_JOB = BASE + "/job-card/55";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean InspectionService       service;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean JwtTokenProvider        jwtTokenProvider;
    @MockBean CustomUserDetailsService customUserDetailsService;

    // Make the mocked JwtAuthenticationFilter pass requests through to the
    // next filter, otherwise the request never reaches the dispatcher servlet.
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

    private InspectionRequestDTO validBody() {
        InspectionRequestDTO req = new InspectionRequestDTO();
        req.setVehicleId(10L);
        req.setComplaint("Brake noise and pulling to the left under braking.");
        return req;
    }

    private InspectionResponseDTO successResponse() {
        InspectionResponseDTO dto = new InspectionResponseDTO();
        dto.setId(42L);
        dto.setVehicleId(10L);
        dto.setVehicleInfo("MH-12-AB-1234 | Swift VXI");
        dto.setComplaint("Brake noise and pulling to the left under braking.");
        dto.setStatus("PENDING");
        dto.setItems(List.of());
        return dto;
    }

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    // ── CT1: Create with vehicleId → 201 ──────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT1 — create with vehicleId → 201, vehicle echoed back")
    void ct1_createWithVehicle_returns201() throws Exception {
        when(service.create(any())).thenReturn(successResponse());

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validBody())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.vehicleId").value(10))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    // ── CT2: Missing vehicleId → 400 ─────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT2 — create without vehicleId → 400 VALIDATION_ERROR")
    void ct2_missingVehicleId_returns400() throws Exception {
        InspectionRequestDTO req = validBody();
        req.setVehicleId(null);

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── CT3: Blank complaint → 400 ───────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT3 — create with blank complaint → 400 VALIDATION_ERROR")
    void ct3_blankComplaint_returns400() throws Exception {
        InspectionRequestDTO req = validBody();
        req.setComplaint("  ");

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── CT4: Item missing checklistItem → 400 (nested @Valid) ────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT4 — item without checklistItem → 400, nested @Valid is applied")
    void ct4_itemWithoutChecklistItem_returns400() throws Exception {
        // Sent as raw JSON so the payload is not constrained by the DTO's
        // generic type — the point is that the nested @Valid is applied.
        String body = """
                {
                  "vehicleId": 10,
                  "complaint": "Brake noise and pulling to the left under braking.",
                  "items": [ { "finding": "Worn below 2mm" } ]
                }
                """;

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── CT5: Negative estimatedCost → 400 ─────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT5 — negative estimatedCost → 400 VALIDATION_ERROR")
    void ct5_negativeEstimatedCost_returns400() throws Exception {
        InspectionRequestDTO req = validBody();
        req.setEstimatedCost(new BigDecimal("-1.00"));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── CT6: Job card of a different vehicle → 409 ────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("CT6 — job card of a different vehicle → 409 BUSINESS_RULE_VIOLATION")
    void ct6_jobCardDifferentVehicle_returns409() throws Exception {
        when(service.create(any())).thenThrow(new BusinessRuleException(
                "JobCard 55 belongs to a different vehicle and cannot be linked to an "
                + "inspection of vehicle 10."));

        InspectionRequestDTO req = validBody();
        req.setJobCardId(55L);

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    // ── CT7: getById not found → 404 ──────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT7 — getById on an unknown inspection → 404")
    void ct7_getByIdNotFound_returns404() throws Exception {
        when(service.getById(99L))
                .thenThrow(new ResourceNotFoundException("Inspection not found: 99"));

        mockMvc.perform(get(BASE + "/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── CT8: GET /vehicle/{id} → 200 ──────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT8 — list inspections for a vehicle → 200 paged history")
    void ct8_listByVehicle_returns200() throws Exception {
        when(service.listByVehicle(anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of(successResponse()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(BY_VEH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].vehicleId").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    // ── CT9: GET /job-card/{id} linked → 200 with findings ────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT9 — inspection for a job card → 200, includes findings")
    void ct9_getByJobCardId_returns200WithFindings() throws Exception {
        InspectionItemResponseDTO finding = new InspectionItemResponseDTO();
        finding.setId(7L);
        finding.setChecklistItem("Front brake pads");
        finding.setFinding("Worn below 2mm");

        InspectionResponseDTO dto = successResponse();
        dto.setJobCardId(55L);
        dto.setItems(List.of(finding));

        when(service.getByJobCardId(55L)).thenReturn(dto);

        mockMvc.perform(get(BY_JOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobCardId").value(55))
                .andExpect(jsonPath("$.data.items[0].checklistItem").value("Front brake pads"))
                .andExpect(jsonPath("$.data.items[0].finding").value("Worn below 2mm"));
    }

    // ── CT10: GET /job-card/{id} not linked → 404 ─────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT10 — job card with no linked inspection → 404")
    void ct10_getByJobCardIdNotLinked_returns404() throws Exception {
        when(service.getByJobCardId(55L)).thenThrow(new ResourceNotFoundException(
                "No inspection is linked to JobCard: 55"));

        mockMvc.perform(get(BY_JOB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── CT11: Anonymous → 401 ─────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("CT11 — anonymous request → 401")
    void ct11_anonymous_returns401() throws Exception {
        mockMvc.perform(get(BY_JOB)).andExpect(status().isUnauthorized());
    }

    // ── CT12: MECHANIC may read but not create → 403 ──────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("CT12 — MECHANIC may read inspections but not create them → 403")
    void ct12_mechanicCannotCreate_returns403() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validBody())))
                .andExpect(status().isForbidden());
    }
}