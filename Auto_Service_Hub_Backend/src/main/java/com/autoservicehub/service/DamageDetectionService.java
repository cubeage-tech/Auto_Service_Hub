package com.autoservicehub.service;

import com.autoservicehub.dto.DamageDetectionRequestDTO;
import com.autoservicehub.dto.DamageDetectionResponseDTO;

/**
 * Domain service for AI-assisted damage assessment
 * (SRS Section 5.6, FR-AI-13..16).
 *
 * <p><strong>Text-based assessment, not image or vision analysis.</strong>
 * The current backend has no image upload, storage or vision capability, and the
 * only configured provider is the no-op client. This service therefore assesses
 * damage from a WRITTEN description together with real vehicle and job card
 * context, and must never imply that a photograph was examined.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Validate that a vehicle and a written damage description were supplied.</li>
 *   <li>Resolve the vehicle, and — when supplied — the job card, verifying that
 *       the job card belongs to the same vehicle where the existing model allows
 *       that check.</li>
 *   <li>Build the AI context from real database fields only. Customer PII (name,
 *       phone, email, address) is never included.</li>
 *   <li>Delegate to the AI provider through
 *       {@link com.autoservicehub.ai.AiOrchestrationService}, the same provider
 *       boundary and {@code app.ai.provider} configuration used by every other
 *       AI feature.</li>
 *   <li>Map provider output into a typed
 *       {@link DamageDetectionResponseDTO}, fabricating nothing: a severity or
 *       area the provider did not supply stays {@code null}.</li>
 *   <li>Return a safe degraded response when the provider is unavailable, with
 *       no invented damage findings.</li>
 * </ul>
 *
 * <p><strong>This service MUST NOT</strong> create or modify an inspection,
 * job card, estimate or invoice. Recording confirmed damage is a human action
 * taken through the normal workshop workflow.
 */
public interface DamageDetectionService {

    /**
     * Assess reported damage from its written description.
     *
     * @param request validated input (vehicle and description are mandatory)
     * @return structured assessment — advisory, never a substitute for a physical inspection
     */
    DamageDetectionResponseDTO assess(DamageDetectionRequestDTO request);
}
