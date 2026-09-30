package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.FeedbackRequestDTO;
import com.autoservicehub.dto.FeedbackResponseDTO;
import com.autoservicehub.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Post-service customer feedback and ratings (SRS FR-CRM-5).
 * Base path: /api/v1/feedback
 *
 * Feedback can only be submitted for DELIVERED job cards.
 * Rating: 1 (Poor) → 5 (Excellent).
 */
@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService service;

    /**
     * Submit post-service feedback.
     * POST /api/v1/feedback
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FeedbackResponseDTO> create(
            @Valid @RequestBody FeedbackRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    /**
     * Update a feedback record.
     * PUT /api/v1/feedback/{id}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<FeedbackResponseDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody FeedbackRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    /**
     * Get a single feedback record.
     * GET /api/v1/feedback/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'BILLING_USER')")
    public ApiResponse<FeedbackResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    /**
     * List feedback. Requires either customerId or jobCardId.
     *
     * GET /api/v1/feedback?customerId={id}   — all feedback for a customer
     * GET /api/v1/feedback?jobCardId={id}    — feedback for a specific job card
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'BILLING_USER')")
    public ApiResponse<Page<FeedbackResponseDTO>> list(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long jobCardId,
            Pageable pageable) {
        if (customerId != null) {
            return ApiResponse.ok(service.listByCustomer(customerId, pageable));
        }
        if (jobCardId != null) {
            return ApiResponse.ok(service.listByJobCard(jobCardId, pageable));
        }
        // At least one filter is required — prevents full-table scans
        throw new com.autoservicehub.exception.BusinessRuleException(
                "Either customerId or jobCardId query parameter is required.");
    }

    /**
     * Delete a feedback record.
     * DELETE /api/v1/feedback/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
