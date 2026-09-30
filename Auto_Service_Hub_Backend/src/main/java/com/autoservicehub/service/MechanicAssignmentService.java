package com.autoservicehub.service;

import com.autoservicehub.dto.MechanicAssignmentRequestDTO;
import com.autoservicehub.dto.MechanicAssignmentResponseDTO;

/**
 * Domain service for AI-assisted mechanic assignment
 * (SRS Section 5.4, FR-AI-17..20).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Validate that a job card was supplied — a recommendation is always made
 *       for a real job.</li>
 *   <li>Build the candidate pool from real mechanic records (active status) and
 *       each candidate's real open workload, then ground the AI context in the
 *       job card's service type, complaint and current status.</li>
 *   <li>Delegate to the AI provider through
 *       {@link com.autoservicehub.ai.AiOrchestrationService}, the same provider
 *       boundary and {@code app.ai.provider} configuration used by every other
 *       AI feature.</li>
 *   <li><strong>Validate</strong> any mechanic the provider names against the
 *       real candidate pool, discarding the recommendation if it does not
 *       correspond to a real, active mechanic.</li>
 *   <li>Return a typed {@link MechanicAssignmentResponseDTO} that always
 *       carries the human-review disclaimer required by SRS BR-09 / FR-AI-20.</li>
 * </ul>
 *
 * <p><strong>This service MUST NOT</strong> assign the mechanic. It never
 * writes {@code JobCard.mechanic}, never creates a job task, and never sends a
 * notification. Assignment remains a human decision taken through the normal
 * job card workflow.
 */
public interface MechanicAssignmentService {

    /**
     * Recommend a mechanic for a job card.
     *
     * @param request validated assignment input (job card is mandatory)
     * @return structured recommendation — advisory, never an assignment
     */
    MechanicAssignmentResponseDTO recommend(MechanicAssignmentRequestDTO request);
}
