package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.QualityCheckRequestDTO;
import com.autoservicehub.dto.QualityCheckResponseDTO;
import com.autoservicehub.service.QualityCheckService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Quality-check endpoints nested under the job-card resource, following the
 * existing work-note convention (SRS 4.5 FR-JOB-6, SRS 12 BR-02, SRS 9).
 *
 * <p>Roles are ADMIN, OWNER, MANAGER and SERVICE_ADVISOR — the roles that already
 * hold job-card supervisory authority in the current model. No new role is
 * introduced. The service additionally refuses a checker who is an assigned
 * mechanic on that job card.
 */
@RestController
@RequestMapping("/api/v1/job-cards/{jobCardId}/quality-checks")
@RequiredArgsConstructor
public class QualityCheckController {

    private final QualityCheckService qualityCheckService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Record a quality check for a job card",
            description = "Appends a new QC attempt. History is never overwritten: every call creates a new "
                    + "attempt, and the highest attempt number is the governing result for delivery purposes. "
                    + "The checker is always the authenticated user; any user id in the body is ignored. "
                    + "A mechanic assigned to this job card cannot record its own quality check.")
    public ApiResponse<QualityCheckResponseDTO> record(
            @PathVariable Long jobCardId,
            @Valid @RequestBody QualityCheckRequestDTO request) {
        return ApiResponse.ok("Recorded", qualityCheckService.record(jobCardId, request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    @Operation(
            summary = "Quality-check history for a job card",
            description = "Returns every recorded attempt, newest first, so the governing result is the first entry.")
    public ApiResponse<List<QualityCheckResponseDTO>> listForJobCard(@PathVariable Long jobCardId) {
        return ApiResponse.ok(qualityCheckService.listForJobCard(jobCardId));
    }
}
