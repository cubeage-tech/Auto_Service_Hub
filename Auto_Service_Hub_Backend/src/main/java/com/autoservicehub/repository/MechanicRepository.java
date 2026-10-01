package com.autoservicehub.repository;

import com.autoservicehub.entity.Mechanic;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MechanicRepository
        extends JpaRepository<Mechanic, Long>,
                JpaSpecificationExecutor<Mechanic> {

    // Find mechanic by associated user
    Optional<Mechanic> findByUserId(Long userId);

    // Lock a mechanic record while updating it
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Mechanic m where m.id = :id")
    Optional<Mechanic> findByIdForUpdate(@Param("id") Long id);

    // AI mechanic assignment: find mechanics by employment status
    List<Mechanic> findByStatusIgnoreCase(String status);
}