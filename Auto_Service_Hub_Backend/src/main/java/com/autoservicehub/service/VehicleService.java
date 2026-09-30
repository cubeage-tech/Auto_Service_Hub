package com.autoservicehub.service;

import com.autoservicehub.dto.VehicleRequestDTO;
import com.autoservicehub.dto.VehicleResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Vehicle Management (SRS 4.2)
 */
public interface VehicleService {

    VehicleResponseDTO create(VehicleRequestDTO request);

    VehicleResponseDTO update(Long id, VehicleRequestDTO request);

    VehicleResponseDTO getById(Long id);

    Page<VehicleResponseDTO> list(Pageable pageable);

    void delete(Long id);
}
