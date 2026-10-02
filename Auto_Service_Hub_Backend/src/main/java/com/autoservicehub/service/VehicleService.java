package com.autoservicehub.service;

import com.autoservicehub.dto.VehicleServiceHistoryDTO;
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

    /**
     * FR-VEH-6: this vehicle's service history, newest visit first.
     *
     * <p>A vehicle with no jobs returns an empty, valid history rather than a 404 —
     * the vehicle exists, it simply has not been serviced. Only an unknown vehicle
     * id is a 404.
     */
    VehicleServiceHistoryDTO getServiceHistory(Long id);

    Page<VehicleResponseDTO> list(Pageable pageable);

    void delete(Long id);
}
