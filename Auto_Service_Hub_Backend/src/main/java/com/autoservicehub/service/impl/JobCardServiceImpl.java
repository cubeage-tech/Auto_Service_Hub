package com.autoservicehub.service.impl;

import com.autoservicehub.dto.InspectionItemResponseDTO;
import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.dto.JobCardResponseDTO;
import com.autoservicehub.entity.*;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.*;
import com.autoservicehub.service.JobCardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Job Card (SRS 4.5) — the repair job raised once a vehicle has been inspected.
 *
 * <p>A job card may be raised from a vehicle inspection
 * ({@code JobCardRequestDTO.inspectionId}). When it is, the inspection's
 * recorded findings are surfaced on the job card so the technician working on
 * the repair sees what the inspection found without a second lookup. The
 * inspection's own fields are NOT copied over the job card: the job card keeps
 * its own complaint, notes and estimate, which staff edit as work progresses.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class JobCardServiceImpl implements JobCardService {

    private final JobCardRepository repository;
    private final CustomerRepository customerRepository;
    private final VehicleRepository vehicleRepository;
    private final MechanicRepository mechanicRepository;
    private final AppointmentRepository appointmentRepository;
    private final InspectionRepository inspectionRepository;
    private final InspectionItemRepository inspectionItemRepository;

    @Override
    public JobCardResponseDTO create(JobCardRequestDTO request) {
        JobCard entity = new JobCard();
        mapToEntity(request, entity);
        entity.setStatus("RECEIVED");
        entity.setAssignedDate(LocalDateTime.now());
        entity.setJobCardNumber(generateJobCardNumber());
        JobCard saved = repository.save(entity);
        linkInspection(request.getInspectionId(), saved);
        return toResponse(saved);
    }

    @Override
    public JobCardResponseDTO update(Long id, JobCardRequestDTO request) {
        JobCard existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + id));
        mapToEntity(request, existing);
        if ("DELIVERED".equalsIgnoreCase(request.getStatus()) && existing.getCompletedDate() == null) {
            existing.setCompletedDate(LocalDateTime.now());
        }
        JobCard saved = repository.save(existing);
        linkInspection(request.getInspectionId(), saved);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public JobCardResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<JobCardResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) throw new ResourceNotFoundException("JobCard not found: " + id);
        repository.deleteById(id);
    }

    /**
     * Attaches an inspection to a freshly saved job card, so the Inspection →
     * Job Card step of the workflow is recorded in one place.
     *
     * <p>The job card is saved first so the inspection can point at a real id.
     * The same ownership and vehicle checks applied from the inspection side
     * are applied here, so the rule behaves identically whichever side the
     * link is created from.
     */
    private void linkInspection(Long inspectionId, JobCard jobCard) {
        if (inspectionId == null) {
            return;
        }

        Inspection inspection = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found: " + inspectionId));

        if (inspection.getVehicle() == null
                || jobCard.getVehicle() == null
                || !inspection.getVehicle().getId().equals(jobCard.getVehicle().getId())) {
            throw new BusinessRuleException(
                    "Inspection " + inspectionId + " belongs to a different vehicle and cannot be "
                    + "attached to JobCard " + jobCard.getId() + ".");
        }

        // A job card is raised from at most one inspection. Re-attaching the
        // same inspection is a no-op and therefore allowed.
        Inspection linked = inspectionRepository.findByJobCardId(jobCard.getId()).orElse(null);
        if (linked != null && !linked.getId().equals(inspectionId)) {
            throw new BusinessRuleException(
                    "JobCard " + jobCard.getId() + " is already linked to inspection "
                    + linked.getId() + ".");
        }

        inspection.setJobCard(jobCard);
        inspectionRepository.save(inspection);
    }

    private void mapToEntity(JobCardRequestDTO r, JobCard e) {
        Customer customer = customerRepository.findById(r.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + r.getCustomerId()));
        Vehicle vehicle = vehicleRepository.findById(r.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + r.getVehicleId()));
        e.setCustomer(customer);
        e.setVehicle(vehicle);
        if (r.getMechanicId() != null) {
            e.setMechanic(mechanicRepository.findById(r.getMechanicId())
                    .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + r.getMechanicId())));
        }
        if (r.getAppointmentId() != null) {
            e.setAppointment(appointmentRepository.findById(r.getAppointmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + r.getAppointmentId())));
        }
        e.setServiceType(r.getServiceType());
        e.setComplaint(r.getComplaint());
        e.setTechnicianNotes(r.getTechnicianNotes());
        e.setOdometerReading(r.getOdometerReading());
        e.setEstimatedDelivery(r.getEstimatedDelivery());
        e.setEstimatedCost(r.getEstimatedCost());
        if (r.getStatus() != null) e.setStatus(r.getStatus());
    }

    private int statusToProgress(String status) {
        if (status == null) return 1;
        return switch (status.toUpperCase()) {
            case "RECEIVED" -> 1;
            case "INSPECTION" -> 2;
            case "IN_REPAIR", "IN REPAIR" -> 3;
            case "QUALITY_CHECK", "QUALITY CHECK", "QC" -> 4;
            case "DELIVERED" -> 5;
            default -> 1;
        };
    }

    private String generateJobCardNumber() {
        return "JC-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    private JobCardResponseDTO toResponse(JobCard e) {
        JobCardResponseDTO dto = new JobCardResponseDTO();
        dto.setId(e.getId());
        dto.setJobCardNumber(e.getJobCardNumber());
        if (e.getCustomer() != null) {
            dto.setCustomerId(e.getCustomer().getId());
            dto.setCustomerName(e.getCustomer().getName());
        }
        if (e.getVehicle() != null) {
            dto.setVehicleId(e.getVehicle().getId());
            dto.setVehicleInfo(e.getVehicle().getRegistrationNo() + " | " + e.getVehicle().getModel());
        }
        if (e.getMechanic() != null) {
            dto.setMechanicId(e.getMechanic().getId());
            dto.setMechanicName(e.getMechanic().getName());
        }
        dto.setServiceType(e.getServiceType());
        dto.setComplaint(e.getComplaint());
        dto.setTechnicianNotes(e.getTechnicianNotes());
        dto.setOdometerReading(e.getOdometerReading());
        dto.setEstimatedDelivery(e.getEstimatedDelivery());
        dto.setEstimatedCost(e.getEstimatedCost());
        dto.setStatus(e.getStatus());
        dto.setProgress(statusToProgress(e.getStatus()));
        dto.setAssignedDate(e.getAssignedDate());
        dto.setCompletedDate(e.getCompletedDate());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());

        // Surface the originating inspection's findings so the technician sees
        // what the inspection found without a second request. Left null when the
        // job card was raised without an inspection.
        applyInspection(e, dto);

        return dto;
    }

    /**
     * Copies the linked inspection's reference and checklist findings onto the
     * job-card response. Read-only: the job card's own fields are never
     * overwritten by inspection data.
     */
    private void applyInspection(JobCard e, JobCardResponseDTO dto) {
        if (e.getId() == null) {
            return;
        }
        Inspection inspection = inspectionRepository.findByJobCardId(e.getId()).orElse(null);
        if (inspection == null) {
            return;
        }

        dto.setInspectionId(inspection.getId());
        dto.setInspectionStatus(inspection.getStatus());

        List<InspectionItem> items =
                inspectionItemRepository.findByInspectionIdOrderByIdAsc(inspection.getId());
        dto.setInspectionItems(items.stream().map(this::itemToResponse).toList());
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
