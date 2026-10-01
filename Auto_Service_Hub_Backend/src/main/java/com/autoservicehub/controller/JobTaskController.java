package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.JobTaskCreateRequestDTO;
import com.autoservicehub.dto.JobTaskRequestDTO;
import com.autoservicehub.dto.JobTaskResponseDTO;
import com.autoservicehub.service.JobTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class JobTaskController {

    private final JobTaskService taskService;

    @PostMapping("/api/v1/job-cards/{jobCardId}/tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JobTaskResponseDTO> create(@PathVariable Long jobCardId,
                                                  @Valid @RequestBody JobTaskCreateRequestDTO request) {
        return ApiResponse.ok("Created", taskService.create(jobCardId, request));
    }

    @GetMapping("/api/v1/job-cards/{jobCardId}/tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<List<JobTaskResponseDTO>> listForJobCard(@PathVariable Long jobCardId) {
        return ApiResponse.ok(taskService.listForJobCard(jobCardId));
    }

    @GetMapping("/api/v1/mechanics/me/tasks")
    @PreAuthorize("hasRole('MECHANIC')")
    public ApiResponse<List<JobTaskResponseDTO>> listMine() {
        return ApiResponse.ok(taskService.listMine());
    }

    @PutMapping("/api/v1/job-tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<JobTaskResponseDTO> update(@PathVariable Long taskId,
                                                  @Valid @RequestBody JobTaskRequestDTO request) {
        return ApiResponse.ok("Updated", taskService.update(taskId, request));
    }

    @PostMapping("/api/v1/job-tasks/{taskId}/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<JobTaskResponseDTO> complete(@PathVariable Long taskId) {
        return ApiResponse.ok("Completed", taskService.complete(taskId));
    }
}