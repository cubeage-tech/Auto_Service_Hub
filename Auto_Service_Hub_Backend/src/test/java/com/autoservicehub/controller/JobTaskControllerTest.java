package com.autoservicehub.controller;

import com.autoservicehub.dto.JobTaskResponseDTO;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.JobCardService;
import com.autoservicehub.service.JobTaskService;
import com.autoservicehub.service.StockMovementService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the repair-task endpoints on
 * {@code JobCardController} and {@code JobTaskController}.
 *
 * <p>Uses {@code @WebMvcTest} with the project's minimal inner security
 * configuration (method security on, JWT filter not registered), matching
 * {@code StockMovementControllerTest}.
 *
 * Test cases
 * ----------
 * CT1  POST a task on a job card   -> 201
 * CT2  POST task, no description   -> 400
 * CT3  POST task, negative labour   -> 400
 * CT4  POST task, unknown job card  -> 404
 * CT5  POST task, unknown mechanic  -> 404
 * CT6  GET a task                   -> 200
 * CT7  GET tasks for a job card     -> 200
 * CT8  PUT a task                   -> 200
 * CT9  DELETE a task                -> 204
 * CT10 PUT task status              -> 200
 * CT11 PUT task status, invalid     -> 409
 * CT12 PUT task status, blank       -> 400
 * CT13 PUT work notes               -> 200
 * CT14 PUT mechanic assignment      -> 200
 * CT15 PUT mechanic, unknown        -> 404
 * CT16 GET labour total             -> 200
 * CT17 POST task by BILLING_USER    -> 403
 * CT18 DELETE task by MECHANIC      -> 403
 * CT19 Anonymous                    -> 401
 */
@WebMvcTest(controllers = {JobTaskController.class, JobCardController.class})
@Import({JobTaskControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class JobTaskControllerTest {

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

    private static final String TASKS_ON_CARD = "/api/v1/job-cards/55/tasks";
    private static final String LABOUR_TOTAL = "/api/v1/job-cards/55/tasks/labour-total";
    private static final String TASK_100     = "/api/v1/job-tasks/100";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean JobCardService       jobCardService;
    @MockBean JobTaskService       jobTaskService;
    @MockBean StockMovementService stockMovementService;
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

    private JobTaskResponseDTO taskResponse(String status) {
        JobTaskResponseDTO dto = new JobTaskResponseDTO();
        dto.setId(100L);
        dto.setJobCardId(55L);
        dto.setJobCardNumber("JC-20260115100000");
        dto.setMechanicId(7L);
        dto.setMechanicName("Anil");
        dto.setDescription("Replace front brake pads");
        dto.setStatus(status);
        dto.setLabourCost(new BigDecimal("500.00"));
        dto.setWorkNotes("Front axle lifted");
        return dto;
    }

    private String taskJson() {
        return """
                {
                  "description": "Replace front brake pads",
                  "mechanicId": 7,
                  "labourCost": 500.00
                }
                """;
    }

    // ── CT1..CT5: creating a task ──────────────────────────────────────

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT1 - POST a task on a job card -> 201")
void ct1_createTask_returns201() throws Exception {
    when(jobTaskService.create(anyLong(), any())).thenReturn(taskResponse("PENDING"));

    mockMvc.perform(post(TASKS_ON_CARD)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(taskJson()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(100))
            .andExpect(jsonPath("$.data.jobCardId").value(55))
            .andExpect(jsonPath("$.data.status").value("PENDING"));

    // The parent comes from the path, never the body.
    verify(jobTaskService).create(org.mockito.ArgumentMatchers.eq(55L), any());
}

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT2 - POST a task with no description -> 400")
void ct2_createTaskNoDescription_returns400() throws Exception {
    mockMvc.perform(post(TASKS_ON_CARD)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"labourCost\": 500.00 }"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
}

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT3 - POST a task with a negative labour cost -> 400")
void ct3_createTaskNegativeLabour_returns400() throws Exception {
    mockMvc.perform(post(TASKS_ON_CARD)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"description\": \"Bad\", \"labourCost\": -250.00 }"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
}

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT4 - POST a task on an unknown job card -> 404")
void ct4_createTaskUnknownJobCard_returns404() throws Exception {
    when(jobTaskService.create(anyLong(), any()))
            .thenThrow(new ResourceNotFoundException("JobCard not found: 55"));

    mockMvc.perform(post(TASKS_ON_CARD)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(taskJson()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
}

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT5 - POST a task assigned to an unknown mechanic -> 404")
void ct5_createTaskUnknownMechanic_returns404() throws Exception {
    when(jobTaskService.create(anyLong(), any()))
            .thenThrow(new ResourceNotFoundException("Mechanic not found: 404"));

    mockMvc.perform(post(TASKS_ON_CARD)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(taskJson()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
}

// ── CT6..CT9: read, update, delete ─────────────────────────────────

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT6 - GET a task -> 200")
void ct6_getTask_returns200() throws Exception {
    when(jobTaskService.getById(100L)).thenReturn(taskResponse("IN_PROGRESS"));

    mockMvc.perform(get(TASK_100))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.mechanicName").value("Anil"));
}

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT7 - GET a job card's tasks -> 200")
void ct7_listTasks_returns200() throws Exception {
    when(jobTaskService.listByJobCard(anyLong(), any()))
            .thenReturn(org.springframework.data.domain.Page.empty());

    mockMvc.perform(get(TASKS_ON_CARD)).andExpect(status().isOk());
}

@Test
@WithMockUser(roles = "SERVICE_ADVISOR")
@DisplayName("CT8 - PUT a task -> 200")
void ct8_updateTask_returns200() throws Exception {
    when(jobTaskService.update(anyLong(), any())).thenReturn(taskResponse("COMPLETED"));

    mockMvc.perform(put(TASK_100)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(taskJson()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));
}

@Test
@WithMockUser(roles = "SERVICE_ADVISOR")
@DisplayName("CT9 - DELETE a task -> 204")
void ct9_deleteTask_returns204() throws Exception {
    mockMvc.perform(delete(TASK_100)).andExpect(status().isNoContent());

    verify(jobTaskService).delete(100L);
}

// ── CT10..CT15: status, notes, mechanic ────────────────────────────

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT10 - PUT task status -> 200")
void ct10_updateStatus_returns200() throws Exception {
    when(jobTaskService.updateStatus(anyLong(), any())).thenReturn(taskResponse("COMPLETED"));

    mockMvc.perform(put(TASK_100 + "/status")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"status\": \"COMPLETED\" }"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));

    verify(jobTaskService).updateStatus(org.mockito.ArgumentMatchers.eq(100L), any());
}

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT11 - PUT an unsupported status -> 409")
void ct11_updateStatusInvalid_returns409() throws Exception {
    when(jobTaskService.updateStatus(anyLong(), any()))
            .thenThrow(new BusinessRuleException(
                    "Unsupported task status: 'NEARLY'. Allowed values are PENDING, "
                    + "IN_PROGRESS, COMPLETED, CANCELLED."));

    mockMvc.perform(put(TASK_100 + "/status")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"status\": \"NEARLY\" }"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
}

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT12 - PUT a blank status -> 400")
void ct12_updateStatusBlank_returns400() throws Exception {
    mockMvc.perform(put(TASK_100 + "/status")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"status\": \"  \" }"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
}

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT13 - PUT work notes -> 200")
void ct13_updateWorkNotes_returns200() throws Exception {
    when(jobTaskService.updateWorkNotes(anyLong(), any())).thenReturn(taskResponse("IN_PROGRESS"));

    mockMvc.perform(put(TASK_100 + "/work-notes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"workNotes\": \"Rear pads also worn\" }"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
}

@Test
@WithMockUser(roles = "SERVICE_ADVISOR")
@DisplayName("CT14 - PUT a mechanic assignment -> 200")
void ct14_assignMechanic_returns200() throws Exception {
    when(jobTaskService.assignMechanic(anyLong(), any())).thenReturn(taskResponse("PENDING"));

    mockMvc.perform(put(TASK_100 + "/mechanic")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"mechanicId\": 7 }"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mechanicId").value(7));

    verify(jobTaskService).assignMechanic(100L, 7L);
}

@Test
@WithMockUser(roles = "SERVICE_ADVISOR")
@DisplayName("CT15 - assigning an unknown mechanic -> 404")
void ct15_assignUnknownMechanic_returns404() throws Exception {
    when(jobTaskService.assignMechanic(anyLong(), any()))
            .thenThrow(new ResourceNotFoundException("Mechanic not found: 404"));

    mockMvc.perform(put(TASK_100 + "/mechanic")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"mechanicId\": 404 }"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
}

// ── CT16: labour total ──────────────────────────────────────────────

@Test
@WithMockUser(roles = "BILLING_USER")
@DisplayName("CT16 - GET a job card's labour total -> 200")
void ct16_labourTotal_returns200() throws Exception {
    when(jobTaskService.totalLabourCost(55L)).thenReturn(new BigDecimal("1750.00"));

    mockMvc.perform(get(LABOUR_TOTAL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(1750.00));
}

// ── CT17..CT19: authorisation ───────────────────────────────────────

@Test
@WithMockUser(roles = "BILLING_USER")
@DisplayName("CT17 - creating a task as BILLING_USER -> 403")
void ct17_createTaskAsBillingUser_returns403() throws Exception {
    mockMvc.perform(post(TASKS_ON_CARD)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(taskJson()))
            .andExpect(status().isForbidden());
}

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT18 - deleting a task as MECHANIC -> 403")
void ct18_deleteTaskAsMechanic_returns403() throws Exception {
    mockMvc.perform(delete(TASK_100)).andExpect(status().isForbidden());
}

@Test
@WithMockUser(roles = "MECHANIC")
@DisplayName("CT18b - allocating a mechanic as MECHANIC -> 403")
void ct18b_assignMechanicAsMechanic_returns403() throws Exception {
    mockMvc.perform(put(TASK_100 + "/mechanic")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"mechanicId\": 7 }"))
            .andExpect(status().isForbidden());
}

@Test
@WithAnonymousUser
@DisplayName("CT19 - anonymous request -> 401")
void ct19_anonymous_returns401() throws Exception {
    mockMvc.perform(get(TASK_100)).andExpect(status().isUnauthorized());
}
}