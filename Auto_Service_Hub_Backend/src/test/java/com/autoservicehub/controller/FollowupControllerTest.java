package com.autoservicehub.controller;

import com.autoservicehub.dto.FollowupRequestDTO;
import com.autoservicehub.dto.FollowupResponseDTO;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.FollowupService;
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
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the follow-up endpoints, covering the new
 * pending / due / by-customer routes, request validation, error mapping and the
 * @PreAuthorize role guards.
 *
 * Test cases
 * ----------
 * FC1  valid create                  → 201, due/closed flags present
 * FC2  create without a due date     → 400
 * FC3  create without a reason       → 400
 * FC4  create without a customer     → 400
 * FC5  unknown customer              → 404
 * FC6  invalid status                → 409
 * FC7  past due date                 → 409
 * FC8  pending list                  → 200
 * FC9  due list, asOf given          → 200
 * FC10 due list without asOf         → 200, defaults to today
 * FC11 by-customer list              → 200
 * FC12 by-customer, unknown customer → 404
 * FC13 MECHANIC may not create       → 403
 * FC14 MECHANIC may read the lists   → 200
 * FC15 anonymous                     → 401
 */
@WebMvcTest(controllers = FollowupController.class)
@Import({FollowupControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class FollowupControllerTest {

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

    private static final String BASE = "/api/v1/followups";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean FollowupService          service;
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

    private FollowupResponseDTO followupResponse() {
        FollowupResponseDTO dto = new FollowupResponseDTO();
        dto.setId(1L);
        dto.setCustomerId(5L);
        dto.setCustomerName("Ravi");
        dto.setCustomerPhone("+919800000000");
        dto.setJobCardId(9L);
        dto.setJobCardNumber("JC-9");
        dto.setDueDate(LocalDate.now().plusDays(7));
        dto.setReason("Post-service check");
        dto.setStatus("PENDING");
        dto.setDue(false);
        dto.setClosed(false);
        return dto;
    }

    private String validBody() throws Exception {
        FollowupRequestDTO req = new FollowupRequestDTO();
        req.setCustomerId(5L);
        req.setJobCardId(9L);
        req.setDueDate(LocalDate.now().plusDays(7));
        req.setReason("Post-service check");
        return mapper.writeValueAsString(req);
    }

    // ── Creation ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("FC1 a valid create returns 201 with the derived due/closed flags")
    void createsFollowup() throws Exception {
        when(service.create(any())).thenReturn(followupResponse());

        mockMvc.perform(post(BASE)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(validBody()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.data.id").value(1))
               .andExpect(jsonPath("$.data.status").value("PENDING"))
               .andExpect(jsonPath("$.data.due").value(false))
               .andExpect(jsonPath("$.data.closed").value(false))
               .andExpect(jsonPath("$.data.jobCardId").value(9));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("FC2 a create without a due date is a 400")
    void rejectsMissingDueDate() throws Exception {
        mockMvc.perform(post(BASE)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{\"customerId\":5,\"reason\":\"Post-service check\"}"))
               .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("FC3 a create without a reason is a 400")
    void rejectsMissingReason() throws Exception {
        mockMvc.perform(post(BASE)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{\"customerId\":5,\"dueDate\":\"" + LocalDate.now().plusDays(3) + "\"}"))
               .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("FC4 a create without a customer is a 400")
    void rejectsMissingCustomer() throws Exception {
        mockMvc.perform(post(BASE)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{\"dueDate\":\"" + LocalDate.now().plusDays(3)
                                + "\",\"reason\":\"Post-service check\"}"))
               .andExpect(status().isBadRequest());
    }

    // ── Error mapping ────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("FC5 an unknown customer is a 404")
    void unknownCustomerIs404() throws Exception {
        when(service.create(any()))
                .thenThrow(new ResourceNotFoundException("Customer not found: 5"));

        mockMvc.perform(post(BASE)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(validBody()))
               .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("FC6 an unsupported status is a 409")
    void unsupportedStatusIs409() throws Exception {
        when(service.create(any()))
                .thenThrow(new BusinessRuleException("Unsupported follow-up status: 'WAT'."));

        mockMvc.perform(post(BASE)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(validBody()))
               .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("FC7 a past due date is a 409")
    void pastDueDateIs409() throws Exception {
        when(service.create(any()))
                .thenThrow(new BusinessRuleException("dueDate is in the past"));

        mockMvc.perform(post(BASE)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(validBody()))
               .andExpect(status().isConflict());
    }

    // ── Pending / due / by-customer lists ────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("FC8 the pending list returns open follow-ups")
    void listsPending() throws Exception {
        when(service.listPending(any()))
                .thenReturn(new PageImpl<>(List.of(followupResponse()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(BASE + "/pending"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.content[0].id").value(1))
               .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("FC9 the due list honours an explicit asOf date")
    void listsDueAsOf() throws Exception {
        LocalDate asOf = LocalDate.now().plusDays(1);
        when(service.listDue(eq(asOf), any()))
                .thenReturn(new PageImpl<>(List.of(followupResponse()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(BASE + "/due").param("asOf", asOf.toString()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.content[0].id").value(1));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("FC10 the due list defaults asOf to today when it is omitted")
    void listsDueDefaultsToToday() throws Exception {
        when(service.listDue(eq(LocalDate.now()), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(BASE + "/due"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("FC11 the by-customer list returns that customer's follow-ups")
    void listsByCustomer() throws Exception {
        when(service.listByCustomer(eq(5L), any()))
                .thenReturn(new PageImpl<>(List.of(followupResponse()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(BASE + "/customer/5"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.content[0].customerId").value(5));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("FC12 the by-customer list for an unknown customer is a 404")
    void listByUnknownCustomerIs404() throws Exception {
        when(service.listByCustomer(eq(404L), any()))
                .thenThrow(new ResourceNotFoundException("Customer not found: 404"));

        mockMvc.perform(get(BASE + "/customer/404"))
               .andExpect(status().isNotFound());
    }

    // ── Authorization ────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("FC13 a MECHANIC may not create a follow-up")
    void mechanicCannotCreate() throws Exception {
        mockMvc.perform(post(BASE)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(validBody()))
               .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("FC14 a MECHANIC may read the pending list")
    void mechanicCanRead() throws Exception {
        when(service.listPending(any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(BASE + "/pending")).andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("FC15 an anonymous caller is rejected with 401")
    void anonymousRejected() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }
}
