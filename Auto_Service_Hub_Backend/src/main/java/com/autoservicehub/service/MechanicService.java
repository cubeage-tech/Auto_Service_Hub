package com.autoservicehub.service;

import com.autoservicehub.dto.MechanicRequestDTO;
import com.autoservicehub.dto.MechanicResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Mechanic Management (SRS 4.6)
 */
public interface MechanicService {

    MechanicResponseDTO create(MechanicRequestDTO request);

    MechanicResponseDTO update(Long id, MechanicRequestDTO request);

    MechanicResponseDTO getById(Long id);

    Page<MechanicResponseDTO> list(Pageable pageable);

    void delete(Long id);
}
