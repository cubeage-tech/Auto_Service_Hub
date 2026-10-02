package com.autoservicehub.service;

import com.autoservicehub.dto.DiagnosisRequestDTO;
import com.autoservicehub.dto.DiagnosisResponseDTO;

/**
 * Domain service for AI-assisted vehicle diagnosis (SRS Section 5.1).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Validate that the request contains sufficient diagnostic information.</li>
 *   <li>Enrich the AI context with vehicle data from the database when a
 *       {@code vehicleId} is supplied.</li>
 *   <li>Delegate to the AI provider via {@link com.autoservicehub.ai.AiOrchestrationService}.</li>
 *   <li>Return a structured {@link DiagnosisResponseDTO} that always includes
 *       the human-review disclaimer mandated by SRS BR-09 / FR-AI-04.</li>
 * </ul>
 *
 * <p>This service MUST NOT automatically create or modify any job card,
 * inspection record, or other business entity based on AI output.
 */
public interface VehicleDiagnosisService {

    /**
     * Run an AI-assisted vehicle diagnosis.
     *
     * @param request validated diagnosis input
     * @return structured diagnosis response — always advisory, never finalised
     */
    DiagnosisResponseDTO diagnose(DiagnosisRequestDTO request);
}
