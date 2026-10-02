package com.autoservicehub.service;

import com.autoservicehub.dto.SparePartsPredictionRequestDTO;
import com.autoservicehub.dto.SparePartsPredictionResponseDTO;

/**
 * Domain service for AI-assisted spare parts prediction
 * (SRS Section 5.5, FR-AI-21..24).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Validate that a service description was supplied — a parts prediction must
 *       be tied to a described repair.</li>
 *   <li>Build the candidate catalogue from real {@code Part} records, with real
 *       stock and price data, and ground the AI context in the job card and
 *       vehicle where supplied.</li>
 *   <li><strong>Validate</strong> every part the provider names against the real
 *       catalogue, discarding any part that does not correspond to a genuine
 *       record rather than passing it through or substituting another.</li>
 *   <li>Delegate to the AI provider through
 *       {@link com.autoservicehub.ai.AiOrchestrationService}, the same provider
 *       boundary and {@code app.ai.provider} configuration used by every other
 *       AI feature.</li>
 *   <li>Return a typed {@link SparePartsPredictionResponseDTO} that always
 *       carries the human-review disclaimer required by SRS BR-09 / FR-AI-24.</li>
 * </ul>
 *
 * <p><strong>This service MUST NOT touch inventory.</strong> It never deducts or
 * reserves stock, never modifies {@code Part.stockQty}, never creates a stock
 * movement, purchase or supplier record, and never modifies a job card.
 * Recording a requirement is a human action taken through the inventory and job
 * card workflows.
 */
public interface SparePartsPredictionService {

    /**
     * Predict the spare parts a repair may require.
     *
     * @param request validated prediction input (service description is mandatory)
     * @return structured prediction — advisory, never an inventory action
     */
    SparePartsPredictionResponseDTO predict(SparePartsPredictionRequestDTO request);
}
