package com.autoservicehub.service.impl;

import com.autoservicehub.dto.StockMovementRequestDTO;
import com.autoservicehub.dto.StockMovementResponseDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Part;
import com.autoservicehub.entity.StockMovement;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.StockMovementRepository;
import com.autoservicehub.service.AuditService;
import com.autoservicehub.service.StockMovementService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory stock movements (SRS 4.7).
 *
 * <p>Single place where a part's stock on hand changes. Each operation reads the
 * part, computes the new balance, refuses anything that would drive stock below
 * zero, writes the movement row and saves the part — all in one transaction, so
 * the ledger and the cached balance can never disagree.
 *
 * <p>{@code quantity} on a movement is always a positive magnitude. Direction
 * comes from the type: IN adds, OUT subtracts, and ADJUSTMENT applies the
 * signed {@code adjustmentDelta}. Negative stock is rejected with a
 * {@link BusinessRuleException} (HTTP 409) rather than silently clamped, so the
 * caller learns the request was not applied.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class StockMovementServiceImpl implements StockMovementService {

    /** Goods received — increases stock. */
    public static final String TYPE_IN         = "IN";
    /** Stock issued — decreases stock. */
    public static final String TYPE_OUT        = "OUT";
    /** Stock take — applies a signed delta. */
    public static final String TYPE_ADJUSTMENT = "ADJUSTMENT";

    /** Default reason stamped on a movement raised by job-card consumption. */
    public static final String REASON_PART_CONSUMED = "PART_CONSUMED";

    /** Entity name recorded on this module's audit entries. */
    private static final String AUDIT_ENTITY = "STOCK_MOVEMENT";

    private final StockMovementRepository repository;
    private final PartRepository           partRepository;
    private final JobCardRepository        jobCardRepository;
    private final AuditService             auditService;

    @Override
    public StockMovementResponseDTO create(StockMovementRequestDTO request) {
        String type = resolveType(request.getMovementType());
        int quantity = resolveQuantity(request.getQuantity());

        Part part = partRepository.findById(request.getPartId())
                .orElseThrow(() -> new ResourceNotFoundException("Part not found: " + request.getPartId()));

        JobCard jobCard = request.getJobCardId() == null
                ? null
                : jobCardRepository.findById(request.getJobCardId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "JobCard not found: " + request.getJobCardId()));

        int stockBefore = stockOf(part);
        int stockAfter  = applyToStock(type, stockBefore, quantity, request.getAdjustmentDelta());

        StockMovement movement = new StockMovement();
        movement.setPart(part);
        movement.setJobCard(jobCard);
        movement.setMovementType(type);
        movement.setQuantity(quantity);
        movement.setAdjustmentDelta(TYPE_ADJUSTMENT.equals(type) ? request.getAdjustmentDelta() : null);
        movement.setStockBefore(stockBefore);
        movement.setStockAfter(stockAfter);
        movement.setReason(request.getReason());
        movement.setReference(request.getReference() != null
                ? request.getReference()
                : (jobCard != null ? jobCard.getJobCardNumber() : null));

        part.setStockQty(stockAfter);
        partRepository.save(part);

        StockMovementResponseDTO response = toResponse(repository.save(movement), part, jobCard);
        // One audited event per movement, labelled with the movement type, so
        // "who added stock, who removed it and who corrected it" is a single
        // query rather than three inferences from the ledger.
        auditService.recordSuccess(AUDIT_ENTITY, response.getId(), auditActionFor(type),
                "Movement " + type + " qty " + quantity
                        + " on part " + part.getSku()
                        + "; stock " + stockBefore + " -> " + stockAfter
                        + (jobCard == null ? "" : "; jobCard=" + jobCard.getJobCardNumber()));
        return response;
    }

    /**
     * The audit action matching a movement type.
     *
     * <p>IN is stock arriving, OUT is stock leaving, and ADJUSTMENT is a correction
     * of the balance rather than a physical movement.
     */
    private AuditAction auditActionFor(String type) {
        return switch (type) {
            case TYPE_IN         -> AuditAction.STOCK_IN;
            case TYPE_OUT        -> AuditAction.STOCK_OUT;
            case TYPE_ADJUSTMENT -> AuditAction.STOCK_ADJUSTMENT;
            default              -> AuditAction.STOCK_ADJUSTMENT;
        };
    }

    /**
     * Consumes spare parts against a job card during repair: always an OUT
     * movement, which keeps the "Spare Parts" step of the workflow and the
     * inventory ledger in step.
     */
    @Override
    public StockMovementResponseDTO consumeForJobCard(Long jobCardId, Long partId,
                                                      Integer quantity, String reason) {
        JobCard jobCard = jobCardRepository.findById(jobCardId)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + jobCardId));

        Part part = partRepository.findById(partId)
                .orElseThrow(() -> new ResourceNotFoundException("Part not found: " + partId));

        int qty = resolveQuantity(quantity);
        int stockBefore = stockOf(part);
        int stockAfter = reduceStock(stockBefore, qty, part.getSku());

        StockMovement movement = new StockMovement();
        movement.setPart(part);
        movement.setJobCard(jobCard);
        movement.setMovementType(TYPE_OUT);
        movement.setQuantity(qty);
        movement.setStockBefore(stockBefore);
        movement.setStockAfter(stockAfter);
        movement.setReason(reason != null ? reason : REASON_PART_CONSUMED);
        movement.setReference(jobCard.getJobCardNumber());

        part.setStockQty(stockAfter);
        partRepository.save(part);

        StockMovementResponseDTO response = toResponse(repository.save(movement), part, jobCard);
        auditService.recordSuccess(AUDIT_ENTITY, response.getId(), AuditAction.STOCK_OUT,
                "Parts consumed on job card " + jobCard.getJobCardNumber()
                        + ": part " + part.getSku() + " qty " + qty
                        + "; stock " + stockBefore + " -> " + stockAfter);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public StockMovementResponseDTO getById(Long id) {
        StockMovement movement = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("StockMovement not found: " + id));
        return toResponse(movement);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockMovementResponseDTO> listByPart(Long partId, Pageable pageable) {
        if (!partRepository.existsById(partId)) {
            throw new ResourceNotFoundException("Part not found: " + partId);
        }
        return repository.findByPartIdOrderByCreatedAtDescIdDesc(partId, pageable)
                         .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockMovementResponseDTO> listByJobCard(Long jobCardId, Pageable pageable) {
        if (!jobCardRepository.existsById(jobCardId)) {
            throw new ResourceNotFoundException("JobCard not found: " + jobCardId);
        }
        return repository.findByJobCardIdOrderByIdAsc(jobCardId, pageable)
                         .map(this::toResponse);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private String resolveType(String requested) {
        if (requested == null || requested.isBlank()) {
            throw new BusinessRuleException("movementType is required.");
        }
        String normalized = requested.trim().toUpperCase();
        if (!TYPE_IN.equals(normalized)
                && !TYPE_OUT.equals(normalized)
                && !TYPE_ADJUSTMENT.equals(normalized)) {
            throw new BusinessRuleException(
                    "Unsupported movementType: '" + requested + "'. Allowed values are "
                    + TYPE_IN + ", " + TYPE_OUT + ", " + TYPE_ADJUSTMENT + ".");
        }
        return normalized;
    }

    private int resolveQuantity(Integer requested) {
        if (requested == null) {
            throw new BusinessRuleException("quantity is required.");
        }
        if (requested <= 0) {
            throw new BusinessRuleException("quantity must be greater than 0, got: " + requested);
        }
        return requested;
    }

    /** Computes the resulting stock, refusing any change that would go below zero. */
    private int applyToStock(String type, int stockBefore, int quantity, Integer adjustmentDelta) {
        return switch (type) {
            case TYPE_IN  -> stockBefore + quantity;
            case TYPE_OUT -> reduceStock(stockBefore, quantity, null);
            case TYPE_ADJUSTMENT -> applyAdjustment(stockBefore, quantity, adjustmentDelta);
            default -> throw new BusinessRuleException("Unsupported movementType: " + type);
        };
    }

    private int reduceStock(int stockBefore, int quantity, String sku) {
        int after = stockBefore - quantity;
        if (after < 0) {
            String subject = sku != null ? " for part " + sku : "";
            throw new BusinessRuleException(
                    "Insufficient stock" + subject + ": requested " + quantity
                    + " but only " + stockBefore + " on hand.");
        }
        return after;
    }

    /**
     * An ADJUSTMENT needs a non-zero signed delta. The delta is what gets
     * applied; {@code quantity} must equal its magnitude, which keeps the two
     * fields consistent for anyone reading the ledger later.
     */
    private int applyAdjustment(int stockBefore, int quantity, Integer adjustmentDelta) {
        if (adjustmentDelta == null) {
            throw new BusinessRuleException(
                    "adjustmentDelta is required for an ADJUSTMENT movement.");
        }
        if (adjustmentDelta == 0) {
            throw new BusinessRuleException("adjustmentDelta must not be zero.");
        }
        if (Math.abs(adjustmentDelta) != quantity) {
            throw new BusinessRuleException(
                    "quantity must equal the magnitude of adjustmentDelta ("
                    + Math.abs(adjustmentDelta) + ").");
        }

        int after = stockBefore + adjustmentDelta;
        if (after < 0) {
            throw new BusinessRuleException(
                    "Adjustment would take stock below zero: " + stockBefore
                    + " " + adjustmentDelta + " = " + after + ".");
        }
        return after;
    }

    /** Null stock is treated as zero, matching how PartServiceImpl seeds a part. */
    private int stockOf(Part part) {
        return part.getStockQty() == null ? 0 : part.getStockQty();
    }

    private StockMovementResponseDTO toResponse(StockMovement m) {
        return toResponse(m, m.getPart(), m.getJobCard());
    }

    private StockMovementResponseDTO toResponse(StockMovement m, Part part, JobCard jobCard) {
        StockMovementResponseDTO dto = new StockMovementResponseDTO();
        dto.setId(m.getId());
        dto.setMovementType(m.getMovementType());
        dto.setQuantity(m.getQuantity());
        dto.setAdjustmentDelta(m.getAdjustmentDelta());
        dto.setStockBefore(m.getStockBefore());
        dto.setStockAfter(m.getStockAfter());
        dto.setReason(m.getReason());
        dto.setReference(m.getReference());
        dto.setCreatedAt(m.getCreatedAt());

        if (part != null) {
            dto.setPartId(part.getId());
            dto.setPartSku(part.getSku());
            dto.setPartName(part.getName());
        }
        if (jobCard != null) {
            dto.setJobCardId(jobCard.getId());
            dto.setJobCardNumber(jobCard.getJobCardNumber());
        }
        return dto;
    }
}
