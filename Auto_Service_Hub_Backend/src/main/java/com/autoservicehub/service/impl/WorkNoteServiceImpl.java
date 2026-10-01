package com.autoservicehub.service.impl;

import com.autoservicehub.dto.WorkNoteRequestDTO;
import com.autoservicehub.dto.WorkNoteResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.User;
import com.autoservicehub.entity.WorkNote;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.WorkNoteRepository;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.WorkNoteService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class WorkNoteServiceImpl implements WorkNoteService {

    private final WorkNoteRepository noteRepository;
    private final JobCardRepository jobCardRepository;
    private final JobTaskRepository taskRepository;
    private final MechanicAccessService accessService;
    private final ServiceAdvisorAccessService advisorAccessService;

    @Override
    public WorkNoteResponseDTO addToJobCard(Long jobCardId, WorkNoteRequestDTO request) {
        JobCard jobCard = findJobCard(jobCardId);
        advisorAccessService.assertCanAccess(jobCard);
        accessService.assertCanAccess(jobCard);
        Mechanic mechanic = accessService.isMechanicUser() ? accessService.currentMechanic() : null;
        return toResponse(noteRepository.save(create(jobCard, null, mechanic, request, accessService.currentUser())));
    }

    @Override
    public WorkNoteResponseDTO addToTask(Long taskId, WorkNoteRequestDTO request) {
        JobTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("JobTask not found: " + taskId));
        if (task.getJobCard() == null || task.getMechanic() == null) {
            throw new AccessDeniedException("Work notes require a task assigned to a mechanic and job card.");
        }
        advisorAccessService.assertCanAccess(task.getJobCard());
        accessService.assertCanAccess(task.getJobCard());
        if (accessService.isMechanicUser()
                && !task.getMechanic().getId().equals(accessService.currentMechanic().getId())) {
            throw new AccessDeniedException("Mechanics can add notes only to their assigned tasks.");
        }
        Mechanic mechanic = accessService.isMechanicUser() ? accessService.currentMechanic() : null;
        return toResponse(noteRepository.save(create(task.getJobCard(), task, mechanic, request, accessService.currentUser())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkNoteResponseDTO> listForJobCard(Long jobCardId) {
        JobCard jobCard = findJobCard(jobCardId);
        advisorAccessService.assertCanAccess(jobCard);
        accessService.assertCanAccess(jobCard);
        Mechanic currentMechanic = accessService.isMechanicUser() ? accessService.currentMechanic() : null;
        return noteRepository.findByJobCardIdOrderByCreatedAtAsc(jobCardId).stream()
            .filter(note -> currentMechanic == null || note.getJobTask() == null
                || note.getJobTask().getMechanic() != null
                && note.getJobTask().getMechanic().getId().equals(currentMechanic.getId()))
            .map(this::toResponse).toList();
    }

    private WorkNote create(JobCard card, JobTask task, Mechanic mechanic, WorkNoteRequestDTO request, User author) {
        WorkNote note = new WorkNote();
        note.setJobCard(card);
        note.setJobTask(task);
        note.setMechanic(mechanic);
        note.setAuthor(author);
        note.setContent(request.getContent().trim());
        return note;
    }

    private JobCard findJobCard(Long id) {
        return jobCardRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + id));
    }

    private WorkNoteResponseDTO toResponse(WorkNote note) {
        WorkNoteResponseDTO response = new WorkNoteResponseDTO();
        response.setId(note.getId());
        response.setJobCardId(note.getJobCard().getId());
        response.setJobTaskId(note.getJobTask() == null ? null : note.getJobTask().getId());
        response.setAuthorUserId(note.getAuthor().getId());
        response.setAuthorName(note.getAuthor().getFullName());
        response.setMechanicId(note.getMechanic() == null ? null : note.getMechanic().getId());
        response.setContent(note.getContent());
        response.setCreatedAt(note.getCreatedAt());
        return response;
    }
}