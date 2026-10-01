package com.autoservicehub.controller;

import com.autoservicehub.dto.QualityCheckRequestDTO;
import com.autoservicehub.dto.QualityCheckResponseDTO;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.service.QualityCheckService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint-level tests for {@link QualityCheckController}: authorization on both
 * routes, the 201 status and response envelope, 400/404/409 error mapping, and the
 * fact that no service call is made when authorization fails.
 *
 * <p>Uses the same {@code @WebMvcTest} slice pattern already established by
 * {@code CustomerControllerAuthorizationTest}, with a minimal in-test security chain
 * so {@code @PreAuthorize} is genuinely evaluated rather than assumed.
 */
@WebMvcTest(controllers = QualityCheckController.class)
@Import({QualityCheckControllerAuthorizationTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class QualityCheckControllerAuthorizationTest {

    private static final String QC_URL = "/api/v1/job-cards/55/quality-checks";

    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Same shape as CustomerControllerAuthorizationTest / AiController*Test:
                // without an explicit entry point, Spring Security 6's DEFAULT entry
                // point answers 403 for anonymous requests, which would make the
                // unauthenticated cases indistinguishable from a role denial.
                .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((req, res, e) ->
                        res.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                    .accessDeniedHandler((req, res, e) ->
                        res.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }
    }

    @Autowired MockMvc     mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean QualityCheckService     qualityCheckService;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    private QualityCheckResponseDTO response(String result, int attempt) {
        QualityCheckResponseDTO dto = new QualityCheckResponseDTO();
        dto.setId(1L);
        dto.setJobCardId(55L);
        dto.setResult(result);
        dto.setAttemptNo(attempt);
        dto.setCheckedByUserId(100L);
        dto.setCheckedByName("Manager A");
        dto.setCheckedAt(LocalDateTime.now());
        return dto;
    }

    private String body(String result) throws Exception {
        QualityCheckRequestDTO dto = new QualityCheckRequestDTO();
        dto.setResult(result);
        return mapper.writeValueAsString(dto);
    }
    // ── POST ───────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("QCH1 - POST as MANAGER returns 201 with the standard envelope")
    void postAsManagerReturns201() throws Exception {
        when(qualityCheckService.record(eq(55L), any())).thenReturn(response("PASS", 1));

        mockMvc.perform(post(QC_URL).contentType(MediaType.APPLICATION_JSON).content(body("PASS")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result").value("PASS"))
                .andExpect(jsonPath("$.data.attemptNo").value(1))
                .andExpect(jsonPath("$.data.checkedByUserId").value(100L));

        verify(qualityCheckService).record(eq(55L), any());
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("QCH2 - POST as OWNER is allowed")
    void postAsOwnerAllowed() throws Exception {
        when(qualityCheckService.record(eq(55L), any())).thenReturn(response("PASS", 1));
        mockMvc.perform(post(QC_URL).contentType(MediaType.APPLICATION_JSON).content(body("PASS")))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("QCH3 - POST as SERVICE_ADVISOR is allowed")
    void postAsAdvisorAllowed() throws Exception {
        when(qualityCheckService.record(eq(55L), any())).thenReturn(response("PASS", 1));
        mockMvc.perform(post(QC_URL).contentType(MediaType.APPLICATION_JSON).content(body("PASS")))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("QCH4 - POST as MECHANIC is 403 and the service is never called")
    void postAsMechanicForbidden() throws Exception {
        mockMvc.perform(post(QC_URL).contentType(MediaType.APPLICATION_JSON).content(body("PASS")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(qualityCheckService, never()).record(any(), any());
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("QCH5 - POST as BILLING_USER is 403 (billing may read, not sign off QC)")
    void postAsBillingForbidden() throws Exception {
        mockMvc.perform(post(QC_URL).contentType(MediaType.APPLICATION_JSON).content(body("PASS")))
                .andExpect(status().isForbidden());
        verify(qualityCheckService, never()).record(any(), any());
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("QCH6 - POST as INVENTORY_MANAGER is 403")
    void postAsInventoryForbidden() throws Exception {
        mockMvc.perform(post(QC_URL).contentType(MediaType.APPLICATION_JSON).content(body("PASS")))
                .andExpect(status().isForbidden());
        verify(qualityCheckService, never()).record(any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("QCH7 - POST anonymously is 401 and reaches no service")
    void postAnonymousIs401() throws Exception {
        mockMvc.perform(post(QC_URL).contentType(MediaType.APPLICATION_JSON).content(body("PASS")))
                .andExpect(status().isUnauthorized());
        verify(qualityCheckService, never()).record(any(), any());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("QCH8 - POST without a result is 400 VALIDATION_ERROR")
    void postWithoutResultIs400() throws Exception {
        mockMvc.perform(post(QC_URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("QCH9 - a missing job card surfaces as 404")
    void missingJobCardIs404() throws Exception {
        when(qualityCheckService.record(eq(55L), any()))
                .thenThrow(new ResourceNotFoundException("JobCard not found: 55"));
        mockMvc.perform(post(QC_URL).contentType(MediaType.APPLICATION_JSON).content(body("PASS")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("QCH10 - a rejected result surfaces as 409 BUSINESS_RULE_VIOLATION")
    void rejectedResultIs409() throws Exception {
        when(qualityCheckService.record(eq(55L), any()))
                .thenThrow(new BusinessRuleException("Quality check result must be PASS or FAIL."));
        mockMvc.perform(post(QC_URL).contentType(MediaType.APPLICATION_JSON).content(body("MAYBE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }
    // ── GET ────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("QCH11 - GET as MECHANIC is allowed and returns history newest first")
    void getAsMechanicAllowed() throws Exception {
        when(qualityCheckService.listForJobCard(55L))
                .thenReturn(List.of(response("FAIL", 2), response("PASS", 1)));

        mockMvc.perform(get(QC_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].attemptNo").value(2))
                .andExpect(jsonPath("$.data[0].result").value("FAIL"))
                .andExpect(jsonPath("$.data[1].attemptNo").value(1));
    }

    @Test
    @WithMockUser(roles = "BILLING_USER")
    @DisplayName("QCH12 - GET as BILLING_USER is allowed (read-only role)")
    void getAsBillingAllowed() throws Exception {
        when(qualityCheckService.listForJobCard(55L)).thenReturn(List.of());
        mockMvc.perform(get(QC_URL)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("QCH13 - GET as INVENTORY_MANAGER is allowed (read-only role)")
    void getAsInventoryAllowed() throws Exception {
        when(qualityCheckService.listForJobCard(55L)).thenReturn(List.of());
        mockMvc.perform(get(QC_URL)).andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("QCH14 - GET anonymously is 401")
    void getAnonymousIs401() throws Exception {
        mockMvc.perform(get(QC_URL)).andExpect(status().isUnauthorized());
        verify(qualityCheckService, never()).listForJobCard(any());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("QCH15 - GET for a missing job card surfaces as 404")
    void getMissingJobCardIs404() throws Exception {
        when(qualityCheckService.listForJobCard(55L))
                .thenThrow(new ResourceNotFoundException("JobCard not found: 55"));
        mockMvc.perform(get(QC_URL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
