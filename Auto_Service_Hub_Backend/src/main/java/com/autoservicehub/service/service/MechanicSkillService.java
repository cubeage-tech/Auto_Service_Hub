package com.autoservicehub.service;

import com.autoservicehub.dto.MechanicSkillRequestDTO;
import com.autoservicehub.dto.MechanicSkillResponseDTO;
import java.util.List;

public interface MechanicSkillService {
    List<MechanicSkillResponseDTO> list(Long mechanicId);
    MechanicSkillResponseDTO create(Long mechanicId, MechanicSkillRequestDTO request);
    MechanicSkillResponseDTO update(Long mechanicId, Long skillId, MechanicSkillRequestDTO request);
    void delete(Long mechanicId, Long skillId);
}