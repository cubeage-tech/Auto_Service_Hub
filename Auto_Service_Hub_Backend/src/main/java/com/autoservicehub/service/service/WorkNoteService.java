package com.autoservicehub.service;

import com.autoservicehub.dto.WorkNoteRequestDTO;
import com.autoservicehub.dto.WorkNoteResponseDTO;
import java.util.List;

public interface WorkNoteService {
    WorkNoteResponseDTO addToJobCard(Long jobCardId, WorkNoteRequestDTO request);
    WorkNoteResponseDTO addToTask(Long taskId, WorkNoteRequestDTO request);
    List<WorkNoteResponseDTO> listForJobCard(Long jobCardId);
}