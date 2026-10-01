package com.autoservicehub.service.impl;

import com.autoservicehub.dto.JobTaskResponseDTO;
import com.autoservicehub.dto.MechanicWorkloadResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.MechanicWorkloadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class MechanicWorkloadServiceImpl implements MechanicWorkloadService {

    private final MechanicRepository mechanicRepository;
    private final JobCardRepository jobCardRepository;
    private final JobTaskRepository taskRepository;
    private final MechanicAccessService accessService;

    @Override
    @Transactional(readOnly = true)
    public MechanicWorkloadResponseDTO getMine() {
        return build(accessService.currentMechanic());
    }

    @Override
    @Transactional(readOnly = true)
    public MechanicWorkloadResponseDTO getForMechanic(Long mechanicId) {
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + mechanicId));
        accessService.assertCanAccess(mechanic);
        return build(mechanic);
    }

    private MechanicWorkloadResponseDTO build(Mechanic mechanic) {
        List<JobCard> activeJobs = jobCardRepository.findAllAssignedToMechanic(mechanic.getId()).stream()
                .filter(job -> !"DELIVERED".equalsIgnoreCase(job.getStatus())
                        && !"CANCELLED".equalsIgnoreCase(job.getStatus()))
                .toList();
        List<JobTaskResponseDTO> pendingTasks = taskRepository.findByMechanicIdOrderByCreatedAtAsc(mechanic.getId()).stream()
                .filter(task -> task.getJobCard() != null && accessService.isAssignedTo(task.getJobCard(), mechanic))
                .filter(task -> !"COMPLETED".equals(normalize(task.getStatus())) && !"DONE".equals(normalize(task.getStatus())))
                .map(this::toResponse).toList();

        MechanicWorkloadResponseDTO response = new MechanicWorkloadResponseDTO();
        response.setMechanicId(mechanic.getId());
        response.setMechanicName(mechanic.getName());
        response.setWorkloadCount(activeJobs.size());
        response.setPendingTaskCount(pendingTasks.size());
        response.setPendingTasks(pendingTasks);
        response.setAssignedJobs(activeJobs.stream().map(job -> {
            MechanicWorkloadResponseDTO.AssignedJobSummaryDTO summary = new MechanicWorkloadResponseDTO.AssignedJobSummaryDTO();
            summary.setJobCardId(job.getId());
            summary.setJobCardNumber(job.getJobCardNumber());
            summary.setServiceType(job.getServiceType());
            summary.setStatus(job.getStatus());
            summary.setAssignedDate(job.getAssignedDate());
            summary.setEstimatedDelivery(job.getEstimatedDelivery());
            return summary;
        }).toList());
        return response;
    }

    private JobTaskResponseDTO toResponse(JobTask task) {
        JobTaskResponseDTO response = new JobTaskResponseDTO();
        response.setId(task.getId());
        response.setJobCardId(task.getJobCard().getId());
        response.setJobCardNumber(task.getJobCard().getJobCardNumber());
        response.setMechanicId(task.getMechanic().getId());
        response.setDescription(task.getDescription());
        response.setStatus(task.getStatus());
        response.setLabourCost(task.getLabourCost());
        response.setCompletedAt(task.getCompletedAt());
        return response;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}