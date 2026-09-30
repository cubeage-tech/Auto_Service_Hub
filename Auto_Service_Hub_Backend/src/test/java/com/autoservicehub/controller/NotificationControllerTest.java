package com.autoservicehub.controller;

import com.autoservicehub.dto.NotificationResponseDTO;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.NotificationService;
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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the per-user notification endpoints on
 * {@code NotificationController}.
 *
 * <p>These endpoints deliberately have no path parameter naming a user: the
 * controller always operates on the authenticated principal. What is pinned
 * here is that the routes exist, return the expected shape, map a not-found to
 * 404, and reject anonymous callers with 401.
 *
 * Test cases
 * ----------
 * NT1  list my notifications        → 200 with the page payload
 * NT2  list unread                  → 200
 * NT3  unread count                 → 200 with unreadCount
 * NT4  mark one as read             → 200, read = true
 * NT5  mark all as read             → 200 with updated count
 * NT6  somebody else's notification → 404 (service-scoped, so no 403 leak)
 * NT7  anonymous list               → 401
 * NT8  anonymous mark as read       → 401
 * NT9  anonymous unread count       → 401
 */
@WebMvcTest(controllers = NotificationController.class)
@Import({NotificationControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class NotificationControllerTest {

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

    private static final String BASE = "/api/v1/notifications";

    @Autowired MockMvc mockMvc;

    @MockBean NotificationService      service;
    @MockBean JwtAuthenticationFilter   jwtAuthenticationFilter;
    @MockBean JwtTokenProvider          jwtTokenProvider;
    @MockBean CustomUserDetailsService  customUserDetailsService;

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

    private NotificationResponseDTO notificationResponse(boolean read) {
        NotificationResponseDTO dto = new NotificationResponseDTO();
        dto.setId(7L);
        dto.setChannel("IN_APP");
        dto.setTitle("Follow-up due");
        dto.setMessage("Follow-up 42 is due");
        dto.setStatus(read ? "READ" : "UNREAD");
        dto.setRead(read);
        dto.setReferenceType("FOLLOWUP");
        dto.setReferenceId(42L);
        return dto;
    }

    // ── Listing ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("NT1 listing my notifications returns the page payload")
    void listsMyNotifications() throws Exception {
        when(service.listMine(any()))
                .thenReturn(new PageImpl<>(List.of(notificationResponse(false)),
                                           PageRequest.of(0, 20), 1));

        mockMvc.perform(get(BASE))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.content[0].id").value(7))
               .andExpect(jsonPath("$.data.content[0].title").value("Follow-up due"))
               .andExpect(jsonPath("$.data.content[0].read").value(false))
               .andExpect(jsonPath("$.data.content[0].referenceType").value("FOLLOWUP"))
               .andExpect(jsonPath("$.data.content[0].referenceId").value(42));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("NT2 listing unread notifications returns only unread ones")
    void listsUnread() throws Exception {
        when(service.listMineUnread(any()))
                .thenReturn(new PageImpl<>(List.of(notificationResponse(false)),
                                           PageRequest.of(0, 20), 1));

        mockMvc.perform(get(BASE + "/unread"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.content[0].read").value(false))
               .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("NT3 the unread count is returned as a plain number")
    void returnsUnreadCount() throws Exception {
        when(service.countMineUnread()).thenReturn(4L);

        mockMvc.perform(get(BASE + "/unread-count"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.unreadCount").value(4));
    }

    // ── Marking as read ───────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("NT4 marking one as read returns it with read = true")
    void marksOneAsRead() throws Exception {
        when(service.markAsRead(7L)).thenReturn(notificationResponse(true));

        mockMvc.perform(put(BASE + "/7/read"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.id").value(7))
               .andExpect(jsonPath("$.data.read").value(true))
               .andExpect(jsonPath("$.data.status").value("READ"));

        verify(service).markAsRead(7L);
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("NT5 marking all as read returns how many changed")
    void marksAllAsRead() throws Exception {
        when(service.markAllAsRead()).thenReturn(3);

        mockMvc.perform(put(BASE + "/read-all"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.updated").value(3));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("NT6 another user's notification is a 404, not a 403")
    void otherUsersNotificationIsNotFound() throws Exception {
        // The service scopes by owner, so somebody else's id simply is not found.
        when(service.markAsRead(999L))
                .thenThrow(new ResourceNotFoundException("Notification not found: 999"));

        mockMvc.perform(put(BASE + "/999/read"))
               .andExpect(status().isNotFound());
    }

    // ── Authorization ─────────────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("NT7 anonymous listing is rejected with 401")
    void anonymousListRejected() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("NT8 anonymous mark-as-read is rejected with 401")
    void anonymousMarkReadRejected() throws Exception {
        mockMvc.perform(put(BASE + "/7/read")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("NT9 anonymous unread count is rejected with 401")
    void anonymousCountRejected() throws Exception {
        mockMvc.perform(get(BASE + "/unread-count")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("any authenticated role may read its own notification box")
    void anyRoleMayUseIt() throws Exception {
        when(service.countMineUnread()).thenReturn(0L);

        mockMvc.perform(get(BASE + "/unread-count"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.unreadCount").value(0));
    }
}
