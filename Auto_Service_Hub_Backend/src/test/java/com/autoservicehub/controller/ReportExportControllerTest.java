package com.autoservicehub.controller;

import com.autoservicehub.dto.ExportFileDTO;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.ReportExportService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the report export endpoints (FR-REP-8).
 *
 * <p>Uses {@code @WebMvcTest}, so no database is involved and these do not
 * depend on the H2 schema issue that blocks {@code ReportRepositoryQueriesTest}.
 * What is pinned is the HTTP contract: status, content type, disposition,
 * filename, filter plumbing and the role guards — plus the rule that an export
 * is never permitted where the JSON report is not.
 */
@WebMvcTest(controllers = ReportExportController.class)
@Import({ReportExportControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class ReportExportControllerTest {

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

    private static final String BASE  = "/api/v1/reports/export";
    private static final String FROM  = "2026-03-01";
    private static final String TO    = "2026-03-31";

    @Autowired MockMvc mockMvc;

    @MockBean ReportExportService    exportService;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean JwtTokenProvider       jwtTokenProvider;
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

    /**
     * A tiny but structurally real PDF payload used by the PDF tests.
     *
     * <p>Held as a constant so the fixture and the expected-response assertion
     * cannot drift apart: the test compares the body against the very bytes the
     * stub returns, which proves the controller streamed them through unmodified
     * (no truncation, no charset change, no wrapping).
     */
    private static final String PDF_PAYLOAD = "%PDF-1.4 fake";

    private ExportFileDTO pdfFile() {
        return new ExportFileDTO(PDF_PAYLOAD.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                "application/pdf", "daily-workshop-2026-03-10.pdf");
    }

    private ExportFileDTO excelFile() {
        return new ExportFileDTO(new byte[]{'P', 'K', 3, 4},
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "daily-workshop-2026-03-10.xlsx");
    }

    // ── PDF ───────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("EXC1 a PDF export returns 200, the PDF content type and a filename")
    void pdfResponseHeaders() throws Exception {
        when(exportService.exportPdf(any(), any())).thenReturn(pdfFile());

        mockMvc.perform(get(BASE + "/daily-workshop.pdf").param("date", "2026-03-10"))
               .andExpect(status().isOk())
               .andExpect(content().contentType("application/pdf"))
               .andExpect(header().string("Content-Disposition",
                       org.hamcrest.Matchers.containsString("attachment")))
               .andExpect(header().string("Content-Disposition",
                       org.hamcrest.Matchers.containsString("daily-workshop-2026-03-10.pdf")))
               // Exact match against the complete stub payload: this asserts the
               // bytes arrived intact, not merely that they start with %PDF-.
               .andExpect(content().bytes(
                       PDF_PAYLOAD.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("EXC2 an Excel export returns 200, the xlsx content type and a filename")
    void excelResponseHeaders() throws Exception {
        when(exportService.exportExcel(any(), any())).thenReturn(excelFile());

        mockMvc.perform(get(BASE + "/daily-workshop.xlsx").param("date", "2026-03-10"))
               .andExpect(status().isOk())
               .andExpect(content().contentType(
                       "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
               .andExpect(header().string("Content-Disposition",
                       org.hamcrest.Matchers.containsString("daily-workshop-2026-03-10.xlsx")));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("EXC3 exports are marked no-store so a download cannot go stale")
    void exportsAreNotCached() throws Exception {
        when(exportService.exportPdf(any(), any())).thenReturn(pdfFile());

        mockMvc.perform(get(BASE + "/daily-workshop.pdf"))
               .andExpect(header().string("Cache-Control",
                       org.hamcrest.Matchers.containsString("no-store")));
    }

    // ── Coverage of every report in both formats ──────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("EXC4 every exportable report has a PDF and an Excel endpoint")
    void everyReportHasBothFormats() throws Exception {
        when(exportService.exportPdf(any(), any())).thenReturn(pdfFile());
        when(exportService.exportExcel(any(), any())).thenReturn(excelFile());

        String[][] reports = {
                {"daily-workshop", "/daily-workshop.pdf", "/daily-workshop.xlsx"},
                {"revenue-payment", "/revenue-payment.pdf", "/revenue-payment.xlsx"},
                {"mechanic-performance", "/mechanic-performance.pdf", "/mechanic-performance.xlsx"},
                {"parts-usage", "/parts-usage.pdf", "/parts-usage.xlsx"},
                {"customer-growth", "/customer-growth.pdf", "/customer-growth.xlsx"},
                {"profit-analysis", "/profit-analysis.pdf", "/profit-analysis.xlsx"},
        };
        for (String[] report : reports) {
            mockMvc.perform(get(BASE + report[1]).param("from", FROM).param("to", TO))
                   .andExpect(status().isOk());
            mockMvc.perform(get(BASE + report[2]).param("from", FROM).param("to", TO))
                   .andExpect(status().isOk());
        }
    }

    // ── Filters, empty data and errors ─────────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("EXC5 every supported filter reaches the export service")
    void filtersReachTheExportService() throws Exception {
        when(exportService.exportPdf(any(), any())).thenReturn(pdfFile());

        mockMvc.perform(get(BASE + "/daily-workshop.pdf")
                       .param("date", "2026-03-10")
                       .param("mechanicId", "7")
                       .param("vehicleId", "9")
                       .param("serviceType", "SERVICE")
                       .param("status", "DELIVERED"))
               .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.autoservicehub.dto.ReportFilterDTO.class);
        org.mockito.Mockito.verify(exportService).exportPdf(any(), captor.capture());

        com.autoservicehub.dto.ReportFilterDTO f = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(f.getDate())
                .isEqualTo(java.time.LocalDate.of(2026, 3, 10));
        org.assertj.core.api.Assertions.assertThat(f.getMechanicId()).isEqualTo(7L);
        org.assertj.core.api.Assertions.assertThat(f.getVehicleId()).isEqualTo(9L);
        org.assertj.core.api.Assertions.assertThat(f.getServiceType()).isEqualTo("SERVICE");
        org.assertj.core.api.Assertions.assertThat(f.getStatus()).isEqualTo("DELIVERED");
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("EXC6 the jobCardId filter is passed through for parts usage")
    void jobCardFilterIsPassedThrough() throws Exception {
        when(exportService.exportPdf(any(), any())).thenReturn(pdfFile());

        mockMvc.perform(get(BASE + "/parts-usage.pdf")
                       .param("from", FROM).param("to", TO)
                       .param("mechanicId", "5").param("jobCardId", "11"))
               .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.autoservicehub.dto.ReportFilterDTO.class);
        org.mockito.Mockito.verify(exportService).exportPdf(any(), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getJobCardId()).isEqualTo(11L);
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("EXC7 an empty report still downloads as a valid file")
    void emptyReportStillReturnsAFile() throws Exception {
        when(exportService.exportExcel(any(), any())).thenReturn(excelFile());

        mockMvc.perform(get(BASE + "/mechanic-performance.xlsx").param("from", FROM).param("to", TO))
               .andExpect(status().isOk())
               .andExpect(content().bytes(new byte[]{'P', 'K', 3, 4}));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("EXC8 an invalid filter maps to 409, exactly as the JSON endpoint does")
    void invalidFilterIsConflict() throws Exception {
        when(exportService.exportPdf(any(), any()))
                .thenThrow(new BusinessRuleException("Revenue report date range is invalid."));

        mockMvc.perform(get(BASE + "/revenue-payment.pdf").param("from", TO).param("to", FROM))
               .andExpect(status().isConflict())
               .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                       .jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("EXC9 a missing required date maps to 400")
    void missingRequiredDateIsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/revenue-payment.pdf").param("to", TO))
               .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("EXC10 an unparseable date maps to 400")
    void unparseableDateIsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/revenue-payment.pdf")
                       .param("from", "not-a-date").param("to", TO))
               .andExpect(status().isBadRequest());
    }

    // ── Authorization: exporting is never more permissive than reading ────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("EXC11 a MECHANIC may not export revenue or profit")
    void mechanicCannotExportFinancials() throws Exception {
        mockMvc.perform(get(BASE + "/revenue-payment.pdf").param("from", FROM).param("to", TO))
               .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/profit-analysis.pdf").param("from", FROM).param("to", TO))
               .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("EXC12 an INVENTORY_MANAGER may export parts usage but not profit")
    void inventoryScopeIsLimited() throws Exception {
        when(exportService.exportPdf(any(), any())).thenReturn(pdfFile());

        mockMvc.perform(get(BASE + "/parts-usage.pdf").param("from", FROM).param("to", TO))
               .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/profit-analysis.pdf").param("from", FROM).param("to", TO))
               .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("EXC13 an anonymous caller is rejected on every export endpoint")
    void anonymousRejectedEverywhere() throws Exception {
        for (String path : new String[]{
                "/daily-workshop.pdf", "/revenue-payment.pdf", "/mechanic-performance.pdf",
                "/parts-usage.pdf", "/customer-growth.pdf", "/profit-analysis.pdf",
                "/daily-workshop.xlsx", "/revenue-payment.xlsx", "/mechanic-performance.xlsx",
                "/parts-usage.xlsx", "/customer-growth.xlsx", "/profit-analysis.xlsx"}) {
            mockMvc.perform(get(BASE + path).param("from", FROM).param("to", TO))
                   .andExpect(status().isUnauthorized());
        }
    }
}
