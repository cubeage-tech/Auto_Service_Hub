package com.autoservicehub.service;

import com.autoservicehub.dto.CommunicationRequestDTO;
import com.autoservicehub.dto.CommunicationResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Customer communication log (SRS FR-CRM-4).
 * Channels: CALL | WHATSAPP | EMAIL | REMINDER
 */
public interface CommunicationService {

    CommunicationResponseDTO create(CommunicationRequestDTO request);

    CommunicationResponseDTO update(Long id, CommunicationRequestDTO request);

    CommunicationResponseDTO getById(Long id);

    /** Paged list of communications for a single customer, newest first. */
    Page<CommunicationResponseDTO> listByCustomer(Long customerId, Pageable pageable);

    void delete(Long id);
}
