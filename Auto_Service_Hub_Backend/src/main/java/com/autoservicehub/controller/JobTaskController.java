package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.JobTaskAssignMechanicRequestDTO;
import com.autoservicehub.dto.JobTaskRequestDTO;
import com.autoservicehub.dto.JobTaskResponseDTO;
import com.autoservicehub.dto.JobTaskStatusRequestDTO;
import com.autoservicehub.dto.JobTaskWorkNotesRequestDTO;
import com.autoservicehub.service.JobTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Repair tasks and labour (FR-JOB-3, FR-JOB-5)
 * Base path: /api/v1/job-tasks
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 *
 * <p>Task creation is nested under the job card
 * ({@code POST /api/v1/job-cards/{id}/tasks}) because the parent is not the
 * client's to choose — that is what keeps a task from being written onto a job
 * nobody is working on. Everything else addresses a task directly by id.
 *
 * <p>Roles follow the existing job-card conventions: service staff and
 * mechanics manage tasks; billing users can read them for costing but cannot
 * change what work was done or what it cost.
 */
@RestController
@RequestMapping("/api/v1/job-tasks")
@RequiredArgsConstructor
public class JobTaskController {

    private final JobTaskService service;

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<JobTaskResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<JobTaskResponseDTO> update(@PathVariable Long id,
                                                   @Valid @RequestBody JobTaskRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    /**
     * Move a task to a new status (FR-JOB-5).
     * PUT /api/v1/job-tasks/{id}/status
     *
     * <p>A narrow payload, so recording progress cannot restate the task's
     * labour cost. Returns 400 for a blank status, 409 for one outside
     * PENDING / IN_PROGRESS / COMPLETED / CANCELLED.
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<JobTaskResponseDTO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody JobTaskStatusRequestDTO request) {
        return ApiResponse.ok("Status updated", service.updateStatus(id, request));
    }

    /**
     * Record notes on a task (FR-JOB-5).
     * PUT /api/v1/job-tasks/{id}/work-notes
     *
     * <p>Open to the same roles that can update the task. An empty
     * {@code workNotes} clears the notes rather than storing a blank string.
     */
    @PutMapping("/{id}/work-notes")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<JobTaskResponseDTO> updateWorkNotes(
            @PathVariable Long id,
            @RequestBody JobTaskWorkNotesRequestDTO request) {
        return ApiResponse.ok("Work notes updated", service.updateWorkNotes(id, request));
    }

    /**
     * Allocate a mechanic to a task, or clear the allocation.
     * PUT /api/v1/job-tasks/{id}/mechanic
     *
     * <p>Body {@code {"mechanicId": 5}} assigns; {@code {"mechanicId": null}}
     * un-assigns. Separate from the task update so reallocation cannot restate
     * the task's cost. Returns 404 for an unknown task or mechanic.
     */
    @PutMapping("/{id}/mechanic")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<JobTaskResponseDTO> assignMechanic(
            @PathVariable Long id,
            @RequestBody JobTaskAssignMechanicRequestDTO request) {
        return ApiResponse.ok("Mechanic assigned", service.assignMechanic(id, request.getMechanicId()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}