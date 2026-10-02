package com.autoservicehub.repository;

import com.autoservicehub.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA repository for AuditLog. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 *
 * <p><b>Read-only by convention.</b> No update or delete finder is declared, and
 * none should be added: an audit trail that can be edited or removed is no
 * longer evidence. Retention is a database/archival concern, deliberately not
 * an application one.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /**
     * Every entry for one record, newest first — "what has happened to this
     * invoice?".
     */
    Page<AuditLog> findByEntityNameIgnoreCaseAndEntityIdOrderByIdDesc(String entityName,
                                                                     String entityId,
                                                                     Pageable pageable);

    /** Everything one user did, newest first. */
    Page<AuditLog> findByPerformedByOrderByIdDesc(String performedBy, Pageable pageable);

    /** Entries in a half-open window [from, to), newest first. */
    List<AuditLog> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdDesc(
            LocalDateTime from, LocalDateTime to);
}
