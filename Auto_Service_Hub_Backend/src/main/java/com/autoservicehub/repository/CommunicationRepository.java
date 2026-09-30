package com.autoservicehub.repository;

import com.autoservicehub.entity.Communication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for Communication (SRS FR-CRM-4).
 */
@Repository
public interface CommunicationRepository extends JpaRepository<Communication, Long>, JpaSpecificationExecutor<Communication> {

    /** Paged list of communications for a customer, newest first. */
    Page<Communication> findByCustomerIdOrderBySentAtDesc(Long customerId, Pageable pageable);

    /** Full list for use inside Customer 360 (bounded in service to reasonable size). */
    List<Communication> findTop20ByCustomerIdOrderBySentAtDesc(Long customerId);
}
