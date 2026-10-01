package com.autoservicehub.repository;

import com.autoservicehub.entity.JobCardStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface JobCardStatusHistoryRepository extends JpaRepository<JobCardStatusHistory, Long> {
    List<JobCardStatusHistory> findByJobCardIdOrderByCreatedAtAsc(Long jobCardId);
}