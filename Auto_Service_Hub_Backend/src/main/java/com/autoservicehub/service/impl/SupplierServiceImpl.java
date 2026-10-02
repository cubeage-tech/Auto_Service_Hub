package com.autoservicehub.service.impl;

import com.autoservicehub.dto.PurchaseResponseDTO;
import com.autoservicehub.dto.SupplierRequestDTO;
import com.autoservicehub.dto.SupplierResponseDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.Supplier;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.PurchaseRepository;
import com.autoservicehub.repository.SupplierRepository;
import com.autoservicehub.service.AuditService;
import com.autoservicehub.service.PurchaseService;
import com.autoservicehub.service.SupplierService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier Management (FR-INV-2).
 *
 * <p>Two rules guard the supplier list. Names must be unique: two suppliers
 * called "Bharat Auto" make every purchase ambiguous about who was bought from,
 * which is the exact question the supplier record exists to answer. And a
 * supplier that has purchase history is kept, because its name is referenced by
 * those purchases — removing it would silently break the purchase record.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository repository;
    private final PurchaseRepository purchaseRepository;
    private final PurchaseService purchaseService;
    private final AuditService auditService;

    /** Entity name recorded on this module's audit entries. */
    private static final String AUDIT_ENTITY = "SUPPLIER";

    @Override
    public SupplierResponseDTO create(SupplierRequestDTO request) {
        assertNameAvailable(request.getName(), null);

        Supplier entity = new Supplier();
        mapToEntity(request, entity);
        SupplierResponseDTO response = toResponse(repository.save(entity));
        auditService.recordSuccess(AUDIT_ENTITY, response.getId(), AuditAction.SUPPLIER_CREATE,
                "Supplier created: " + response.getName());
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierResponseDTO getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    /**
     * Free-text supplier search.
     *
     * <p>A blank term falls back to the plain list rather than returning nothing:
     * an empty search box should show everything, which is what the caller
     * means, and {@code LIKE '%%'} would match every row but is a needless scan
     * depending on the collation.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<SupplierResponseDTO> search(String term, Pageable pageable) {
        if (term == null || term.isBlank()) {
            return list(pageable);
        }
        return repository.search(term.trim(), pageable).map(this::toResponse);
    }

    /**
     * The supplier's purchase orders, newest first.
     *
     * <p>Delegates to {@link PurchaseService#listBySupplier} rather than
     * re-querying, so the ordering and the "unknown supplier" behaviour are
     * identical whether a buyer reaches history from here or from
     * {@code GET /api/v1/purchases/supplier/{id}}.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PurchaseResponseDTO> purchaseHistory(Long supplierId, Pageable pageable) {
        return purchaseService.listBySupplier(supplierId, pageable);
    }

    @Override
    public SupplierResponseDTO update(Long id, SupplierRequestDTO request) {
        Supplier existing = findOrThrow(id);
        // The row being updated is excluded from the duplicate check, so
        // re-saving a supplier without changing anything is not a conflict.
        assertNameAvailable(request.getName(), id);

        mapToEntity(request, existing);
        SupplierResponseDTO response = toResponse(repository.save(existing));
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.SUPPLIER_UPDATE,
                "Supplier updated: " + response.getName());
        return response;
    }

    @Override
    public void delete(Long id) {
        Supplier existing = findOrThrow(id);

        if (purchaseRepository.existsBySupplierId(id)) {
            throw new BusinessRuleException(
                    "Supplier " + existing.getName()
                    + " cannot be deleted because it has purchase history.");
        }

        repository.delete(existing);
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.SUPPLIER_DELETE,
                "Supplier deleted: " + existing.getName());
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Supplier findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + id));
    }

    /**
     * Rejects a name already held by another supplier.
     *
     * <p>{@code selfId} is the supplier being updated, or null on create; it is
     * excluded from the lookup so a supplier never collides with itself.
     */
    private void assertNameAvailable(String name, Long selfId) {
        if (name == null || name.isBlank()) {
            // Required-ness is enforced by bean validation; this is the guard for
            // callers that reach the service directly.
            throw new BusinessRuleException("name is required.");
        }
        long matches = repository.countByNameIgnoreCaseAndIdNot(name.trim(), selfId == null ? -1L : selfId);
        if (matches > 0) {
            throw new BusinessRuleException("A supplier named '" + name.trim() + "' already exists.");
        }
    }

    private void mapToEntity(SupplierRequestDTO r, Supplier e) {
        e.setName(r.getName().trim());
        e.setPhone(trimToNull(r.getPhone()));
        e.setEmail(trimToNull(r.getEmail()));
        e.setAddress(trimToNull(r.getAddress()));
    }

    /** Blank optional fields are stored as null rather than as an empty string. */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private SupplierResponseDTO toResponse(Supplier e) {
        SupplierResponseDTO dto = new SupplierResponseDTO();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setPhone(e.getPhone());
        dto.setEmail(e.getEmail());
        dto.setAddress(e.getAddress());
        // Only meaningful once the supplier has an id; an unsaved entity has no
        // purchases by definition.
        dto.setPurchaseCount(e.getId() == null ? 0L : purchaseRepository.countPurchasesBySupplierId(e.getId()));
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}