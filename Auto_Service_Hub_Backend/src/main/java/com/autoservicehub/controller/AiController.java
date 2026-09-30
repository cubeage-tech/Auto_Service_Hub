package com.autoservicehub.controller;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.DamageDetectionRequestDTO;
import com.autoservicehub.dto.DamageDetectionResponseDTO;
import com.autoservicehub.dto.DiagnosisRequestDTO;
import com.autoservicehub.dto.DiagnosisResponseDTO;
import com.autoservicehub.dto.MaintenancePredictionRequestDTO;
import com.autoservicehub.dto.MaintenancePredictionResponseDTO;
import com.autoservicehub.dto.MechanicAssignmentRequestDTO;
import com.autoservicehub.dto.MechanicAssignmentResponseDTO;
import com.autoservicehub.dto.RepairCostEstimationRequestDTO;
import com.autoservicehub.dto.RepairCostEstimationResponseDTO;
import com.autoservicehub.dto.SparePartsPredictionRequestDTO;
import com.autoservicehub.dto.SparePartsPredictionResponseDTO;
import com.autoservicehub.service.DamageDetectionService;
import com.autoservicehub.service.MaintenancePredictionService;
import com.autoservicehub.service.MechanicAssignmentService;
import com.autoservicehub.service.RepairCostEstimationService;
import com.autoservicehub.service.SparePartsPredictionService;
import com.autoservicehub.service.VehicleDiagnosisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * AI Center endpoints (SRS 5, 9, 10 - AI Center screen, 11.3 - AI Flow).
 *
 * <p>Every response must be reviewed/confirmed by a user before it changes a
 * job card, invoice, or stock record (BR-09).
 *
 * <p>All six SRS AI capabilities are fully implemented, each with domain
 * validation, database-grounded context enrichment, a typed response and an
 * enforced human-review disclaimer:
 * <ul>
 *   <li>Vehicle Diagnosis (FR-AI-01..04)</li>
 *   <li>Repair Cost Estimation (FR-AI-05..08)</li>
 *   <li>Maintenance Prediction (FR-AI-09..12)</li>
 *   <li>Damage Detection (FR-AI-13..16) — text-based assessment; the system has
 *       no image upload or vision capability, so no image is analysed</li>
 *   <li>Mechanic Assignment (FR-AI-17..20)</li>
 *   <li>Spare Parts Prediction (FR-AI-21..24)</li>
 * </ul>
 * The remaining AI feature endpoints retain their original stub behaviour until
 * they are individually implemented.
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Tag(name = "AI Center", description = "AI-assisted vehicle operations — outputs are advisory and require human confirmation (SRS BR-09)")
public class AiController {

    private final AiOrchestrationService        aiOrchestrationService;
    private final VehicleDiagnosisService       vehicleDiagnosisService;
    private final RepairCostEstimationService   repairCostEstimationService;
    private final MaintenancePredictionService  maintenancePredictionService;
    private final MechanicAssignmentService     mechanicAssignmentService;
    private final SparePartsPredictionService   sparePartsPredictionService;
    private final DamageDetectionService        damageDetectionService;

    // ── FR-AI-01..04: Vehicle Diagnosis ───────────────────────────────────

    /**
     * POST /api/v1/ai/diagnosis
     *
     * <p>Performs AI-assisted vehicle diagnosis based on reported symptoms,
     * optional inspection findings, and optional vehicle database context.
     * The result is always advisory — a qualified technician must review and
     * confirm before any repair action is taken (SRS BR-09, FR-AI-04).
     */
    @PostMapping("/diagnosis")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER')")
    @Operation(
        summary = "AI Vehicle Diagnosis",
        description = "Provides an AI-assisted diagnosis recommendation based on symptoms, " +
                      "inspection findings and (optionally) vehicle data from the database. " +
                      "Output is ADVISORY ONLY — human confirmation required before any repair action."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Diagnosis completed (or provider unavailable — see providerUnavailable flag)",
            content = @Content(schema = @Schema(implementation = DiagnosisResponseDTO.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — symptoms missing or too short"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vehicle not found (when vehicleId supplied)")
    })
    public ApiResponse<DiagnosisResponseDTO> diagnosis(
            @Valid @RequestBody DiagnosisRequestDTO request) {
        return ApiResponse.ok(vehicleDiagnosisService.diagnose(request));
    }

    // ── FR-AI-05..08: Repair Cost Estimation ─────────────────────────────

    /**
     * POST /api/v1/ai/repair-cost-estimation
     *
     * <p>Produces an AI-assisted estimate of the cost of a repair. The request
     * deliberately accepts no client-supplied price or total — the figure is
     * produced by the AI provider and is advisory only.
     *
     * <p>Vehicle, job card and parts context is read from the database when
     * supplied. The response reports which inputs were missing rather than
     * filling the gaps in.
     *
     * <p>The result is NOT a quotation and NOT a guaranteed price. It must be
     * reviewed by a service advisor or manager and agreed with the customer
     * before any work is authorised (SRS BR-09, FR-AI-08).
     */
    @PostMapping("/repair-cost-estimation")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    @Operation(
        summary = "AI Repair Cost Estimation",
        description = "Estimates the cost of a repair using AI, grounded in vehicle, job card and parts " +
                      "data from the database. Accepts no client-supplied totals. The output is an " +
                      "APPROXIMATION, not a quotation or guaranteed price, and MUST be reviewed by a " +
                      "service advisor or manager and agreed with the customer before work is authorised. " +
                      "When the AI provider is unavailable no figure is returned and a manual estimate " +
                      "must be prepared."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Estimate produced (or provider unavailable — see providerUnavailable flag)",
            content = @Content(schema = @Schema(implementation = RepairCostEstimationResponseDTO.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — serviceDescription missing or too short"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vehicle, job card or part not found (when the corresponding id was supplied)")
    })
    public ApiResponse<RepairCostEstimationResponseDTO> repairCostEstimation(
            @Valid @RequestBody RepairCostEstimationRequestDTO request) {
        return ApiResponse.ok(repairCostEstimationService.estimate(request));
    }

    // ── FR-AI-09..12: Maintenance Prediction ──────────────────────────────

    /**
     * POST /api/v1/ai/maintenance-prediction
     *
     * <p>Predicts upcoming maintenance for a specific vehicle, grounded in the
     * vehicle's recorded service history and appointment history. Data gaps are
     * reported in {@code dataLimitations} rather than being filled in.
     *
     * <p>The output is ADVISORY. It is not a service schedule and not a safety
     * instruction, and must be reviewed by a qualified technician before
     * anything is recommended to a customer (SRS BR-09, FR-AI-12).
     */
    @PostMapping("/maintenance-prediction")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    @Operation(
        summary = "AI Maintenance Prediction",
        description = "Predicts upcoming maintenance items for a vehicle using its recorded service " +
                      "history, odometer reading and booking history. The output is ADVISORY only — " +
                      "it is not a service schedule or a safety instruction, and MUST be reviewed by a " +
                      "qualified technician before being discussed with a customer. When the AI provider " +
                      "is unavailable no prediction is returned and a manual inspection is required."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Prediction produced (or provider unavailable — see providerUnavailable flag)",
            content = @Content(schema = @Schema(implementation = MaintenancePredictionResponseDTO.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — vehicleId missing or not positive"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vehicle not found")
    })
    public ApiResponse<MaintenancePredictionResponseDTO> maintenancePrediction(
            @Valid @RequestBody MaintenancePredictionRequestDTO request) {
        return ApiResponse.ok(maintenancePredictionService.predict(request));
    }

    // ── FR-AI-17..20: Mechanic Assignment ─────────────────────────────────

    /**
     * POST /api/v1/ai/mechanic-assignment
     *
     * <p>Recommends a mechanic for a job card, grounded in real active-mechanic
     * records and each candidate's real open workload. Data the system does not
     * hold — skills, certifications, ratings, availability, attendance — is
     * reported in {@code dataLimitations} rather than approximated.
     *
     * <p><strong>No assignment is performed.</strong> The job card is not
     * modified and no schedule entry is created. A service advisor or manager
     * must review the recommendation and assign the work themselves
     * (SRS BR-09, FR-AI-20).
     */
    @PostMapping("/mechanic-assignment")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @Operation(
        summary = "AI Mechanic Assignment (recommendation only)",
        description = "Recommends a mechanic for a job card using employment status, experience and real " +
                      "current workload. The system holds no skill, certification, rating, availability or " +
                      "attendance data, so those gaps are reported in dataLimitations. This is a " +
                      "RECOMMENDATION ONLY — no assignment is made, the job card is not modified, and a " +
                      "service advisor or manager must assign the work manually."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Recommendation produced, or no recommendation when the provider is unavailable " +
                          "or returned an unvalidatable candidate",
            content = @Content(schema = @Schema(implementation = MechanicAssignmentResponseDTO.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — jobCardId missing or not positive, or a requested candidate is not active"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Job card or candidate mechanic not found")
    })
    public ApiResponse<MechanicAssignmentResponseDTO> mechanicAssignment(
            @Valid @RequestBody MechanicAssignmentRequestDTO request) {
        return ApiResponse.ok(mechanicAssignmentService.recommend(request));
    }

    // ── FR-AI-21..24: Spare Parts Prediction ─────────────────────────────

    /**
     * POST /api/v1/ai/spare-parts-prediction
     *
     * <p>Predicts the spare parts a described repair may require, grounded in
     * the real parts catalogue with its current stock and pricing. Predicted
     * parts are validated against the catalogue; any part the provider names
     * that does not exist is discarded rather than substituted.
     *
     * <p><strong>No inventory action is taken.</strong> Stock is not deducted or
     * reserved, no stock movement, purchase or supplier record is created, and no
     * job card is modified. A parts professional must review the prediction and
     * confirm the requirement before ordering (SRS BR-09, FR-AI-24).
     */
    @PostMapping("/spare-parts-prediction")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'INVENTORY_MANAGER', 'SERVICE_ADVISOR')")
    @Operation(
        summary = "AI Spare Parts Prediction (prediction only)",
        description = "Predicts the spare parts a repair may require, using the real parts catalogue and " +
                      "current stock. Parts are not linked to job cards and stock movements are not linked " +
                      "to parts, so no historical parts-usage signal is available; these gaps are reported in " +
                      "dataLimitations. This is a PREDICTION ONLY — no stock is reserved or deducted, no " +
                      "stock movement, purchase or job card change is created, and a parts professional must " +
                      "review the prediction and confirm the requirement before ordering."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Prediction produced, or no prediction when the provider is unavailable, the " +
                          "catalogue is empty, or returned unmatchable parts",
            content = @Content(schema = @Schema(implementation = SparePartsPredictionResponseDTO.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — serviceDescription missing or too short, or a requested part id is not positive"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vehicle, job card or requested part not found")
    })
    public ApiResponse<SparePartsPredictionResponseDTO> sparePartsPrediction(
            @Valid @RequestBody SparePartsPredictionRequestDTO request) {
        return ApiResponse.ok(sparePartsPredictionService.predict(request));
    }

    // ── FR-AI-13..16: Damage Detection (text-based assessment) ────────────

    /**
     * POST /api/v1/ai/damage-detection
     *
     * <p>Assesses reported vehicle damage from a WRITTEN description, grounded
     * in the vehicle's real record and, when supplied, a job card belonging to
     * that vehicle.
     *
     * <p><strong>This is a text-based advisory assessment. No photographs,
     * images or video are accepted or analysed</strong> — the system has no image
     * upload, storage or vision capability, and no AI provider capable of vision
     * is configured. A qualified technician must physically inspect the vehicle.
     * The output must not be used to settle an insurance claim or to declare a
     * vehicle safe or unsafe (SRS BR-09, FR-AI-16).
     */
    @PostMapping("/damage-detection")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    @Operation(
        summary = "AI Damage Assessment (text-based, NOT image analysis)",
        description = "Assesses reported damage from a WRITTEN description together with the vehicle record " +
                      "and job context. IMPORTANT: this endpoint does NOT analyse photographs, images or " +
                      "video — the system has no image upload or vision capability, so no image can be " +
                      "submitted or interpreted. Output is advisory and must be confirmed by a qualified " +
                      "technician who physically inspects the vehicle. It must NOT be used to settle or " +
                      "value an insurance claim, nor to declare a vehicle safe or unsafe. When the AI " +
                      "provider is unavailable no damage areas are returned and a manual inspection is " +
                      "required."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Assessment produced, or a safe degraded response when the provider is unavailable",
            content = @Content(schema = @Schema(implementation = DamageDetectionResponseDTO.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — vehicleId or damageDescription missing/invalid, or the job card belongs to another vehicle"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vehicle or job card not found")
    })
    public ApiResponse<DamageDetectionResponseDTO> damageDetection(
            @Valid @RequestBody DamageDetectionRequestDTO request) {
        return ApiResponse.ok(damageDetectionService.assess(request));
    }
}
