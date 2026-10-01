package com.autoservicehub.service;

import com.autoservicehub.dto.JobTaskCreateRequestDTO;
import com.autoservicehub.dto.JobTaskRequestDTO;
import com.autoservicehub.dto.JobTaskResponseDTO;
import java.util.List;

public interface JobTaskService {
    JobTaskResponseDTO create(Long jobCardId, JobTaskCreateRequestDTO request);
    List<JobTaskResponseDTO> listForJobCard(Long jobCardId);
    List<JobTaskResponseDTO> listMine();
    JobTaskResponseDTO update(Long taskId, JobTaskRequestDTO request);
    JobTaskResponseDTO complete(Long taskId);
}