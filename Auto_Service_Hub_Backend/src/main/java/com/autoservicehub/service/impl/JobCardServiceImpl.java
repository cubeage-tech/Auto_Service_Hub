package com.autoservicehub.service.impl;

import com.autoservicehub.dto.InspectionItemResponseDTO;
import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.dto.JobCardResponseDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.*;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.*;
import com.autoservicehub.service.AuditService;
import com.autoservicehub.service.JobCardService;
import com.autoservicehub.util.BillingCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

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
    private final PackageApplicationRepository packageApplicationRepository;
    private final JobTaskRepository jobTaskRepository;
    private final BillingCalculator calculator;
    private final AuditService auditService;

    /** Entity name recorded on this module's audit entries. */
    private static final String AUDIT_ENTITY = "JOB_CARD";

    @Override
    public JobCardResponseDTO create(JobCardRequestDTO request) {
        JobCard entity = new JobCard();
        mapToEntity(request, entity);
        entity.setStatus("RECEIVED");
        entity.setAssignedDate(LocalDateTime.now());
        entity.setJobCardNumber(generateJobCardNumber());
        JobCard saved = repository.save(entity);
        linkInspection(request.getInspectionId(), saved);
        linkPackageApplication(request.getPackageApplicationId(), saved);

        JobCardResponseDTO response = toResponse(saved);
        auditService.recordSuccess(AUDIT_ENTITY, saved.getId(), AuditAction.JOB_CARD_CREATE,
                "Job card raised: " + saved.getJobCardNumber()
                        + ", serviceType " + request.getServiceType()
                        + ", vehicleId " + request.getVehicleId()
                        + ", mechanicId " + request.getMechanicId());
        return response;
    }

    @Override
    public JobCardResponseDTO update(Long id, JobCardRequestDTO request) {
        JobCard existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + id));
        // Captured before mapToEntity overwrites it.
        String statusBefore = existing.getStatus();

        mapToEntity(request, existing);
        if ("DELIVERED".equalsIgnoreCase(request.getStatus()) && existing.getCompletedDate() == null) {
            existing.setCompletedDate(LocalDateTime.now());
        }
        JobCard saved = repository.save(existing);
        linkInspection(request.getInspectionId(), saved);
        linkPackageApplication(request.getPackageApplicationId(), saved);

        JobCardResponseDTO response = toResponse(saved);
        // A status change is the audit question that matters on a job card —
        // where is this repair in the workflow — so it gets its own action type
        // rather than being folded into a general update.
        boolean statusChanged = statusBefore != null
                && request.getStatus() != null
                && !statusBefore.equalsIgnoreCase(request.getStatus());
        auditService.recordSuccess(AUDIT_ENTITY, id,
                statusChanged ? AuditAction.JOB_CARD_STATUS_CHANGE : AuditAction.JOB_CARD_UPDATE,
                "Job card updated: status " + statusBefore + " -> " + saved.getStatus()
                        + ", serviceType " + saved.getServiceType()
                        + ", mechanicId " + (saved.getMechanic() == null
                                ? null : saved.getMechanic().getId()));
        return response;
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

        // Tasks are owned by the job card, so they go with it. Left behind they
        // would be orphans pointing at a row that no longer exists.
        jobTaskRepository.deleteAll(jobTaskRepository.findByJobCardIdOrderByIdAsc(id));
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

    /**
     * Attaches an applied service package to a saved job card, so the technician
     * can see what the job is covered by without a second lookup.
     *
     * <p>Both ownership checks are made here rather than trusted from the
     * client: an application names a customer and a vehicle, and the job card
     * names its own. Linking is only valid when they agree on both, otherwise a
     * package sold to one customer could be attached to another's car and would
     * read as though that work were covered. The rule is the same one enforced
     * when the application was created, re-checked at the point of linking.
     *
     * <p>An absent id is "not mentioned", not "unlink" — an ordinary edit that
     * omits the field leaves an existing link exactly as it was, the same
     * convention the service package endpoint uses for {@code active}.
     */
    private void linkPackageApplication(Long packageApplicationId, JobCard jobCard) {
        if (packageApplicationId == null) {
            return;
        }

        PackageApplication application = packageApplicationRepository.findById(packageApplicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PackageApplication not found: " + packageApplicationId));

        Long applicationVehicleId = application.getVehicle() != null
                ? application.getVehicle().getId() : null;
        Long jobCardVehicleId = jobCard.getVehicle() != null ? jobCard.getVehicle().getId() : null;

        if (!Objects.equals(applicationVehicleId, jobCardVehicleId)) {
            throw new BusinessRuleException(
                    "PackageApplication " + packageApplicationId + " was applied to a different vehicle "
                    + "and cannot be attached to JobCard " + jobCard.getId() + ".");
        }

        Long applicationCustomerId = application.getCustomer() != null
                ? application.getCustomer().getId() : null;
        Long jobCardCustomerId = jobCard.getCustomer() != null ? jobCard.getCustomer().getId() : null;

        if (!Objects.equals(applicationCustomerId, jobCardCustomerId)) {
            throw new BusinessRuleException(
                    "PackageApplication " + packageApplicationId + " belongs to a different customer "
                    + "and cannot be attached to JobCard " + jobCard.getId() + ".");
        }

        jobCard.setPackageApplication(application);
        repository.save(jobCard);
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

        // Task count and labour total for this job (FR-JOB-3), both derived from
        // the stored tasks rather than anything the client sent. Skipped for an
        // unsaved entity, where there can be no tasks yet.
        if (e.getId() != null) {
            dto.setTaskCount(jobTaskRepository.countByJobCardId(e.getId()));
            dto.setTotalLabourCost(
                    calculator.money(jobTaskRepository.sumLabourCostByJobCardId(e.getId())));
        }

        // Surface the originating inspection's findings so the technician sees
        // what the inspection found without a second request. Left null when the
        // job card was raised without an inspection.
        applyInspection(e, dto);

        // Same idea for an applied package: report what the job is covered by.
        applyPackageApplication(e, dto);

        return dto;
    }

    /**
     * Copies the linked package application onto the job-card response, together
     * with the SERVICE items it includes.
     *
     * <p>Read-only and deliberately inert. Nothing here marks a task complete,
     * consumes a part or applies a price — the terms are the snapshot the
     * customer agreed to, reported so staff can see them, and are not folded
     * into {@code estimatedCost} or any total.
     *
     * <p>PART items are left out: they are consumables, not work, and a job card
     * tracks consumed parts separately through stock movements.
     */
    private void applyPackageApplication(JobCard e, JobCardResponseDTO dto) {
        PackageApplication application = e.getPackageApplication();
        if (application == null) {
            dto.setPackageServices(List.of());
            return;
        }

        dto.setPackageApplicationId(application.getId());
        dto.setPackageName(application.getPackageName());
        dto.setPackageType(application.getPackageType());
        dto.setPackageDuration(application.getPackageDuration());
        dto.setPackagePrice(application.getPackagePrice());
        dto.setPackageGstRate(application.getPackageGstRate());
        dto.setPackageDiscountPercentage(application.getPackageDiscountPercentage());

        ServicePackage servicePackage = application.getServicePackage();
        if (servicePackage == null) {
            dto.setPackageServices(List.of());
            return;
        }

        // fromStored is the lenient lookup: an item whose stored type this build
        // does not recognise reads as neither kind and is skipped, rather than
        // failing the whole job-card GET.
        dto.setPackageServices(servicePackage.getItems().stream()
                .filter(item -> PackageItemType.fromStored(item.getItemType()) == PackageItemType.SERVICE)
                .map(PackageItem::getItemName)
                .toList());
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
