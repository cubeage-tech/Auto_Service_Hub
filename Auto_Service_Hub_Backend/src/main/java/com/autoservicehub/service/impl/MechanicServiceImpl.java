package com.autoservicehub.service.impl;

import com.autoservicehub.dto.MechanicRequestDTO;
import com.autoservicehub.dto.MechanicResponseDTO;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.MechanicService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class MechanicServiceImpl implements MechanicService {

    private final MechanicRepository repository;
    private final UserRepository userRepository;
    private final MechanicAccessService accessService;

    @Override
    public MechanicResponseDTO create(MechanicRequestDTO request) {
        Mechanic entity = new Mechanic();
        mapToEntity(request, entity);
        return toResponse(repository.save(entity));
    }

    @Override
    public MechanicResponseDTO update(Long id, MechanicRequestDTO request) {
        Mechanic existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + id));
        mapToEntity(request, existing);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public MechanicResponseDTO getById(Long id) {
        Mechanic mechanic = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + id));
        accessService.assertCanAccess(mechanic);
        return toResponse(mechanic);
    }

    @Override
    @Transactional(readOnly = true)
    public MechanicResponseDTO getMine() {
        return toResponse(accessService.currentMechanic());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MechanicResponseDTO> list(Pageable pageable) {
        if (accessService.isMechanicUser()) {
            Mechanic mechanic = accessService.currentMechanic();
            List<MechanicResponseDTO> content = pageable.getOffset() > 0 ? List.of() : List.of(toResponse(mechanic));
            return new PageImpl<>(content, pageable, 1);
        }
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) throw new ResourceNotFoundException("Mechanic not found: " + id);
        repository.deleteById(id);
    }

    private void mapToEntity(MechanicRequestDTO r, Mechanic e) {
        e.setName(r.getName());
        e.setEmployeeCode(r.getEmployeeCode());
        e.setPhone(r.getPhone());
        if (r.getUserId() != null) {
            User user = userRepository.findById(r.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + r.getUserId()));
            String roleName = user.getRole() == null ? null : user.getRole().getName();
            if (roleName == null || !roleName.replaceFirst("(?i)^ROLE_", "").trim().equalsIgnoreCase("MECHANIC")) {
                throw new BusinessRuleException("A mechanic profile can only be linked to a user with the MECHANIC role.");
            }
            repository.findByUserId(user.getId()).filter(mechanic -> !mechanic.getId().equals(e.getId()))
                    .ifPresent(mechanic -> { throw new BusinessRuleException("User is already linked to another mechanic."); });
            e.setUser(user);
        }
        e.setExperienceYears(r.getExperienceYears());
        if (r.getStatus() != null && !r.getStatus().equalsIgnoreCase("ACTIVE")
            && !r.getStatus().equalsIgnoreCase("INACTIVE")) {
            throw new BusinessRuleException("Mechanic status must be ACTIVE or INACTIVE.");
        }
        e.setStatus(r.getStatus() != null ? r.getStatus().toUpperCase() : (e.getStatus() != null ? e.getStatus() : "ACTIVE"));
    }

    private MechanicResponseDTO toResponse(Mechanic e) {
        MechanicResponseDTO dto = new MechanicResponseDTO();
        dto.setId(e.getId());
        dto.setEmployeeCode(e.getEmployeeCode());
        dto.setName(e.getName());
        dto.setPhone(e.getPhone());
        dto.setUserId(e.getUser() == null ? null : e.getUser().getId());
        dto.setExperienceYears(e.getExperienceYears());
        dto.setStatus(e.getStatus());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
