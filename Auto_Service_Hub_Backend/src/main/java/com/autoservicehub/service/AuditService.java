package com.autoservicehub.service;

import com.autoservicehub.dto.AuditLogResponseDTO;
import com.autoservicehub.dto.AuditFilterDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.AuditResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The one place audit records are written and read (SRS 6 - Auditability).
 *
 * <p>Every business module calls these methods instead of touching
 * {@code AuditLogRepository}, so user attribution, IP resolution and detail
 * scrubbing happen identically everywhere. There is deliberately no second way
 * to write an audit row: a module that built its own would be the one place
 * attribution or redaction was quietly skipped.
 *
 * <h2>Transaction behaviour</h2>
 * The two record methods deliberately behave differently, because "did this
 * happen?" and "was this refused?" have opposite transaction requirements:
 *
 * <ul>
 *   <li>{@link #recordSuccess} <b>joins</b> the caller's transaction. A success
 *       entry therefore disappears if the business operation rolls back, so the
 *       trail never claims work happened that did not.</li>
 *   <li>{@link #recordFailure} runs in its <b>own</b> transaction. It must
 *       survive the rollback of the operation it describes, or every refused
 *       attempt — the most interesting entries in the table — would vanish.</li>
 * </ul>
 *
 * <h2>Attribution</h2>
 * The actor is resolved from the Spring Security context and can never be
 * supplied by a caller. Work with no signed-in user is recorded as
 * {@code SYSTEM}, and a request that arrived unauthenticated as
 * {@code ANONYMOUS}, so "who did this" always has an answer.
 */
public interface AuditService {

    /**
     * Records that an operation completed, in the caller's transaction.
     *
     * <p>Rolls back with the surrounding business transaction.
     *
     * @param entityName what kind of record was acted on, e.g. {@code INVOICE}
     * @param entityId   its id, or null when the record has none yet
     * @param action     what was done
     * @param details    a short description; scrubbed and truncated, never trusted
     */
    void recordSuccess(String entityName, Object entityId, AuditAction action, String details);

    /**
     * Records that an operation was refused or failed, in its own transaction so
     * it survives the rollback of the operation it describes.
     *
     * @param errorMessage a safe summary of what went wrong; scrubbed and truncated
     */
    void recordFailure(String entityName, Object entityId, AuditAction action,
                       String details, String errorMessage);

    /**
     * Filters the trail for the audit read endpoint.
     *
     * <p>Every filter is optional and they combine with AND. Windows are
     * half-open, [from, to), matching the rest of the project's report queries.
     */
    Page<AuditLogResponseDTO> search(AuditFilterDTO filter, Pageable pageable);
}