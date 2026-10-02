package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.AuditFilterDTO;
import com.autoservicehub.dto.AuditLogResponseDTO;
import com.autoservicehub.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * Audit Trail (SRS 6 - Auditability).
 * Base path: /api/v1/audit-logs
 * Requires a valid JWT.
 *
 * <p><b>Read-only, deliberately.</b> There is no create, update or delete
 * endpoint. Entries are written by the business services that perform the
 * action, never by a client, and nothing in the API may alter or remove them — an
 * audit trail that can be edited is not evidence. Adding a delete here would
 * defeat the purpose of the table.
 *
 * <p>Restricted to ADMIN, OWNER and MANAGER. Workshop roles (mechanics, service
 * advisors, inventory staff, billing users) are deliberately excluded: the trail
 * records who refused or deleted what across the whole business, which is
 * management information and not something every employee should be able to
 * browse.
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService service;

    /**
     * The audit trail, newest first, filtered as requested.
     * GET /api/v1/audit-logs?entityName=INVOICE&entityId=42&action=INVOICE_DELETE
     *     &result=FAILURE&performedBy=admin&from=2026-01-01T00:00:00&to=2026-02-01T00:00:00
     *
     * <p>All filters are optional. {@code from}/{@code to} are a half-open
     * window, {@code [from, to)}, so a caller passing the start of the following
     * day includes that day whole.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ApiResponse<Page<AuditLogResponseDTO>> list(
            @RequestParam(required = false) String entityName,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String performedBy,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            Pageable pageable) {

        AuditFilterDTO filter = new AuditFilterDTO();
        filter.setEntityName(entityName);
        filter.setEntityId(entityId);
        filter.setAction(action);
        filter.setResult(result);
        filter.setPerformedBy(performedBy);
        filter.setFrom(from);
        filter.setTo(to);

        return ApiResponse.ok(service.search(filter, pageable));
    }
}