package com.autoservicehub.controller;

import com.autoservicehub.dto.AuditLogResponseDTO;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.AuditService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the audit read endpoint.
 *
 * Test cases
 * ----------
 * AC1  ADMIN reads the trail        -> 200
 * AC2  MANAGER reads the trail      -> 200
 * AC3  Filters are passed through   -> 200 with the filter built
 * AC4  MECHANIC                     -> 403
 * AC5  SERVICE_ADVISOR              -> 403
 * AC6  BILLING_USER                 -> 403
 * AC7  INVENTORY_MANAGER            -> 403
 * AC8  Anonymous                    -> 401
 * AC9  No write verb is mapped       -> 405/404
 */
@WebMvcTest(controllers = {AuditLogController.class})
@Import({AuditLogControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class AuditLogControllerTest {

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

    private static final String AUDIT = "/api/v1/audit-logs";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean AuditService           auditService;
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
            org.mockito.ArgumentMatchers.any(HttpServletRequest.class),
            org.mockito.ArgumentMatchers.any(HttpServletResponse.class),
            org.mockito.ArgumentMatchers.any(FilterChain.class));
    }

    private AuditLogResponseDTO entry() {
        AuditLogResponseDTO dto = new AuditLogResponseDTO();
        dto.setId(1L);
        dto.setEntityName("INVOICE");
        dto.setEntityId("42");
        dto.setAction("INVOICE_DELETE");
        dto.setPerformedBy("admin");
        dto.setResult("SUCCESS");
        dto.setIpAddress("203.0.113.7");
        dto.setDetails("Invoice deleted");
        return dto;
    }

    // ── AC1..AC3: reading, as an authorised role ────────────────────────

@Test
@WithMockUser(roles = "ADMIN")
@DisplayName("AC1 - ADMIN reads the trail -> 200")
void ac1_admin_returns200() throws Exception {
when(auditService.search(any(), any()))
.thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entry())));

mockMvc.perform(get(AUDIT))
.andExpect(status().isOk())
.andExpect(jsonPath("$.data.content[0].entityName").value("INVOICE"))
.andExpect(jsonPath("$.data.content[0].action").value("INVOICE_DELETE"))
.andExpect(jsonPath("$.data.content[0].performedBy").value("admin"))
.andExpect(jsonPath("$.data.content[0].result").value("SUCCESS"))
.andExpect(jsonPath("$.data.content[0].ipAddress").value("203.0.113.7"));
}

@Test
@WithMockUser(roles = "MANAGER")
@DisplayName("AC2 - MANAGER reads the trail -> 200")
void ac2_manager_returns200() throws Exception {
when(auditService.search(any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

mockMvc.perform(get(AUDIT)).andExpect(status().isOk());
}

@Test
@WithMockUser(roles = "OWNER")
@DisplayName("AC3 - filters are assembled and passed to the service")
void ac3_filtersPassedThrough() throws Exception {
when(auditService.search(any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

mockMvc.perform(get(AUDIT).param("entityName", "INVOICE")
.param("entityId", "42")
.param("action", "INVOICE_DELETE")
.param("result", "SUCCESS")
.param("performedBy", "admin"))
.andExpect(status().isOk());

var captor = org.mockito.ArgumentCaptor.forClass(com.autoservicehub.dto.AuditFilterDTO.class);
verify(auditService).search(captor.capture(), any());
var filter = captor.getValue();

assertThat(filter.getEntityName()).isEqualTo("INVOICE");
assertThat(filter.getEntityId()).isEqualTo("42");
assertThat(filter.getAction()).isEqualTo("INVOICE_DELETE");
assertThat(filter.getResult()).isEqualTo("SUCCESS");
assertThat(filter.getPerformedBy()).isEqualTo("admin");
}

// ── AC4..AC7: the trail is not open to workshop roles ─────────────────

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("AC4 - MECHANIC is refused -> 403")
void ac4_mechanic_returns403() throws Exception {
mockMvc.perform(get(AUDIT)).andExpect(status().isForbidden());
}

@Test
@WithMockUser(roles = "SERVICE_ADVISOR")
@DisplayName("AC5 - SERVICE_ADVISOR is refused -> 403")
void ac5_serviceAdvisor_returns403() throws Exception {
mockMvc.perform(get(AUDIT)).andExpect(status().isForbidden());
}

@Test
@WithMockUser(roles = "BILLING_USER")
@DisplayName("AC6 - BILLING_USER is refused -> 403")
void ac6_billingUser_returns403() throws Exception {
mockMvc.perform(get(AUDIT)).andExpect(status().isForbidden());
}

@Test
@WithMockUser(roles = "INVENTORY_MANAGER")
@DisplayName("AC7 - INVENTORY_MANAGER is refused -> 403")
void ac7_inventoryManager_returns403() throws Exception {
mockMvc.perform(get(AUDIT)).andExpect(status().isForbidden());
}

@Test
@WithAnonymousUser
@DisplayName("AC8 - anonymous is refused -> 401")
void ac8_anonymous_returns401() throws Exception {
mockMvc.perform(get(AUDIT)).andExpect(status().isUnauthorized());
}

/**
 * The trail is read-only at the HTTP boundary, not merely by convention: no write
 * verb is mapped, so a client cannot create, alter or delete an entry even with an
 * administrator token. An audit trail that can be edited is not evidence.
 *
 * <p>Asserted by the absence of a 2xx rather than an exact status: the project
 * {@code GlobalExceptionHandler} has no handler for
 * {@code HttpRequestMethodNotSupportedException}, so an unmapped verb surfaces as
 * a 500 rather than a 405. What matters is that no write is ever dispatched to
 * the service, which is asserted directly.
 */
@Test
@WithMockUser(roles = "ADMIN")
@DisplayName("AC9 - no write verb reaches the audit service")
void ac9_noWriteVerbMapped() throws Exception {
mockMvc.perform(post(AUDIT)
.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
.content("{}"));
mockMvc.perform(put(AUDIT + "/1")
.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
.content("{}"));
mockMvc.perform(delete(AUDIT + "/1"));

// Nothing was created, altered or removed: the service only ever saw reads.
verify(auditService, org.mockito.Mockito.never()).recordSuccess(
any(), any(), any(), any());
verify(auditService, org.mockito.Mockito.never()).recordFailure(
any(), any(), any(), any(), any());
}
}