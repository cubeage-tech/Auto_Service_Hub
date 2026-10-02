package com.autoservicehub.service.impl;

import com.autoservicehub.dto.InspectionItemRequestDTO;
import com.autoservicehub.dto.InspectionItemResponseDTO;
import com.autoservicehub.dto.InspectionRequestDTO;
import com.autoservicehub.dto.InspectionResponseDTO;
import com.autoservicehub.entity.Inspection;
import com.autoservicehub.entity.InspectionItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.InspectionItemRepository;
import com.autoservicehub.repository.InspectionRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.InspectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Vehicle Inspection (SRS 4.4)
 *
 * <p>Sits between Vehicle and Job Card in the core workflow
 * (Customer Booking → Vehicle Inspection → Job Card → Repair → Spare Parts → Billing).
 *
 * <p>Rules enforced here:
 * <ul>
 *   <li>An inspection always names the vehicle it was carried out on.</li>
 *   <li>A linked job card must belong to that same vehicle, so an inspection
 *       can never contribute findings to another vehicle's job.</li>
 *   <li>A job card is raised from at most one inspection. Enforced here and in
 *       {@code JobCardServiceImpl} against the same
 *       {@code InspectionRepository.findByJobCardId} lookup, so the rule holds
 *       whichever side the link is created from. The job_card_id foreign key
 *       lives only on this entity — the link is not duplicated onto JobCard.</li>
 *   <li>Status is restricted to the workflow's own states and defaults to
 *       PENDING on create.</li>
 *   <li>Checklist findings belong to exactly one inspection.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class InspectionServiceImpl implements InspectionService {

    /** Initial state of a freshly recorded inspection. */
    public static final String STATUS_PENDING     = "PENDING";
    /** Inspection under way, findings still being recorded. */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    /** Inspection finished; the job card may be raised from it. */
    public static final String STATUS_COMPLETED   = "COMPLETED";

    private static final List<String> ALLOWED_STATUSES =
            List.of(STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_COMPLETED);

    private final InspectionRepository     repository;
    private final InspectionItemRepository itemRepository;
    private final VehicleRepository       vehicleRepository;
    private final JobCardRepository       jobCardRepository;

    @Override
    public InspectionResponseDTO create(InspectionRequestDTO request) {
        Inspection entity = new Inspection();
        mapToEntity(request, entity);
        Inspection saved = repository.save(entity);
        replaceItems(request, saved);
        return toResponse(saved);
    }

    @Override
    public InspectionResponseDTO update(Long id, InspectionRequestDTO request) {
        Inspection existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found: " + id));
        mapToEntity(request, existing);
        Inspection saved = repository.save(existing);
        replaceItems(request, saved);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public InspectionResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InspectionResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }
    @Override
    @Transactional(readOnly = true)
    public Page<InspectionResponseDTO> listByVehicle(Long vehicleId, Pageable pageable) {
        if (!vehicleRepository.existsById(vehicleId)) {
            throw new ResourceNotFoundException("Vehicle not found: " + vehicleId);
        }
        return repository.findByVehicleIdOrderByCreatedAtDesc(vehicleId, pageable)
                         .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public InspectionResponseDTO getByJobCardId(Long jobCardId) {
        InspectionResponseDTO found = findByJobCardIdOrNull(jobCardId);
        if (found == null) {
            throw new ResourceNotFoundException(
                    "No inspection is linked to JobCard: " + jobCardId);
        }
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public InspectionResponseDTO findByJobCardIdOrNull(Long jobCardId) {
        if (jobCardId == null) {
            return null;
        }
        return repository.findByJobCardId(jobCardId).map(this::toResponse).orElse(null);
    }

    @Override
    public void delete(Long id) {
        Inspection existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found: " + id));

        // Findings are owned by the inspection, so they are removed with it
        // rather than left orphaned.
        itemRepository.deleteAll(itemRepository.findByInspectionIdOrderByIdAsc(id));

        // Unlink from the job card first. The foreign key lives on this row, so
        // clearing the reference and deleting is enough to detach the pair.
        existing.setJobCard(null);
        repository.deleteById(id);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void mapToEntity(InspectionRequestDTO r, Inspection e) {
        Vehicle vehicle = vehicleRepository.findById(r.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + r.getVehicleId()));

        e.setVehicle(vehicle);
        e.setJobCard(resolveJobCard(r.getJobCardId(), vehicle, e.getId()));
        e.setComplaint(r.getComplaint());
        e.setTechnicianNotes(r.getTechnicianNotes());
        e.setEstimatedCost(r.getEstimatedCost());
        e.setStatus(resolveStatus(r.getStatus(), e.getStatus()));
    }

    /**
     * Resolves the job card this inspection feeds and checks that it belongs to
     * the inspected vehicle and is not already spoken for by another
     * inspection. Re-linking this same inspection to the same job card is a
     * no-op and therefore allowed.
     */
    private JobCard resolveJobCard(Long jobCardId, Vehicle vehicle, Long currentInspectionId) {
        if (jobCardId == null) {
            return null;
        }

        JobCard jobCard = jobCardRepository.findById(jobCardId)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + jobCardId));

        if (jobCard.getVehicle() == null
                || !jobCard.getVehicle().getId().equals(vehicle.getId())) {
            throw new BusinessRuleException(
                    "JobCard " + jobCardId + " belongs to a different vehicle and cannot be "
                    + "linked to an inspection of vehicle " + vehicle.getId() + ".");
        }

        Inspection linked = repository.findByJobCardId(jobCardId).orElse(null);
        if (linked != null && !linked.getId().equals(currentInspectionId)) {
            throw new BusinessRuleException(
                    "JobCard " + jobCardId + " is already linked to inspection "
                    + linked.getId() + ".");
        }

        return jobCard;
    }

    /**
     * Findings are replaced wholesale when the caller sends a list and left
     * untouched when the field is omitted, so a partial update cannot silently
     * discard recorded findings.
     */
    private void replaceItems(InspectionRequestDTO request, Inspection inspection) {
        List<InspectionItemRequestDTO> requested = request.getItems();
        if (requested == null) {
            return;
        }

        itemRepository.deleteAll(itemRepository.findByInspectionIdOrderByIdAsc(inspection.getId()));

        for (InspectionItemRequestDTO itemRequest : requested) {
            InspectionItem item = new InspectionItem();
            item.setInspection(inspection);
            item.setChecklistItem(itemRequest.getChecklistItem());
            item.setFinding(itemRequest.getFinding());
            item.setPhotoUrl(itemRequest.getPhotoUrl());
            itemRepository.save(item);
        }
    }

    /**
     * Falls back to the stored status (so a partial update does not reset it)
     * and finally to PENDING, rejecting any value outside the workflow.
     */
    private String resolveStatus(String requested, String current) {
        if (requested == null || requested.isBlank()) {
            return current != null ? current : STATUS_PENDING;
        }
        String normalized = requested.trim().toUpperCase();
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new BusinessRuleException(
                    "Unsupported inspection status: '" + requested + "'. Allowed values are "
                    + ALLOWED_STATUSES + ".");
        }
        return normalized;
    }

    private InspectionResponseDTO toResponse(Inspection e) {
        InspectionResponseDTO dto = new InspectionResponseDTO();
        dto.setId(e.getId());

        if (e.getVehicle() != null) {
            dto.setVehicleId(e.getVehicle().getId());
            dto.setVehicleInfo(e.getVehicle().getRegistrationNo() + " | " + e.getVehicle().getModel());
        }

        if (e.getJobCard() != null) {
            dto.setJobCardId(e.getJobCard().getId());
            dto.setJobCardNumber(e.getJobCard().getJobCardNumber());
        }

        dto.setComplaint(e.getComplaint());
        dto.setTechnicianNotes(e.getTechnicianNotes());
        dto.setEstimatedCost(e.getEstimatedCost());
        dto.setStatus(e.getStatus());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());

        if (e.getId() != null) {
            dto.setItems(itemRepository.findByInspectionIdOrderByIdAsc(e.getId())
                                       .stream()
                                       .map(this::itemToResponse)
                                       .toList());
        }
        return dto;
    }

    private InspectionItemResponseDTO itemToResponse(InspectionItem item) {
        InspectionItemResponseDTO dto = new InspectionItemResponseDTO();
        dto.setId(item.getId());
        dto.setChecklistItem(item.getChecklistItem());
        dto.setFinding(item.getFinding());
        dto.setPhotoUrl(item.getPhotoUrl());
        dto.setCreatedAt(item.getCreatedAt());
        return dto;
    }
}

