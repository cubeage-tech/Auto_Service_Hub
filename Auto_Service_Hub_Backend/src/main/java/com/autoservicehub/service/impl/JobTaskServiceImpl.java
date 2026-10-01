package com.autoservicehub.service.impl;

import com.autoservicehub.dto.JobTaskCreateRequestDTO;
import com.autoservicehub.dto.JobTaskRequestDTO;
import com.autoservicehub.dto.JobTaskResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.service.JobTaskService;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional
public class JobTaskServiceImpl implements JobTaskService {

    private final JobTaskRepository taskRepository;
    private final JobCardRepository jobCardRepository;
    private final MechanicRepository mechanicRepository;
    private final MechanicAccessService accessService;
    private final ServiceAdvisorAccessService advisorAccessService;

    @Override
    public JobTaskResponseDTO create(Long jobCardId, JobTaskCreateRequestDTO request) {
        if (accessService.isMechanicUser()) throw new AccessDeniedException("Mechanics cannot create or assign tasks.");
        JobCard jobCard = findJobCard(jobCardId);
        advisorAccessService.assertCanAccess(jobCard);
        Mechanic mechanic = mechanicRepository.findById(request.getMechanicId())
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + request.getMechanicId()));
        if (!"ACTIVE".equalsIgnoreCase(mechanic.getStatus())) {
            throw new BusinessRuleException("Inactive mechanics cannot be assigned tasks.");
        }
        if (!accessService.isAssignedTo(jobCard, mechanic)) {
            throw new BusinessRuleException("Task assignee must also be assigned to the job card.");
        }
        JobTask task = new JobTask();
        task.setJobCard(jobCard);
        task.setMechanic(mechanic);
        task.setDescription(request.getDescription().trim());
        task.setLabourCost(request.getLabourCost());
        task.setStatus("PENDING");
        return toResponse(taskRepository.save(task));
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobTaskResponseDTO> listForJobCard(Long jobCardId) {
        JobCard jobCard = findJobCard(jobCardId);
        advisorAccessService.assertCanAccess(jobCard);
        accessService.assertCanAccess(jobCard);
        Mechanic currentMechanic = accessService.isMechanicUser() ? accessService.currentMechanic() : null;
        return taskRepository.findByJobCardIdOrderByCreatedAtAsc(jobCardId).stream()
            .filter(task -> currentMechanic == null
                || task.getMechanic() != null && task.getMechanic().getId().equals(currentMechanic.getId()))
            .map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobTaskResponseDTO> listMine() {
        Mechanic mechanic = accessService.currentMechanic();
        return taskRepository.findByMechanicIdOrderByCreatedAtAsc(mechanic.getId()).stream()
                .filter(task -> task.getJobCard() != null && accessService.isAssignedTo(task.getJobCard(), mechanic))
                .map(this::toResponse).toList();
    }

    @Override
    public JobTaskResponseDTO update(Long taskId, JobTaskRequestDTO request) {
        JobTask task = findTask(taskId);
        authorizeTask(task);
        String next = normalize(request.getStatus());
        if (!List.of("PENDING", "IN_PROGRESS", "COMPLETED").contains(next)) {
            throw new BusinessRuleException("Task status must be PENDING, IN_PROGRESS, or COMPLETED.");
        }
        String current = normalize(task.getStatus());
        List<String> workflow = List.of("PENDING", "IN_PROGRESS", "COMPLETED");
        int currentIndex = workflow.indexOf(current);
        int nextIndex = workflow.indexOf(next);
        if (currentIndex < 0 || nextIndex < currentIndex) {
            throw new BusinessRuleException("Invalid task status transition from " + task.getStatus() + " to " + next + ".");
        }
        task.setStatus(next);
        if ("COMPLETED".equals(next) && task.getCompletedAt() == null) {
            task.setCompletedAt(LocalDateTime.now());
            task.setCompletedBy(accessService.currentUser());
        }
        return toResponse(taskRepository.save(task));
    }

    @Override
    public JobTaskResponseDTO complete(Long taskId) {
        JobTask task = findTask(taskId);
        authorizeTask(task);
        if (!"COMPLETED".equals(normalize(task.getStatus()))) {
            task.setStatus("COMPLETED");
            task.setCompletedAt(LocalDateTime.now());
            task.setCompletedBy(accessService.currentUser());
        }
        return toResponse(taskRepository.save(task));
    }

    private void authorizeTask(JobTask task) {
        if (task.getJobCard() == null || task.getMechanic() == null) {
            throw new BusinessRuleException("Task must be linked to a job card and mechanic before it can be updated.");
        }
        advisorAccessService.assertCanAccess(task.getJobCard());
        accessService.assertCanAccess(task.getJobCard());
        if (accessService.isMechanicUser()
                && !task.getMechanic().getId().equals(accessService.currentMechanic().getId())) {
            throw new AccessDeniedException("Mechanics can update only their assigned tasks.");
        }
    }

    private JobCard findJobCard(Long id) {
        return jobCardRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + id));
    }

    private JobTask findTask(Long id) {
        return taskRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("JobTask not found: " + id));
    }

    private String normalize(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private JobTaskResponseDTO toResponse(JobTask task) {
        JobTaskResponseDTO response = new JobTaskResponseDTO();
        response.setId(task.getId());
        response.setDescription(task.getDescription());
        response.setStatus(task.getStatus());
        response.setLabourCost(task.getLabourCost());
        response.setCompletedAt(task.getCompletedAt());
        response.setCompletedByUserId(task.getCompletedBy() == null ? null : task.getCompletedBy().getId());
        if (task.getJobCard() != null) {
            response.setJobCardId(task.getJobCard().getId());
            response.setJobCardNumber(task.getJobCard().getJobCardNumber());
        }
        if (task.getMechanic() != null) response.setMechanicId(task.getMechanic().getId());
        return response;
    }
}