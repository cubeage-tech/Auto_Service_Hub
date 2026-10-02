package com.autoservicehub.service;

import com.autoservicehub.dto.MaintenancePredictionRequestDTO;
import com.autoservicehub.dto.MaintenancePredictionResponseDTO;

/**
 * Domain service for AI-assisted maintenance prediction
 * (SRS Section 5.3, FR-AI-09..12).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Validate that a vehicle was supplied — a prediction is always made for a
 *       specific vehicle.</li>
 *   <li>Ground the AI context in real database data: vehicle details, its
 *       recorded service history (job cards), and its appointment history. Only
 *       relationships that exist in the JPA model are traversed; where history
 *       is missing or thin, the gap is reported in {@code dataLimitations}
 *       rather than being simulated.</li>
 *   <li>Delegate to the AI provider through
 *       {@link com.autoservicehub.ai.AiOrchestrationService}, the same provider
 *       boundary and {@code app.ai.provider} configuration used by every other
 *       AI feature.</li>
 *   <li>Return a typed {@link MaintenancePredictionResponseDTO} that always
 *       carries the human-review disclaimer required by SRS BR-09 / FR-AI-12.</li>
 * </ul>
 *
 * <p><strong>This service MUST NOT</strong> create or modify a job card, task,
 * estimate, invoice or notification. Predictions are advisory display output;
 * scheduling the work is a human decision made outside this service.
 */
public interface MaintenancePredictionService {

    /**
     * Predict upcoming maintenance for a vehicle.
     *
     * @param request validated prediction input (vehicle is mandatory)
     * @return structured prediction response — advisory, never prescriptive
     */
    MaintenancePredictionResponseDTO predict(MaintenancePredictionRequestDTO request);
}
