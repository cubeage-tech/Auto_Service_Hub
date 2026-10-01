package com.autoservicehub.service;

import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.dto.JobCardResponseDTO;
import com.autoservicehub.dto.JobCardStatusHistoryResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

/**
 * Digital Job Card (SRS 4.5)
 */
public interface JobCardService {

    JobCardResponseDTO create(JobCardRequestDTO request);

    JobCardResponseDTO update(Long id, JobCardRequestDTO request);

    JobCardResponseDTO assignMechanics(Long id, List<Long> mechanicIds);

    List<JobCardStatusHistoryResponseDTO> getStatusHistory(Long id);

    JobCardResponseDTO getById(Long id);

    Page<JobCardResponseDTO> list(Pageable pageable);

    void delete(Long id);
}
