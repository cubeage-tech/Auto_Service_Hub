package com.autoservicehub.repository;

import com.autoservicehub.entity.WorkNote;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WorkNoteRepository extends JpaRepository<WorkNote, Long> {
    List<WorkNote> findByJobCardIdOrderByCreatedAtAsc(Long jobCardId);
    List<WorkNote> findByJobTaskIdOrderByCreatedAtAsc(Long jobTaskId);
}