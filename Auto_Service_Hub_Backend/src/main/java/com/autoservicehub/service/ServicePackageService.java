package com.autoservicehub.service;

import com.autoservicehub.dto.ServicePackageRequestDTO;
import com.autoservicehub.dto.ServicePackageResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service Package Management (SRS 4.8)
 */
public interface ServicePackageService {

    ServicePackageResponseDTO create(ServicePackageRequestDTO request);

    ServicePackageResponseDTO update(Long id, ServicePackageRequestDTO request);

    ServicePackageResponseDTO getById(Long id);

    Page<ServicePackageResponseDTO> list(Pageable pageable);

    void delete(Long id);
}
