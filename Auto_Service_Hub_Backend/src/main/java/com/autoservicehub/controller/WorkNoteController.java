package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.WorkNoteRequestDTO;
import com.autoservicehub.dto.WorkNoteResponseDTO;
import com.autoservicehub.service.WorkNoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WorkNoteController {

    private final WorkNoteService workNoteService;

    @PostMapping("/job-cards/{jobCardId}/work-notes")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkNoteResponseDTO> addToJobCard(@PathVariable Long jobCardId,
                                                        @Valid @RequestBody WorkNoteRequestDTO request) {
        return ApiResponse.ok("Created", workNoteService.addToJobCard(jobCardId, request));
    }

    @GetMapping("/job-cards/{jobCardId}/work-notes")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<List<WorkNoteResponseDTO>> listForJobCard(@PathVariable Long jobCardId) {
        return ApiResponse.ok(workNoteService.listForJobCard(jobCardId));
    }

    @PostMapping("/job-tasks/{taskId}/work-notes")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkNoteResponseDTO> addToTask(@PathVariable Long taskId,
                                                     @Valid @RequestBody WorkNoteRequestDTO request) {
        return ApiResponse.ok("Created", workNoteService.addToTask(taskId, request));
    }
}