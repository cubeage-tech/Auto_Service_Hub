package com.autoservicehub.service;

import com.autoservicehub.dto.QualityCheckRequestDTO;
import com.autoservicehub.dto.QualityCheckResponseDTO;

import java.util.List;

/**
 * Quality-check recording and history (SRS 4.5 FR-JOB-6, SRS 12 BR-02).
 *
 * <p>Every method returns DTOs rather than JPA entities, per SRS 9.1 ("Use DTOs
 * instead of exposing JPA entities directly"), and every method enforces the same
 * job-card visibility rules as the rest of the job-card sub-resources.
 */
public interface QualityCheckService {

    /**
     * Records a new QC attempt for a job card. The checker is always the
     * authenticated user. History is appended, never overwritten.
     */
    QualityCheckResponseDTO record(Long jobCardId, QualityCheckRequestDTO request);

    /** Full QC history for a job card, newest attempt first. */
    List<QualityCheckResponseDTO> listForJobCard(Long jobCardId);

    /**
     * The governing (highest attempt number) QC result, or {@code null} when none has
     * been recorded. Returns a DTO, never the entity, so callers cannot touch lazy
     * associations after the transaction has closed.
     */
    QualityCheckResponseDTO latest(Long jobCardId);
}
