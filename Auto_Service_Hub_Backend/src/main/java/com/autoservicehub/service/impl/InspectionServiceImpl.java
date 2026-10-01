package com.autoservicehub.service.impl;

import com.autoservicehub.dto.InspectionRequestDTO;
import com.autoservicehub.dto.InspectionResponseDTO;
import com.autoservicehub.dto.InspectionItemResponseDTO;
import com.autoservicehub.entity.Inspection;
import com.autoservicehub.entity.InspectionItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.InspectionRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import com.autoservicehub.service.InspectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vehicle Inspection (SRS 4.4)
 * Business rules to enforce here per SRS section 12 (e.g. server-side total
 * recalculation, stock limits, audit trail) before persisting.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class InspectionServiceImpl implements InspectionService {

    private final InspectionRepository repository;
    private final VehicleRepository vehicleRepository;
    private final JobCardRepository jobCardRepository;
    private final ServiceAdvisorAccessService advisorAccessService;

    @Override
    public InspectionResponseDTO create(InspectionRequestDTO request) {
        Inspection entity = new Inspection();
        mapToEntity(request, entity);
        advisorAccessService.assertCanAccess(entity);
        Inspection saved = repository.save(entity);
        return toResponse(saved);
    }

    @Override
    public InspectionResponseDTO update(Long id, InspectionRequestDTO request) {
        Inspection existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found: " + id));
        advisorAccessService.assertCanAccess(existing);
        if (advisorAccessService.isAdvisorUser() && request.getJobCardId() != null
                && (existing.getJobCard() == null || !request.getJobCardId().equals(existing.getJobCard().getId()))) {
            throw new org.springframework.security.access.AccessDeniedException("Service Advisors cannot move an inspection to another job card.");
        }
        mapToEntity(request, existing);
        advisorAccessService.assertCanAccess(existing);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public InspectionResponseDTO getById(Long id) {
        Inspection found = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found: " + id));
        advisorAccessService.assertCanAccess(found);
        return toResponse(found);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InspectionResponseDTO> list(Pageable pageable) {
        if (advisorAccessService.isAdvisorUser()) {
            return repository.findVisibleToAdvisor(advisorAccessService.currentAdvisor().getId(), pageable).map(this::toResponse);
        }
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        Inspection inspection = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Inspection not found: " + id));
        advisorAccessService.assertCanAccess(inspection);
        repository.delete(inspection);
    }

    private InspectionResponseDTO toResponse(Inspection entity) {
        InspectionResponseDTO dto = new InspectionResponseDTO();
        dto.setId(entity.getId());
        dto.setVehicleId(entity.getVehicle() == null ? null : entity.getVehicle().getId());
        dto.setJobCardId(entity.getJobCard() == null ? null : entity.getJobCard().getId());
        dto.setComplaint(entity.getComplaint());
        dto.setTechnicianNotes(entity.getTechnicianNotes());
        dto.setEstimatedCost(entity.getEstimatedCost());
        dto.setStatus(entity.getStatus());
        dto.setItems(entity.getItems().stream().map(item -> {
            InspectionItemResponseDTO itemDTO = new InspectionItemResponseDTO();
            itemDTO.setId(item.getId());
            itemDTO.setChecklistItem(item.getChecklistItem());
            itemDTO.setFinding(item.getFinding());
            itemDTO.setPhotoUrl(item.getPhotoUrl());
            return itemDTO;
        }).toList());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private void mapToEntity(InspectionRequestDTO request, Inspection inspection) {
        Vehicle vehicle = inspection.getVehicle();
        JobCard jobCard = inspection.getJobCard();
        if (request.getVehicleId() != null) {
            vehicle = vehicleRepository.findById(request.getVehicleId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + request.getVehicleId()));
        }
        if (request.getJobCardId() != null) {
            jobCard = jobCardRepository.findById(request.getJobCardId())
                    .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + request.getJobCardId()));
        }
        if (vehicle == null && jobCard != null) vehicle = jobCard.getVehicle();
        if (jobCard != null && vehicle != null && jobCard.getVehicle() != null
                && !vehicle.getId().equals(jobCard.getVehicle().getId())) {
            throw new BusinessRuleException("Inspection vehicle must match the job card vehicle.");
        }
        inspection.setVehicle(vehicle);
        inspection.setJobCard(jobCard);
        inspection.setComplaint(request.getComplaint());
        inspection.setTechnicianNotes(request.getTechnicianNotes());
        inspection.setEstimatedCost(request.getEstimatedCost());
        inspection.setStatus(request.getStatus());
        if (request.getItems() != null) {
            inspection.getItems().clear();
            for (var itemRequest : request.getItems()) {
                InspectionItem item = new InspectionItem();
                item.setInspection(inspection);
                item.setChecklistItem(itemRequest.getChecklistItem().trim());
                item.setFinding(itemRequest.getFinding());
                item.setPhotoUrl(itemRequest.getPhotoUrl());
                inspection.getItems().add(item);
            }
        }
    }
}
