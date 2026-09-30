package com.autoservicehub.service;

import com.autoservicehub.dto.FeedbackRequestDTO;
import com.autoservicehub.dto.FeedbackResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Post-service customer feedback and ratings (SRS FR-CRM-5).
 * Rating range: 1–5.
 */
public interface FeedbackService {

    FeedbackResponseDTO create(FeedbackRequestDTO request);

    FeedbackResponseDTO update(Long id, FeedbackRequestDTO request);

    FeedbackResponseDTO getById(Long id);

    /** Paged list of feedback for a single customer, newest first. */
    Page<FeedbackResponseDTO> listByCustomer(Long customerId, Pageable pageable);

    /** All feedback for a specific job card (typically 0 or 1 record). */
    Page<FeedbackResponseDTO> listByJobCard(Long jobCardId, Pageable pageable);

    void delete(Long id);
}
