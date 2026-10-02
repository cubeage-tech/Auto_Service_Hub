package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.dto.JobCardResponseDTO;
import com.autoservicehub.dto.PartConsumptionRequestDTO;
import com.autoservicehub.dto.JobTaskRequestDTO;
import com.autoservicehub.dto.JobTaskResponseDTO;
import com.autoservicehub.dto.StockMovementResponseDTO;
import com.autoservicehub.service.JobCardService;
import com.autoservicehub.service.JobTaskService;
import com.autoservicehub.service.StockMovementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Digital Job Card (SRS 4.5)
 * Base path: /api/v1/job-cards
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 */
@RestController
@RequestMapping("/api/v1/job-cards")
@RequiredArgsConstructor
public class JobCardController {

    private final JobCardService      service;
    private final StockMovementService stockMovementService;
    private final JobTaskService       jobTaskService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JobCardResponseDTO> create(@Valid @RequestBody JobCardRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<JobCardResponseDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody JobCardRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<JobCardResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<JobCardResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    /**
     * Consume spare parts against this job card during repair.
     * POST /api/v1/job-cards/{id}/parts
     *
     * <p>Records an OUT stock movement and reduces the part's stock in the same
     * transaction. Fails with 404 if the job card or part is unknown, 400 if the
     * quantity is not positive, and 409 if there is not enough stock — in which
     * case nothing is written.
     */
    @PostMapping("/{id}/parts")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StockMovementResponseDTO> consumePart(
            @PathVariable Long id,
            @Valid @RequestBody PartConsumptionRequestDTO request) {
        return ApiResponse.ok("Consumed", stockMovementService.consumeForJobCard(
                id, request.getPartId(), request.getQuantity(), request.getReason()));
    }

    /**
     * Spare parts consumed against this job card, oldest first.
     * GET /api/v1/job-cards/{id}/parts?page=0&size=20
     */
    @GetMapping("/{id}/parts")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<StockMovementResponseDTO>> listConsumedParts(
            @PathVariable Long id,
            Pageable pageable) {
        return ApiResponse.ok(stockMovementService.listByJobCard(id, pageable));
    }

    /**
     * Add a repair task to this job card (FR-JOB-3).
     * POST /api/v1/job-cards/{id}/tasks
     *
     * <p>Nested under the job card so the parent comes from the path rather
     * than the body: a task cannot be written onto a job card the caller is not
     * working on. Returns 400 for a blank description or a negative labour
     * cost, 404 for an unknown job card or mechanic, 409 for a status outside
     * the allowed set.
     */
    @PostMapping("/{id}/tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JobTaskResponseDTO> createTask(
            @PathVariable Long id,
            @Valid @RequestBody JobTaskRequestDTO request) {
        return ApiResponse.ok("Created", jobTaskService.create(id, request));
    }

    /**
     * This job card's repair tasks, in recorded order.
     * GET /api/v1/job-cards/{id}/tasks?page=0&size=20
     */
    @GetMapping("/{id}/tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<JobTaskResponseDTO>> listTasks(@PathVariable Long id,
                                                            Pageable pageable) {
        return ApiResponse.ok(jobTaskService.listByJobCard(id, pageable));
    }

    /**
     * This job card's total labour cost, summed server-side from its tasks.
     * GET /api/v1/job-cards/{id}/tasks/labour-total
     */
    @GetMapping("/{id}/tasks/labour-total")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<java.math.BigDecimal> taskLabourTotal(@PathVariable Long id) {
        return ApiResponse.ok(jobTaskService.totalLabourCost(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
