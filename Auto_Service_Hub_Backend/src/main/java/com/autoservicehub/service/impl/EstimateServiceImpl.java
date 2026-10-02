package com.autoservicehub.service.impl;

import com.autoservicehub.dto.EstimateItemRequestDTO;
import com.autoservicehub.dto.EstimateItemResponseDTO;
import com.autoservicehub.dto.EstimateRequestDTO;
import com.autoservicehub.dto.EstimateResponseDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.Estimate;
import com.autoservicehub.entity.EstimateItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.EstimateItemRepository;
import com.autoservicehub.repository.EstimateRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.service.AuditService;
import com.autoservicehub.service.EstimateService;
import com.autoservicehub.service.InvoiceService;
import com.autoservicehub.util.BillingCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Billing - Estimates (SRS 4.9).
 *
 * <p>An estimate quotes a repair job, so it always belongs to a
 * {@link JobCard}, and its value is defined entirely by its line items.
 * {@code subtotal}, {@code tax} and {@code total} are computed here by
 * {@link BillingCalculator}; values sent by the client for those fields are
 * ignored entirely.
 *
 * <p>Each line's amount is calculated and stored, so the recorded estimate can
 * be re-checked later without trusting what was sent.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EstimateServiceImpl implements EstimateService {

    /**
     * Estimate status meaning "this quote has become an invoice".
     *
     * <p>The single definition of that word: {@link #assertNotConverted} freezes
     * an estimate with this status, and {@code InvoiceServiceImpl} sets it when a
     * conversion succeeds. One constant, so the freeze and the transition can
     * never disagree.
     */
    public static final String STATUS_CONVERTED = "CONVERTED";

    private final EstimateRepository     repository;
    private final EstimateItemRepository itemRepository;
    private final JobCardRepository      jobCardRepository;
    private final InvoiceService         invoiceService;
    private final BillingCalculator      calculator;
    private final AuditService          auditService;

    /** Entity name recorded on this module's audit entries. */
    private static final String AUDIT_ENTITY = "ESTIMATE";

    @Override
    public EstimateResponseDTO create(EstimateRequestDTO request) {
        // Validate every line before anything is written, so a malformed
        // payload cannot even reach the database.
        validateItems(request.getItems());

        Estimate entity = new Estimate();
        mapToEntity(request, entity);
        entity.setStatus(request.getStatus() != null ? request.getStatus() : "DRAFT");

        Estimate saved = repository.save(entity);
        // Totals depend on the persisted lines, so they are computed only once
        // the estimate has an id and the lines have been written.
        replaceItems(request.getItems(), saved);
        recalculate(saved);

        EstimateResponseDTO response = toResponse(saved);
        auditService.recordSuccess(AUDIT_ENTITY, saved.getId(), AuditAction.ESTIMATE_CREATE,
                "Estimate raised on job card " + request.getJobCardId()
                        + ": lines " + request.getItems().size()
                        + ", total " + saved.getTotal());
        return response;
    }

    @Override
    public EstimateResponseDTO update(Long id, EstimateRequestDTO request) {
        Estimate existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate not found: " + id));

        // An estimate already converted to an invoice must keep its value, so
        // its lines are frozen.
        assertNotConverted(existing);
        validateItems(request.getItems());

        mapToEntity(request, existing);
        replaceItems(request.getItems(), existing);
        recalculate(existing);

        EstimateResponseDTO response = toResponse(repository.save(existing));
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.ESTIMATE_UPDATE,
                "Estimate updated: lines " + request.getItems().size()
                        + ", total " + response.getTotal());
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public EstimateResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EstimateResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        Estimate existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate not found: " + id));
        assertNotConverted(existing);

        // Lines are owned by the estimate, so they are removed with it.
        itemRepository.deleteAll(itemRepository.findByEstimateIdOrderByIdAsc(id));
        BigDecimal total = existing.getTotal();
        repository.deleteById(id);
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.ESTIMATE_DELETE,
                "Estimate deleted: total " + total);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EstimateResponseDTO> listByJobCard(Long jobCardId, Pageable pageable) {
        if (!jobCardRepository.existsById(jobCardId)) {
            throw new ResourceNotFoundException("JobCard not found: " + jobCardId);
        }
        return repository.findByJobCardIdOrderByCreatedAtDescIdDesc(jobCardId, pageable)
                         .map(this::toResponse);
    }

    /**
     * Convert this estimate into an invoice.
     *
     * <p>Delegated to {@code InvoiceService}, which owns invoice creation and
     * therefore all the money calculation. This service keeps only the estimate
     * side of the transition, so the frozen-estimate rule and the
     * status-setting rule stay in the same class that already owned them.
     *
     * <p>No {@code @Transactional(readOnly = true)} here: the caller's
     * transaction must be the one that commits the invoice and the estimate's
     * new status together, or neither.
     */
    @Override
    public com.autoservicehub.dto.InvoiceResponseDTO convertToInvoice(Long id) {
        return invoiceService.convertFromEstimate(id);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void mapToEntity(EstimateRequestDTO r, Estimate e) {
        e.setJobCard(jobCardRepository.findById(r.getJobCardId())
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + r.getJobCardId())));
        // The discount is the one client-supplied money value: it is a decision,
        // not a computation, so it is carried onto the entity here and validated
        // against the calculated subtotal in recalculate().
        e.setDiscount(r.getDiscount());
        if (r.getStatus() != null) {
            e.setStatus(r.getStatus());
        }
    }

    /**
     * Rejects the whole request if any line is unusable, before any write is
     * attempted. The same calculation is applied again when the line is stored,
     * so this is a fail-fast guard rather than a second source of truth.
     */
    private void validateItems(List<EstimateItemRequestDTO> items) {
        for (EstimateItemRequestDTO item : items) {
            calculator.lineAmount(item.getQuantity(), item.getUnitPrice());
        }
    }

    /**
     * Replaces the estimate's lines with the submitted ones. The client cannot
     * choose a line's parent, and a line's amount is always recalculated, so a
     * tampered payload cannot change what the estimate is worth.
     */
    private void replaceItems(List<EstimateItemRequestDTO> requested, Estimate estimate) {
        itemRepository.deleteAll(itemRepository.findByEstimateIdOrderByIdAsc(estimate.getId()));

        for (EstimateItemRequestDTO itemRequest : requested) {
            EstimateItem item = new EstimateItem();
            item.setEstimate(estimate);
            item.setDescription(itemRequest.getDescription());
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(itemRequest.getUnitPrice());
            item.setLineAmount(calculator.lineAmount(itemRequest.getQuantity(), itemRequest.getUnitPrice()));
            // Null means "not stated", which reads as PART.
            item.setCategory(itemRequest.getCategory());
            itemRepository.save(item);
        }
    }

    /**
     * Computes subtotal, tax and total from the stored lines and the requested
     * discount, then writes them onto the estimate.
     *
     * <p>This is the only place an estimate's value is decided; the client's
     * subtotal/tax/total are never read.
     */
    private void recalculate(Estimate estimate) {
        BigDecimal lineSum = itemRepository.findByEstimateIdOrderByIdAsc(estimate.getId())
                                            .stream()
                                            .map(EstimateItem::getLineAmount)
                                            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal subtotal = calculator.subtotal(lineSum);
        BigDecimal discount = calculator.discount(estimate.getDiscount(), subtotal);
        BigDecimal taxable  = calculator.taxableAmount(subtotal, discount);
        BigDecimal tax      = calculator.tax(taxable);
        BigDecimal total    = calculator.total(taxable, tax);

        estimate.setSubtotal(subtotal);
        estimate.setDiscount(discount);
        estimate.setTax(tax);
        estimate.setTotal(total);
    }

    /** An estimate that has become an invoice is a financial record, not a draft. */
    private void assertNotConverted(Estimate estimate) {
        if (STATUS_CONVERTED.equalsIgnoreCase(estimate.getStatus())) {
            throw new BusinessRuleException(
                    "Estimate " + estimate.getId()
                    + " has been converted to an invoice and can no longer be modified or deleted.");
        }
    }

    private EstimateResponseDTO toResponse(Estimate e) {
        EstimateResponseDTO dto = new EstimateResponseDTO();
        dto.setId(e.getId());

        if (e.getJobCard() != null) {
            dto.setJobCardId(e.getJobCard().getId());
            dto.setJobCardNumber(e.getJobCard().getJobCardNumber());
        }

        dto.setSubtotal(e.getSubtotal());
        dto.setDiscount(e.getDiscount());
        dto.setTax(e.getTax());
        dto.setTotal(e.getTotal());
        dto.setStatus(e.getStatus());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());

        if (e.getId() != null) {
            dto.setItems(itemRepository.findByEstimateIdOrderByIdAsc(e.getId())
                                       .stream()
                                       .map(this::itemToResponse)
                                       .toList());
        }
        return dto;
    }

    private EstimateItemResponseDTO itemToResponse(EstimateItem item) {
        EstimateItemResponseDTO dto = new EstimateItemResponseDTO();
        dto.setId(item.getId());
        dto.setDescription(item.getDescription());
        dto.setQuantity(item.getQuantity());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setLineAmount(item.getLineAmount());
        // Null on lines written before the column existed means PART, which is
        // what every line meant before this column.
        dto.setCategory(item.getCategory() == null
                ? com.autoservicehub.entity.BillingItemCategory.PART
                : item.getCategory());
        return dto;
    }
}

