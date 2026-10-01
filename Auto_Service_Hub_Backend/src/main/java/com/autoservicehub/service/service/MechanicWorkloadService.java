package com.autoservicehub.service;

import com.autoservicehub.dto.MechanicWorkloadResponseDTO;

public interface MechanicWorkloadService {
    MechanicWorkloadResponseDTO getMine();
    MechanicWorkloadResponseDTO getForMechanic(Long mechanicId);
}