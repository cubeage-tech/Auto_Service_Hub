package com.autoservicehub.service.impl;

import com.autoservicehub.dto.MechanicSkillRequestDTO;
import com.autoservicehub.dto.MechanicSkillResponseDTO;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.MechanicSkill;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.MechanicSkillRepository;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.MechanicSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class MechanicSkillServiceImpl implements MechanicSkillService {

    private final MechanicRepository mechanicRepository;
    private final MechanicSkillRepository skillRepository;
    private final MechanicAccessService accessService;

    @Override
    @Transactional(readOnly = true)
    public List<MechanicSkillResponseDTO> list(Long mechanicId) {
        Mechanic mechanic = findMechanic(mechanicId);
        accessService.assertCanAccess(mechanic);
        return skillRepository.findByMechanicIdOrderBySkillNameAsc(mechanicId).stream().map(this::toResponse).toList();
    }

    @Override
    public MechanicSkillResponseDTO create(Long mechanicId, MechanicSkillRequestDTO request) {
        Mechanic mechanic = findMechanic(mechanicId);
        if (skillRepository.existsByMechanicIdAndSkillNameIgnoreCase(mechanicId, request.getSkillName().trim())) {
            throw new BusinessRuleException("Mechanic already has this skill.");
        }
        MechanicSkill skill = new MechanicSkill();
        skill.setMechanic(mechanic);
        map(request, skill);
        return toResponse(skillRepository.save(skill));
    }

    @Override
    public MechanicSkillResponseDTO update(Long mechanicId, Long skillId, MechanicSkillRequestDTO request) {
        MechanicSkill skill = skillRepository.findByIdAndMechanicId(skillId, mechanicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic skill not found: " + skillId));
        if (!skill.getSkillName().equalsIgnoreCase(request.getSkillName().trim())
                && skillRepository.existsByMechanicIdAndSkillNameIgnoreCase(mechanicId, request.getSkillName().trim())) {
            throw new BusinessRuleException("Mechanic already has this skill.");
        }
        map(request, skill);
        return toResponse(skillRepository.save(skill));
    }

    @Override
    public void delete(Long mechanicId, Long skillId) {
        MechanicSkill skill = skillRepository.findByIdAndMechanicId(skillId, mechanicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic skill not found: " + skillId));
        skillRepository.delete(skill);
    }

    private Mechanic findMechanic(Long mechanicId) {
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + mechanicId));
        accessService.assertCanAccess(mechanic);
        return mechanic;
    }

    private void map(MechanicSkillRequestDTO request, MechanicSkill skill) {
        skill.setSkillName(request.getSkillName().trim());
        skill.setLevel(request.getLevel() == null ? null : request.getLevel().trim());
    }

    private MechanicSkillResponseDTO toResponse(MechanicSkill skill) {
        MechanicSkillResponseDTO response = new MechanicSkillResponseDTO();
        response.setId(skill.getId());
        response.setMechanicId(skill.getMechanic().getId());
        response.setSkillName(skill.getSkillName());
        response.setLevel(skill.getLevel());
        return response;
    }
}