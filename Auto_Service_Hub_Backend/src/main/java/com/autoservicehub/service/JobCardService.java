package com.autoservicehub.service;

import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.dto.JobCardResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Digital Job Card (SRS 4.5)
 */
public interface JobCardService {

    JobCardResponseDTO create(JobCardRequestDTO request);

    JobCardResponseDTO update(Long id, JobCardRequestDTO request);

    JobCardResponseDTO getById(Long id);

    Page<JobCardResponseDTO> list(Pageable pageable);

    void delete(Long id);
}
