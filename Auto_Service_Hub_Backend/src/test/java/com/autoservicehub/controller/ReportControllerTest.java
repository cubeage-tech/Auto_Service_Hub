package com.autoservicehub.controller;

import com.autoservicehub.dto.*;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.ReportService;
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
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the report endpoints.
 *
 * <p>Uses {@code @WebMvcTest}, so no database is involved and these are unaffected
 * by the H2 schema issue that blocks {@code ReportRepositoryQueriesTest}. What is
 * pinned here is the HTTP contract: routes, filter parameters reaching the
 * service, error mapping and the role guards.
 */
@WebMvcTest(controllers = ReportController.class)
@Import({ReportControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class ReportControllerTest {

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

    private static final String BASE = "/api/v1/reports";
    private static final String FROM = "2026-03-01";
    private static final String TO   = "2026-03-31";

    @Autowired MockMvc mockMvc;

    @MockBean ReportService          service;
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

    // ── Fixtures ──────────────────────────────────────────────────────────

    private DailyWorkshopReportDTO dailyWorkshop() {
        DailyWorkshopReportDTO d = new DailyWorkshopReportDTO();
        d.setDate(LocalDate.of(2026, 3, 10));
        d.setTotalJobs(5L);
        d.setCompletedJobs(3L);
        d.setPendingJobs(2L);
        d.getStatusBreakdown().put("DELIVERED", 3L);
        d.getStatusBreakdown().put("IN_REPAIR", 2L);
        d.setInvoicedAmount(new BigDecimal("1000.00"));
        d.setCollectedAmount(new BigDecimal("500.00"));
        return d;
    }

    private RevenuePaymentReportDTO revenue() {
        RevenuePaymentReportDTO d = new RevenuePaymentReportDTO();
        d.setFrom(LocalDate.parse(FROM));
        d.setTo(LocalDate.parse(TO));
        d.setInvoicedRevenue(new BigDecimal("5000.00"));
        d.setInvoiceCount(4L);
        d.setCollectedAmount(new BigDecimal("3000.00"));
        d.setPaymentCount(3L);
        d.setOutstandingAmount(new BigDecimal("2000.00"));
        return d;
    }

    private MechanicPerformanceReportDTO mechanic() {
        MechanicPerformanceReportDTO d = new MechanicPerformanceReportDTO();
        d.setMechanicId(1L);
        d.setMechanicName("Anil");
        d.setEmployeeCode("MECH-1");
        d.setAssignedJobs(5L);
        d.setCompletedJobs(3L);
        d.setOpenJobs(2L);
        d.setCompletionRate(new BigDecimal("0.6000"));
        d.setTotalRevenue(new BigDecimal("6000.00"));
        d.setAverageCustomerRating(4.5);
        return d;
    }

    private PartsUsageReportDTO partsUsage() {
        PartsUsageReportDTO d = new PartsUsageReportDTO();
        d.setFrom(LocalDate.parse(FROM));
        d.setTo(LocalDate.parse(TO));
        d.setDistinctPartCount(1L);
        d.setTotalQuantityConsumed(4L);
        d.setTotalEstimatedCost(new BigDecimal("2000.00"));
        d.setMethodology("Counted from StockMovement rows of type OUT only.");
        PartsUsageRowDTO row = new PartsUsageRowDTO();
        row.setPartId(1L);
        row.setSku("BRK-1");
        row.setPartName("Brake Pad");
        row.setQuantityConsumed(4L);
        d.setParts(List.of(row));
        return d;
    }

    private CustomerGrowthReportDTO growth() {
        return new CustomerGrowthReportDTO(LocalDate.parse(FROM), LocalDate.parse(TO),
                3L, 120L, 5L, 8L, 10L, new BigDecimal("0.6250"), "notes");
    }

    private ProfitAnalysisReportDTO profit() {
        ProfitAnalysisReportDTO d = new ProfitAnalysisReportDTO();
        d.setFrom(LocalDate.parse(FROM));
        d.setTo(LocalDate.parse(TO));
        d.setInvoicedRevenue(new BigDecimal("10000.00"));
        d.setCollectedAmount(new BigDecimal("8000.00"));
        d.setPartsCostEstimate(new BigDecimal("2500.00"));
        d.setProfitAvailable(false);
        d.setGrossProfit(null);
        d.setLimitations(List.of("Labour cost is unattributable."));
        return d;
    }

    // ── Routes and payload shapes ─────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("RC1 the daily workshop report returns totals and the status breakdown")
    void dailyWorkshopRoute() throws Exception {
        when(service.getDailyWorkshopReport(any())).thenReturn(dailyWorkshop());

        mockMvc.perform(get(BASE + "/daily-workshop").param("date", "2026-03-10"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.totalJobs").value(5))
               .andExpect(jsonPath("$.data.completedJobs").value(3))
               .andExpect(jsonPath("$.data.pendingJobs").value(2))
               .andExpect(jsonPath("$.data.statusBreakdown.DELIVERED").value(3))
               .andExpect(jsonPath("$.data.statusBreakdown.IN_REPAIR").value(2));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("RC2 the revenue report separates invoiced from collected")
    void revenueRoute() throws Exception {
        when(service.getRevenuePaymentReport(any())).thenReturn(revenue());

        mockMvc.perform(get(BASE + "/revenue").param("from", FROM).param("to", TO))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.invoicedRevenue").value(5000.00))
               .andExpect(jsonPath("$.data.invoiceCount").value(4))
               .andExpect(jsonPath("$.data.collectedAmount").value(3000.00))
               .andExpect(jsonPath("$.data.paymentCount").value(3))
               .andExpect(jsonPath("$.data.outstandingAmount").value(2000.00));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("RC3 mechanic performance returns a list of mechanics")
    void mechanicPerformanceRoute() throws Exception {
        when(service.getMechanicPerformanceReport(any())).thenReturn(List.of(mechanic()));

        mockMvc.perform(get(BASE + "/mechanic-performance").param("from", FROM).param("to", TO))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data[0].mechanicName").value("Anil"))
               .andExpect(jsonPath("$.data[0].employeeCode").value("MECH-1"))
               .andExpect(jsonPath("$.data[0].assignedJobs").value(5))
               .andExpect(jsonPath("$.data[0].completedJobs").value(3))
               .andExpect(jsonPath("$.data[0].averageCustomerRating").value(4.5));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("RC4 parts usage returns quantity consumed and its methodology")
    void partsUsageRoute() throws Exception {
        when(service.getPartsUsageReport(any())).thenReturn(partsUsage());

        mockMvc.perform(get(BASE + "/parts-usage").param("from", FROM).param("to", TO))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.totalQuantityConsumed").value(4))
               .andExpect(jsonPath("$.data.parts[0].sku").value("BRK-1"))
               .andExpect(jsonPath("$.data.methodology").exists());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("RC5 customer growth returns new and repeat counts")
    void customerGrowthRoute() throws Exception {
        when(service.getCustomerGrowthReport(any())).thenReturn(growth());

        mockMvc.perform(get(BASE + "/customer-growth").param("from", FROM).param("to", TO))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.newCustomerCount").value(3))
               .andExpect(jsonPath("$.data.repeatCustomerCount").value(5))
               .andExpect(jsonPath("$.data.customersServedInPeriod").value(8));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("RC6 profit analysis states that profit is unavailable")
    void profitAnalysisRoute() throws Exception {
        when(service.getProfitAnalysisReport(any())).thenReturn(profit());

        mockMvc.perform(get(BASE + "/profit-analysis").param("from", FROM).param("to", TO))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.invoicedRevenue").value(10000.00))
               .andExpect(jsonPath("$.data.partsCostEstimate").value(2500.00))
               .andExpect(jsonPath("$.data.profitAvailable").value(false))
               .andExpect(jsonPath("$.data.grossProfit").doesNotExist())
               .andExpect(jsonPath("$.data.limitations[0]").exists());
    }

    // ── Filters ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("RC7 date, mechanic, vehicle, service and status filters reach the service")
    void filtersReachTheService() throws Exception {
        when(service.getDailyWorkshopReport(any())).thenReturn(dailyWorkshop());

        mockMvc.perform(get(BASE + "/daily-workshop")
                       .param("date", "2026-03-10")
                       .param("mechanicId", "7")
                       .param("vehicleId", "9")
                       .param("serviceType", "SERVICE")
                       .param("status", "DELIVERED"))
               .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ReportFilterDTO> captor =
                org.mockito.ArgumentCaptor.forClass(ReportFilterDTO.class);
        verify(service).getDailyWorkshopReport(captor.capture());

        ReportFilterDTO f = captor.getValue();
        assert f.getDate().equals(LocalDate.of(2026, 3, 10));
        assert f.getMechanicId() == 7L;
        assert f.getVehicleId() == 9L;
        assert "SERVICE".equals(f.getServiceType());
        assert "DELIVERED".equals(f.getStatus());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("RC8 the parts usage mechanic and job-card filters are both accepted")
    void partsUsageFiltersReachTheService() throws Exception {
        when(service.getPartsUsageReport(any())).thenReturn(partsUsage());

        mockMvc.perform(get(BASE + "/parts-usage")
                       .param("from", FROM).param("to", TO)
                       .param("mechanicId", "5").param("jobCardId", "11"))
               .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ReportFilterDTO> captor =
                org.mockito.ArgumentCaptor.forClass(ReportFilterDTO.class);
        verify(service).getPartsUsageReport(captor.capture());
        assert captor.getValue().getMechanicId() == 5L;
        assert captor.getValue().getJobCardId() == 11L;
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("RC9 the mechanic filter reaches the mechanic performance report")
    void mechanicFilterReachesService() throws Exception {
        when(service.getMechanicPerformanceReport(any())).thenReturn(List.of(mechanic()));

        mockMvc.perform(get(BASE + "/mechanic-performance")
                       .param("from", FROM).param("to", TO).param("mechanicId", "3"))
               .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ReportFilterDTO> captor =
                org.mockito.ArgumentCaptor.forClass(ReportFilterDTO.class);
        verify(service).getMechanicPerformanceReport(captor.capture());
        assert captor.getValue().getMechanicId() == 3L;
    }

    // ── Validation and empty results ──────────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("RC10 an invalid date range maps to 409")
    void invalidRangeIsConflict() throws Exception {
        when(service.getRevenuePaymentReport(any()))
                .thenThrow(new BusinessRuleException("Revenue report date range is invalid."));

        mockMvc.perform(get(BASE + "/revenue").param("from", TO).param("to", FROM))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("RC11 a missing required date maps to 400")
    void missingDateIsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/revenue").param("to", TO))
               .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("RC12 an empty report is a 200 with zeroes, not an error")
    void emptyReportIsStillTwoHundred() throws Exception {
        when(service.getRevenuePaymentReport(any())).thenReturn(new RevenuePaymentReportDTO());
        when(service.getPartsUsageReport(any())).thenReturn(new PartsUsageReportDTO());
        when(service.getMechanicPerformanceReport(any())).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/revenue").param("from", FROM).param("to", TO))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.invoicedRevenue").value(0));
        mockMvc.perform(get(BASE + "/parts-usage").param("from", FROM).param("to", TO))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.parts").isEmpty());
        mockMvc.perform(get(BASE + "/mechanic-performance").param("from", FROM).param("to", TO))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data").isEmpty());
    }

    // ── Authorization ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("RC13 a MECHANIC may not read revenue")
    void mechanicCannotReadRevenue() throws Exception {
        mockMvc.perform(get(BASE + "/revenue").param("from", FROM).param("to", TO))
               .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("RC14 a MECHANIC may not read profit analysis")
    void mechanicCannotReadProfit() throws Exception {
        mockMvc.perform(get(BASE + "/profit-analysis").param("from", FROM).param("to", TO))
               .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("RC15 an INVENTORY_MANAGER may read parts usage but not profit")
    void inventoryScopeIsLimited() throws Exception {
        when(service.getPartsUsageReport(any())).thenReturn(partsUsage());

        mockMvc.perform(get(BASE + "/parts-usage").param("from", FROM).param("to", TO))
               .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/profit-analysis").param("from", FROM).param("to", TO))
               .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("RC16 an anonymous caller is rejected with 401 on every report")
    void anonymousRejectedEverywhere() throws Exception {
        mockMvc.perform(get(BASE + "/daily-workshop")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/revenue").param("from", FROM).param("to", TO))
               .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/parts-usage").param("from", FROM).param("to", TO))
               .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/customer-growth").param("from", FROM).param("to", TO))
               .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/profit-analysis").param("from", FROM).param("to", TO))
               .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/mechanic-performance").param("from", FROM).param("to", TO))
               .andExpect(status().isUnauthorized());
    }
}
