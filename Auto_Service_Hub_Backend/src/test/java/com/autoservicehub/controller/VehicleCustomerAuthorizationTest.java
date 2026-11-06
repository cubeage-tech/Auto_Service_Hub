package com.autoservicehub.controller;

import com.autoservicehub.entity.Role;
import com.autoservicehub.entity.User;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.CustomerService;
import com.autoservicehub.service.JobCardService;
import com.autoservicehub.service.JobTaskService;
import com.autoservicehub.service.MechanicService;
import com.autoservicehub.service.ReportService;
import com.autoservicehub.service.StockMovementService;
import com.autoservicehub.service.VehicleService;
import com.autoservicehub.service.impl.VehicleHistoryDocumentService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        VehicleController.class,
        CustomerController.class,
        MechanicController.class,
        JobCardController.class,
        DashboardController.class,
        ReportController.class
})
@Import({VehicleCustomerAuthorizationTest.TestSecurityConfig.class, CustomUserDetailsService.class})
class VehicleCustomerAuthorizationTest {

    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(
                HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
            http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((req, res, e) ->
                        res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                    .accessDeniedHandler((req, res, e) ->
                        res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VehicleService vehicleService;

    @MockBean
    private VehicleHistoryDocumentService vehicleHistoryDocumentService;

    @MockBean
    private CustomerService customerService;

    @MockBean
    private MechanicService mechanicService;

    @MockBean
    private JobCardService jobCardService;

    @MockBean
    private JobTaskService jobTaskService;

    @MockBean
    private StockMovementService stockMovementService;

    @MockBean
    private ReportService reportService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserRepository userRepository;

    @Test
    void garageOwnerTokenCanAccessGarageOwnerEndpoints() throws Exception {
        givenAuthenticatedUserWithRole("GARAGE_OWNER");

        for (String path : garageOwnerGetPaths()) {
            mockMvc.perform(get(path)
                            .param("from", "2026-03-01")
                            .param("to", "2026-03-31")
                            .header("Authorization", "Bearer test-token"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void customerTokenCannotAccessGarageOwnerEndpoints() throws Exception {
        givenAuthenticatedUserWithRole("CUSTOMER");

        for (String path : garageOwnerGetPaths()) {
            mockMvc.perform(get(path)
                            .param("from", "2026-03-01")
                            .param("to", "2026-03-31")
                            .header("Authorization", "Bearer test-token"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void garageOwnerCanUseCombinedVehicleStatusAndFuelFilters() throws Exception {
        givenAuthenticatedUserWithRole("GARAGE_OWNER");

        mockMvc.perform(get("/api/v1/vehicles")
                        .param("fuelType", "PETROL")
                        .param("status", "IN_SERVICE")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());
    }

    @Test
    void customerCannotUseVehicleStatusAndFuelFilters() throws Exception {
        givenAuthenticatedUserWithRole("CUSTOMER");

        mockMvc.perform(get("/api/v1/vehicles")
                        .param("fuelType", "PETROL")
                        .param("status", "IN_SERVICE")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUserCannotListVehiclesOrCustomers() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized());
    }

    private void givenAuthenticatedUserWithRole(String roleName) {
        Role role = new Role();
        role.setName(roleName);

        User user = new User();
        user.setUsername("garage-owner");
        user.setEmail("owner@example.invalid");
        user.setPasswordHash("encoded-password");
        user.setActive(true);
        user.setRole(role);

        when(jwtTokenProvider.validateToken("test-token")).thenReturn(true);
        when(jwtTokenProvider.getUsername("test-token")).thenReturn("garage-owner");
        when(userRepository.findByUsernameIgnoreCase("garage-owner"))
                .thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase("garage-owner"))
                .thenReturn(Optional.empty());
    }

    private String[] garageOwnerGetPaths() {
        return new String[] {
                "/api/v1/vehicles",
                "/api/v1/customers",
                "/api/v1/mechanics",
                "/api/v1/job-cards",
                "/api/v1/dashboard/summary",
                "/api/v1/reports/revenue",
                "/api/v1/reports/ai-insights"
        };
    }
}
