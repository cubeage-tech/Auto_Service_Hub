package com.autoservicehub.service;

import com.autoservicehub.dto.RepairCostEstimationRequestDTO;
import com.autoservicehub.dto.RepairCostEstimationResponseDTO;

/**
 * Domain service for AI-assisted repair cost estimation (SRS Section 5.2, FR-AI-05..08).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Validate that the request carries enough information to cost a repair.</li>
 *   <li>Ground the AI context with real database data — vehicle, job card and
 *       parts — resolving only relationships that actually exist. Where a
 *       relationship or reference data is missing from the domain model, the gap
 *       is reported in {@code dataLimitations} rather than filled in.</li>
 *   <li>Delegate to the AI provider through
 *       {@link com.autoservicehub.ai.AiOrchestrationService}, the same provider
 *       boundary and {@code app.ai.provider} configuration used by every other
 *       AI feature.</li>
 *   <li>Return a typed {@link RepairCostEstimationResponseDTO} that always
 *       carries the human-review disclaimer required by SRS BR-09 / FR-AI-08.</li>
 * </ul>
 *
 * <p><strong>This service MUST NOT</strong> create or modify an estimate,
 * invoice, job card, task or stock record. An AI estimate is advisory output
 * only; persisting a price is the responsibility of the billing module, after a
 * human has reviewed and confirmed the figure.
 */
public interface RepairCostEstimationService {

    /**
     * Produce an AI-assisted repair cost estimate.
     *
     * @param request validated estimation input (never carries a client total)
     * @return structured estimate response — advisory, never authoritative
     */
    RepairCostEstimationResponseDTO estimate(RepairCostEstimationRequestDTO request);
}
