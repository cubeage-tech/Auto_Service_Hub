package com.autoservicehub.service.impl;

import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.dto.JobCardResponseDTO;
import com.autoservicehub.dto.JobCardStatusHistoryResponseDTO;
import com.autoservicehub.entity.*;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.*;
import com.autoservicehub.service.DeliveryGateService;
import com.autoservicehub.service.JobCardService;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class JobCardServiceImpl implements JobCardService {

    private final JobCardRepository repository;
    private final CustomerRepository customerRepository;
    private final VehicleRepository vehicleRepository;
    private final MechanicRepository mechanicRepository;
    private final AppointmentRepository appointmentRepository;
    private final MechanicSkillRepository mechanicSkillRepository;
    private final JobCardStatusHistoryRepository statusHistoryRepository;
    private final AuditLogRepository auditLogRepository;
    private final MechanicAccessService accessService;
    private final ServiceAdvisorAccessService advisorAccessService;

    /**
     * SRS 12 BR-02 delivery gate. Read-only: decides whether a job card may move to
     * DELIVERED, and never mutates state.
     */
    private final DeliveryGateService deliveryGateService;

    @Value("${app.mechanics.skill-enforcement:true}")
    private boolean skillEnforcementEnabled;

    @Override
    public JobCardResponseDTO create(JobCardRequestDTO request) {
        JobCard entity = new JobCard();
        mapToEntity(request, entity);
        advisorAccessService.assertCanAccess(entity);
        entity.setStatus("RECEIVED");
        entity.setAssignedDate(LocalDateTime.now());
        entity.setJobCardNumber(generateJobCardNumber());
        JobCard saved = repository.save(entity);
        recordStatusChange(saved, null, "RECEIVED");
        return toResponse(saved);
    }

    @Override
    public JobCardResponseDTO update(Long id, JobCardRequestDTO request) {
        // Pessimistic write lock on the SAME job_cards row that
        // QualityCheckServiceImpl.record() locks, so the two paths serialise.
        //
        // Why here and not inside the gate: the lock must be taken before the gate
        // reads tasks and quality checks, otherwise a QC FAIL committed after that
        // read would not be seen and the vehicle could still be delivered. Loading
        // the card with FOR UPDATE also makes this read see the latest committed row
        // instead of a REPEATABLE READ snapshot.
        //
        // Lock ordering: both paths take the job_cards row first and exclusively.
        // The gate's task/QC reads are non-locking aggregates and never take another
        // lock, so there is no ordering inversion and no deadlock potential.
        //
        // This is applied to the single load in update() rather than being made
        // conditional on the target status, so that no future path through update()
        // can reach DELIVERED without holding the lock. update() is a low-frequency
        // human-initiated operation, so the extra serialisation is not a throughput
        // concern. getById() is read-only and deliberately keeps the plain findById.
        JobCard existing = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + id));
        advisorAccessService.assertCanAccess(existing);
        accessService.assertCanAccess(existing);
        if (accessService.isMechanicUser()) {
            validateMechanicUpdate(existing, request);
            if (request.getTechnicianNotes() != null) existing.setTechnicianNotes(request.getTechnicianNotes());
            changeStatus(existing, request.getStatus());
            return toResponse(repository.save(existing));
        }
        if (advisorAccessService.isAdvisorUser() && request.getAppointmentId() != null
                && (existing.getAppointment() == null
                || !request.getAppointmentId().equals(existing.getAppointment().getId()))) {
            throw new AccessDeniedException("Service Advisors cannot move a job card to another appointment.");
        }
        Set<Long> originalMechanicIds = assignedMechanicIds(existing);
        mapToEntity(request, existing);
        advisorAccessService.assertCanAccess(existing);
        Set<Long> updatedMechanicIds = assignedMechanicIds(existing);
        if (!originalMechanicIds.equals(updatedMechanicIds)) {
            validateAssignments(existing, assignedMechanics(existing));
            recordAssignmentAudit(existing, originalMechanicIds, updatedMechanicIds, "MANUAL_ASSIGNMENT");
        }
        changeStatus(existing, request.getStatus());
        return toResponse(repository.save(existing));
    }

    @Override
    public JobCardResponseDTO assignMechanics(Long id, java.util.List<Long> mechanicIds) {
        if (!accessService.isManagementUser()) {
            throw new org.springframework.security.access.AccessDeniedException("Only management can confirm mechanic assignments.");
        }
        if (mechanicIds == null || mechanicIds.isEmpty()) {
            throw new BusinessRuleException("At least one mechanic must be selected.");
        }
        Set<Long> uniqueIds = new LinkedHashSet<>(mechanicIds);
        if (uniqueIds.contains(null) || uniqueIds.size() != mechanicIds.size()) {
            throw new BusinessRuleException("Mechanic IDs must be non-null and unique.");
        }

        JobCard jobCard = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + id));
        advisorAccessService.assertCanAccess(jobCard);
        Set<Long> previousIds = assignedMechanicIds(jobCard);
        Set<Mechanic> mechanics = uniqueIds.stream().map(mechanicId -> mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + mechanicId)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        validateAssignments(jobCard, mechanics);
        jobCard.setAssignedMechanics(mechanics);
        jobCard.setMechanic(mechanics.iterator().next());
        JobCard saved = repository.save(jobCard);
        recordAssignmentAudit(saved, previousIds, uniqueIds, "AI_RECOMMENDATION_CONFIRMED");
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobCardStatusHistoryResponseDTO> getStatusHistory(Long id) {
        JobCard jobCard = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + id));
        advisorAccessService.assertCanAccess(jobCard);
        accessService.assertCanAccess(jobCard);
        return statusHistoryRepository.findByJobCardIdOrderByCreatedAtAsc(id).stream().map(history -> {
            JobCardStatusHistoryResponseDTO response = new JobCardStatusHistoryResponseDTO();
            response.setId(history.getId());
            response.setFromStatus(history.getFromStatus());
            response.setToStatus(history.getToStatus());
            response.setChangedByUserId(history.getChangedBy().getId());
            response.setChangedBy(history.getChangedBy().getFullName());
            response.setChangedAt(history.getCreatedAt());
            return response;
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public JobCardResponseDTO getById(Long id) {
        JobCard jobCard = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + id));
        advisorAccessService.assertCanAccess(jobCard);
        accessService.assertCanAccess(jobCard);
        return toResponse(jobCard);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<JobCardResponseDTO> list(Pageable pageable) {
        if (advisorAccessService.isAdvisorUser()) {
            return repository.findVisibleToAdvisor(advisorAccessService.currentAdvisor().getId(), pageable).map(this::toResponse);
        }
        if (accessService.isMechanicUser()) {
            return repository.findAssignedToMechanic(accessService.currentMechanic().getId(), pageable).map(this::toResponse);
        }
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        JobCard jobCard = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + id));
        advisorAccessService.assertCanAccess(jobCard);
        repository.delete(jobCard);
    }

    private void mapToEntity(JobCardRequestDTO r, JobCard e) {
        Customer customer = customerRepository.findById(r.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + r.getCustomerId()));
        Vehicle vehicle = vehicleRepository.findById(r.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + r.getVehicleId()));
        if (vehicle.getCustomer() == null || !customer.getId().equals(vehicle.getCustomer().getId())) {
            throw new BusinessRuleException("The selected vehicle does not belong to the selected customer.");
        }
        e.setCustomer(customer);
        e.setVehicle(vehicle);
        if (r.getRequiredSkills() != null) {
            if (r.getRequiredSkills().stream().anyMatch(skill -> skill == null || skill.isBlank())) {
                throw new BusinessRuleException("Required skill names must not be blank.");
            }
            e.setRequiredSkills(r.getRequiredSkills().stream().map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new)));
        }
        updateMechanicAssignments(r, e);
        if (r.getRequiredSkills() != null && r.getMechanicIds() == null && r.getMechanicId() == null
                && !Boolean.TRUE.equals(r.getClearAssignment())) {
            validateAssignments(e, assignedMechanics(e));
        }
        if (r.getAppointmentId() != null) {
            e.setAppointment(appointmentRepository.findById(r.getAppointmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + r.getAppointmentId())));
        }
        if (e.getAppointment() != null) {
            Appointment appointment = e.getAppointment();
            if (appointment.getCustomer() != null && !customer.getId().equals(appointment.getCustomer().getId())) {
                throw new BusinessRuleException("Job card customer must match the linked appointment customer.");
            }
            if (appointment.getVehicle() != null && !vehicle.getId().equals(appointment.getVehicle().getId())) {
                throw new BusinessRuleException("Job card vehicle must match the linked appointment vehicle.");
            }
        }
        e.setServiceType(r.getServiceType());
        e.setComplaint(r.getComplaint());
        e.setTechnicianNotes(r.getTechnicianNotes());
        e.setOdometerReading(r.getOdometerReading());
        e.setEstimatedDelivery(r.getEstimatedDelivery());
        e.setEstimatedCost(r.getEstimatedCost());
    }

    private void updateMechanicAssignments(JobCardRequestDTO request, JobCard jobCard) {
        boolean assignmentSpecified = request.getClearAssignment() != null && request.getClearAssignment()
                || request.getMechanicIds() != null || request.getMechanicId() != null;
        if (!assignmentSpecified) return;

        if (Boolean.TRUE.equals(request.getClearAssignment())) {
            jobCard.setAssignedMechanics(new LinkedHashSet<>());
            jobCard.setMechanic(null);
            return;
        }

        Set<Long> mechanicIds = request.getMechanicIds() == null
                ? new LinkedHashSet<>(Set.of(request.getMechanicId()))
                : new LinkedHashSet<>(request.getMechanicIds());
        if (mechanicIds.contains(null)) throw new BusinessRuleException("Mechanic IDs must not contain null values.");
        if (request.getMechanicId() != null) mechanicIds.add(request.getMechanicId());

        Set<Mechanic> mechanics = mechanicIds.stream().map(mechanicId -> mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + mechanicId)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        validateAssignments(jobCard, mechanics);
        jobCard.setAssignedMechanics(mechanics);
        jobCard.setMechanic(mechanics.stream().findFirst().orElse(null));
    }

    private void validateAssignments(JobCard jobCard, Set<Mechanic> mechanics) {
        for (Mechanic mechanic : mechanics) {
            if (!"ACTIVE".equalsIgnoreCase(mechanic.getStatus())) {
                throw new BusinessRuleException("Inactive mechanics cannot be assigned to a job card.");
            }
            if (skillEnforcementEnabled && !jobCard.getRequiredSkills().isEmpty()) {
                Set<String> available = mechanicSkillRepository.findByMechanicIdOrderBySkillNameAsc(mechanic.getId()).stream()
                        .map(MechanicSkill::getSkillName).map(this::normalizeSkill).collect(Collectors.toSet());
                Set<String> missing = jobCard.getRequiredSkills().stream().map(this::normalizeSkill)
                        .filter(required -> !available.contains(required)).collect(Collectors.toSet());
                if (!missing.isEmpty()) {
                    throw new BusinessRuleException("Mechanic " + mechanic.getName() + " does not have required skills: " + String.join(", ", missing));
                }
            }
        }
    }

    private void validateMechanicUpdate(JobCard jobCard, JobCardRequestDTO request) {
        if (Boolean.TRUE.equals(request.getClearAssignment())
                || request.getMechanicIds() != null && !assignedMechanicIds(jobCard).equals(new LinkedHashSet<>(request.getMechanicIds()))
                || request.getMechanicId() != null && !assignedMechanicIds(jobCard).contains(request.getMechanicId())) {
            throw new AccessDeniedException("Mechanics cannot change job assignments.");
        }
    }

    private void changeStatus(JobCard jobCard, String requestedStatus) {
        if (requestedStatus == null || requestedStatus.isBlank()) return;
        String current = normalizeStatus(jobCard.getStatus());
        String next = normalizeStatus(requestedStatus);
        String[] workflow = {"RECEIVED", "INSPECTION", "IN_REPAIR", "QUALITY_CHECK", "DELIVERED"};
        String currentStage = canonicalStatus(current);
        String nextStage = canonicalStatus(next);
        int currentIndex = java.util.Arrays.asList(workflow).indexOf(currentStage);
        int nextIndex = java.util.Arrays.asList(workflow).indexOf(nextStage);
        if (currentIndex < 0 || nextIndex < 0 || nextIndex < currentIndex || nextIndex > currentIndex + 1) {
            throw new BusinessRuleException("Invalid job status transition from " + jobCard.getStatus() + " to " + requestedStatus + ".");
        }
        if (!currentStage.equals(nextStage)) {
            String previousStatus = jobCard.getStatus();

            // ── SRS 12 BR-02 delivery gate ────────────────────────────────────
            // Reached only AFTER the transition has been validated above, so an
            // invalid transition still reports the transition problem rather than a
            // QC problem. nextStage is the canonical form, so the DELIVERED alias
            // path is covered too. The gate is a read-only evaluation: it takes no
            // lock and mutates nothing, so it introduces no lock-ordering
            // interaction with the job-card pessimistic lock used when recording a
            // quality check.
            if ("DELIVERED".equals(nextStage)) {
                deliveryGateService.assertCanDeliver(jobCard);
            }

            jobCard.setStatus(next);
            recordStatusChange(jobCard, previousStatus, next);
            if ("IN_REPAIR".equals(nextStage) && jobCard.getStartedDate() == null) {
                jobCard.setStartedDate(LocalDateTime.now());
            }
            if ("DELIVERED".equals(nextStage)) jobCard.setCompletedDate(LocalDateTime.now());
        }
    }

    private void recordStatusChange(JobCard jobCard, String fromStatus, String toStatus) {
        JobCardStatusHistory history = new JobCardStatusHistory();
        history.setJobCard(jobCard);
        history.setFromStatus(fromStatus);
        history.setToStatus(toStatus);
        history.setChangedBy(accessService.currentUser());
        statusHistoryRepository.save(history);
    }

    private void recordAssignmentAudit(JobCard jobCard, Set<Long> previousIds, Set<Long> currentIds, String action) {
        AuditLog auditLog = new AuditLog();
        auditLog.setEntityName("JobCard");
        auditLog.setEntityId(jobCard.getId().toString());
        auditLog.setAction(action);
        auditLog.setPerformedBy(accessService.currentUser().getUsername());
        auditLog.setDetails("Mechanic IDs changed from " + previousIds + " to " + currentIds);
        auditLogRepository.save(auditLog);
    }

    private Set<Long> assignedMechanicIds(JobCard jobCard) {
        Set<Long> ids = jobCard.getAssignedMechanics().stream().map(Mechanic::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (jobCard.getMechanic() != null) ids.add(jobCard.getMechanic().getId());
        return ids;
    }

    private Set<Mechanic> assignedMechanics(JobCard jobCard) {
        Set<Mechanic> mechanics = new LinkedHashSet<>(jobCard.getAssignedMechanics());
        if (jobCard.getMechanic() != null) mechanics.add(jobCard.getMechanic());
        return mechanics;
    }

    private String normalizeSkill(String skill) {
        return skill.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private String canonicalStatus(String status) {
        return switch (status) {
            case "VEHICLE_RECEIVED" -> "RECEIVED";
            case "REPAIR_STARTED" -> "IN_REPAIR";
            case "QC" -> "QUALITY_CHECK";
            default -> status;
        };
    }

    private int statusToProgress(String status) {
        if (status == null) return 1;
        return switch (canonicalStatus(normalizeStatus(status))) {
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
        dto.setMechanicIds(assignedMechanicIds(e));
        dto.setRequiredSkills(new LinkedHashSet<>(e.getRequiredSkills()));
        dto.setServiceType(e.getServiceType());
        dto.setComplaint(e.getComplaint());
        dto.setTechnicianNotes(e.getTechnicianNotes());
        dto.setOdometerReading(e.getOdometerReading());
        dto.setEstimatedDelivery(e.getEstimatedDelivery());
        dto.setEstimatedCost(e.getEstimatedCost());
        dto.setStatus(e.getStatus());
        dto.setProgress(statusToProgress(e.getStatus()));
        dto.setAssignedDate(e.getAssignedDate());
        dto.setStartedDate(e.getStartedDate());
        dto.setCompletedDate(e.getCompletedDate());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
