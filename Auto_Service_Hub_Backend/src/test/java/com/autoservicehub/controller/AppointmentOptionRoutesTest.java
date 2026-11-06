package com.autoservicehub.controller;

import com.autoservicehub.dto.AdvisorResponseDTO;
import com.autoservicehub.dto.AppointmentResponseDTO;
import com.autoservicehub.entity.Role;
import com.autoservicehub.entity.User;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.AppointmentService;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Routing test for the three literal sub-paths of /api/v1/appointments.
 *
 * <p>Regression guard for a real failure: a server that does not have these
 * mappings matches "advisors" against the existing {@code @GetMapping("/{id}")},
 * Spring cannot bind "advisors" to a {@code Long id}, and the request fails
 * with 400 "Parameter 'id' has an invalid value." — which the frontend then
 * reported as an advisors error plus a silent fallback for bays and types.
 *
 * <p>Each case asserts the literal path reaches its own handler, so a 400 on
 * any of them now fails the build instead of only failing in the UI.
 */
@WebMvcTest(controllers = AppointmentController.class)
@Import({AppointmentOptionRoutesTest.TestSecurityConfig.class, CustomUserDetailsService.class})
class AppointmentOptionRoutesTest {

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
    private AppointmentService appointmentService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserRepository userRepository;

    private void givenServiceAdvisor() {
        givenRole("SERVICE_ADVISOR", "advisor");
    }

    private void givenRole(String roleName, String username) {
        Role role = new Role();
        role.setName(roleName);
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPasswordHash("encoded");
        user.setActive(true);
        user.setRole(role);

        when(jwtTokenProvider.validateToken("test-token")).thenReturn(true);
        when(jwtTokenProvider.getUsername("test-token")).thenReturn(username);
        when(userRepository.findByUsernameIgnoreCase(username)).thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase(username + "@example.invalid"))
                .thenReturn(Optional.empty());
    }

    private AdvisorResponseDTO advisor(Long id, String name) {
        AdvisorResponseDTO dto = new AdvisorResponseDTO();
        dto.setId(id);
        dto.setName(name);
        return dto;
    }

    @Test
    void advisorsRouteIsNotCapturedById() throws Exception {
        givenServiceAdvisor();
        when(appointmentService.listAdvisors()).thenReturn(List.of(advisor(7L, "Ramesh K.")));

        mockMvc.perform(get("/api/v1/appointments/advisors")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].name").value("Ramesh K."));
    }

    @Test
    void baysRouteIsNotCapturedById() throws Exception {
        givenServiceAdvisor();
        when(appointmentService.availableBaySlots())
                .thenReturn(List.of("Bay 1", "Bay 2", "Bay 3", "Bay 4"));

        mockMvc.perform(get("/api/v1/appointments/bays")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("Bay 1"))
                .andExpect(jsonPath("$.data[3]").value("Bay 4"));
    }

    @Test
    void typesRouteIsNotCapturedById() throws Exception {
        givenServiceAdvisor();
        when(appointmentService.supportedAppointmentTypes())
                .thenReturn(List.of("WALK_IN", "ONLINE", "PICKUP_DROP"));

        mockMvc.perform(get("/api/v1/appointments/types")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("WALK_IN"))
                .andExpect(jsonPath("$.data[2]").value("PICKUP_DROP"));
    }

    /** Guards the other direction: /{id} must still reach getById, not the literals. */
    @Test
    void numericIdRouteStillResolvesToGetById() throws Exception {
        givenServiceAdvisor();
        when(appointmentService.getById(42L)).thenReturn(new AppointmentResponseDTO());

        mockMvc.perform(get("/api/v1/appointments/42")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());
    }

    /**
     * The fix itself: a non-numeric segment must no longer be handed to the
     * Long path variable.
     *
     * <p>Before the {@code \d+} constraint this request was routed to
     * {@code getById} and answered 400 "Parameter 'id' has an invalid
     * value.". What status it returns now is the application's pre-existing
     * unmatched-route behaviour (GlobalExceptionHandler's catch-all), which is
     * deliberately not asserted here — changing it is out of scope for a
     * routing fix. What matters is that the id handler is never invoked and
     * the old binding error is gone.
     */
    @Test
    void nonNumericSegmentIsNotOfferedToTheIdRoute() throws Exception {
        givenServiceAdvisor();

        String body = mockMvc.perform(get("/api/v1/appointments/not-a-number")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().is(not(400)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("has an invalid value");
        verify(appointmentService, never()).getById(anyLong());
    }

    /** Update and delete share the id route shape and must keep working. */
    @Test
    void numericIdUpdateRouteStillWorks() throws Exception {
        givenServiceAdvisor();
        when(appointmentService.update(eq(7L), any())).thenReturn(new AppointmentResponseDTO());

        mockMvc.perform(put("/api/v1/appointments/7")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":1,\"vehicleId\":2,\"serviceType\":\"Basic Service\","
                                + "\"appointmentAt\":\"2026-05-06T09:15:00\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void numericIdDeleteRouteStillWorks() throws Exception {
        givenServiceAdvisor();

        mockMvc.perform(delete("/api/v1/appointments/7")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isNoContent());

        verify(appointmentService).delete(7L);
    }

    /** Authorization is unchanged: only roles that may book can read these lists. */
    @Test
    void optionRoutesRejectAnUnauthorisedRole() throws Exception {
        givenRole("INVENTORY_MANAGER", "storekeeper");

        mockMvc.perform(get("/api/v1/appointments/advisors")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isForbidden());
    }

    /** Still authenticated-only: no anonymous reader is created. */
    @Test
    void optionRoutesRejectAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/advisors"))
                .andExpect(status().isUnauthorized());
    }
}