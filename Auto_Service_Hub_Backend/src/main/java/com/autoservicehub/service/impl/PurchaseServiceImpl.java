package com.autoservicehub.service.impl;

import com.autoservicehub.dto.PurchaseItemRequestDTO;
import com.autoservicehub.dto.PurchaseItemResponseDTO;
import com.autoservicehub.dto.PurchaseRequestDTO;
import com.autoservicehub.dto.PurchaseResponseDTO;
import com.autoservicehub.dto.StockMovementRequestDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.Part;
import com.autoservicehub.entity.Purchase;
import com.autoservicehub.entity.PurchaseItem;
import com.autoservicehub.entity.Supplier;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.PurchaseItemRepository;
import com.autoservicehub.repository.PurchaseRepository;
import com.autoservicehub.repository.SupplierRepository;
import com.autoservicehub.service.AuditService;
import com.autoservicehub.service.PurchaseService;
import com.autoservicehub.service.StockMovementService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Purchasing (FR-INV-2).
 *
 * <p>Two ideas hold this together.
 *
 * <p>The first is that the money is ours, not the client's. Every line amount
 * and the order total are computed here from quantity and unit price; nothing
 * the caller sends about value is read. A tampered payload therefore cannot
 * change what a purchase is recorded as worth, and the stored figure is always
 * re-derivable from the stored lines.
 *
 * <p>The second is that purchasing does not touch stock. A purchase is only an
 * intention to buy; the goods arrive later. Receiving is the only thing that
 * moves stock, and it does so exclusively through {@link StockMovementService},
 * so the inventory ledger remains the one place a part's stock on hand changes
 * and no stock arithmetic is duplicated here.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PurchaseServiceImpl implements PurchaseService {

    /** Order raised but goods not yet received. */
    public static final String STATUS_PENDING  = "PENDING";
    /** Goods received; stock has been increased. */
    public static final String STATUS_RECEIVED = "RECEIVED";

    /** Reason stamped on the IN movements raised by a goods receipt. */
    public static final String REASON_PURCHASE_RECEIVED = "PURCHASE_RECEIVED";

    private final PurchaseRepository     repository;
    private final PurchaseItemRepository itemRepository;
    private final SupplierRepository     supplierRepository;
    private final PartRepository         partRepository;
    private final StockMovementService   stockMovementService;
    private final AuditService          auditService;

    /** Entity name recorded on this module's audit entries. */
    private static final String AUDIT_ENTITY = "PURCHASE";

    @Override
    public PurchaseResponseDTO create(PurchaseRequestDTO request) {
        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Supplier not found: " + request.getSupplierId()));

        // Everything is validated and every part resolved before the first
        // write, so a payload with one bad line never leaves a half-written
        // order behind.
        validateItems(request.getItems());
        Map<Part, PurchaseItemRequestDTO> lines = resolveParts(request.getItems());

        Purchase entity = new Purchase();
        entity.setSupplier(supplier);
        entity.setPurchaseDate(request.getPurchaseDate() != null
                ? request.getPurchaseDate()
                : LocalDate.now());
        entity.setStatus(STATUS_PENDING);

        Purchase saved = repository.save(entity);
        replaceItems(lines, saved);
        recalculateTotal(saved);

        PurchaseResponseDTO response = toResponse(saved);
        auditService.recordSuccess(AUDIT_ENTITY, saved.getId(), AuditAction.PURCHASE_CREATE,
                "Purchase raised on supplier " + request.getSupplierId()
                        + ": lines " + lines.size()
                        + ", total " + saved.getTotalAmount());
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseResponseDTO getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PurchaseResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PurchaseResponseDTO> listBySupplier(Long supplierId, Pageable pageable) {
        if (!supplierRepository.existsById(supplierId)) {
            throw new ResourceNotFoundException("Supplier not found: " + supplierId);
        }
        return repository.findBySupplierIdOrderByIdDesc(supplierId, pageable).map(this::toResponse);
    }

    /**
     * Goods receipt: turns each ordered line into stock.
     *
     * <p>The status is checked first, so a purchase that has already been
     * received is refused before any stock moves — otherwise a repeated call
     * would add the same quantities a second time and quietly double the stock
     * on hand.
     *
     * <p>Each line is handed to {@link StockMovementService}, which does the
     * stock arithmetic and writes the ledger row. Those calls join this method's
     * transaction, so a failure part-way through — an unknown part, a stock
     * conflict — rolls back the lines already applied and leaves the purchase
     * PENDING with stock exactly as it was.
     */
    @Override
    public PurchaseResponseDTO receive(Long id) {
        Purchase purchase = findOrThrow(id);

        if (STATUS_RECEIVED.equalsIgnoreCase(purchase.getStatus())) {
            throw new BusinessRuleException(
                    "Purchase " + id + " has already been received and cannot be received again.");
        }

        List<PurchaseItem> items = itemRepository.findByPurchaseIdOrderByIdAsc(id);
        if (items.isEmpty()) {
            throw new BusinessRuleException("Purchase " + id + " has no items to receive.");
        }

        String reference = purchaseReference(id);
        for (PurchaseItem item : items) {
            StockMovementRequestDTO movement = new StockMovementRequestDTO();
            movement.setPartId(item.getPart().getId());
            movement.setMovementType(StockMovementServiceImpl.TYPE_IN);
            movement.setQuantity(item.getQuantity());
            movement.setReason(REASON_PURCHASE_RECEIVED);
            movement.setReference(reference);

            stockMovementService.create(movement);
        }

        // Only marked RECEIVED once every line has been applied; a rollback
        // before this point leaves the order in PENDING.
        purchase.setStatus(STATUS_RECEIVED);
        PurchaseResponseDTO response = toResponse(repository.save(purchase));
        // Receiving is the operation that moves real stock, so it is audited as
        // one entry naming every line rather than one entry per movement — the
        // movements themselves are already individually audited downstream.
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.PURCHASE_RECEIVE,
                "Purchase received: " + items.size() + " line(s) applied to stock");
        return response;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Purchase findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + id));
    }

    /**
     * Rejects a request whose lines cannot be purchased.
     *
     * <p>Bean validation already covers a null quantity, a zero/negative quantity
     * and a negative price at the HTTP boundary; this repeats the rules so the
     * service is safe when called directly (other services, jobs) and so a null
     * items list fails with a clear message rather than a NullPointerException.
     */
    private void validateItems(List<PurchaseItemRequestDTO> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessRuleException("A purchase must contain at least one item.");
        }

        int line = 0;
        for (PurchaseItemRequestDTO item : items) {
            line++;
            if (item == null) {
                throw new BusinessRuleException("Purchase item " + line + " is missing.");
            }
            if (item.getPartId() == null) {
                throw new BusinessRuleException("Purchase item " + line + " must reference a part.");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new BusinessRuleException("Purchase item " + line
                        + " must have a quantity greater than 0, got: " + item.getQuantity());
            }
            if (item.getUnitPrice() == null) {
                throw new BusinessRuleException("Purchase item " + line + " must have a unit price.");
            }
            if (item.getUnitPrice().signum() < 0) {
                throw new BusinessRuleException("Purchase item " + line
                        + " must not have a negative unit price, got: " + item.getUnitPrice());
            }
        }
    }

    /**
     * Resolves each line's part, failing before any write if one does not exist, so
     * an unknown part cannot leave the earlier lines already persisted.
     */
    private Map<Part, PurchaseItemRequestDTO> resolveParts(List<PurchaseItemRequestDTO> items) {
        Map<Part, PurchaseItemRequestDTO> lines = new LinkedHashMap<>();
        for (PurchaseItemRequestDTO item : items) {
            Part part = partRepository.findById(item.getPartId())
                    .orElseThrow(() -> new ResourceNotFoundException("Part not found: " + item.getPartId()));
            lines.put(part, item);
        }
        return lines;
    }

    /**
     * Writes the order's lines. The parent is set here, never taken from the
     * request, and each line's amount is calculated rather than accepted.
     */
    private void replaceItems(Map<Part, PurchaseItemRequestDTO> requested, Purchase purchase) {
        for (Map.Entry<Part, PurchaseItemRequestDTO> entry : requested.entrySet()) {
            PurchaseItemRequestDTO line = entry.getValue();

            PurchaseItem item = new PurchaseItem();
            item.setPurchase(purchase);
            item.setPart(entry.getKey());
            item.setQuantity(line.getQuantity());
            item.setUnitPrice(line.getUnitPrice());
            item.setLineAmount(lineAmount(line.getQuantity(), line.getUnitPrice()));
            itemRepository.save(item);
        }
    }

    /**
     * The purchase total: the sum of the stored line amounts.
     *
     * <p>The only place a purchase's value is decided. Recalculated from what was
     * written rather than carried over from the request.
     */
    private void recalculateTotal(Purchase purchase) {
        BigDecimal total = itemRepository.findByPurchaseIdOrderByIdAsc(purchase.getId())
                                           .stream()
                                           .map(PurchaseItem::getLineAmount)
                                           .reduce(BigDecimal.ZERO, BigDecimal::add);
        purchase.setTotalAmount(total);
    }

    private BigDecimal lineAmount(Integer quantity, BigDecimal unitPrice) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    /** Free-text pointer recorded on each IN movement raised by a receipt. */
    private String purchaseReference(Long purchaseId) {
        return "PURCHASE-" + purchaseId;
    }

    private PurchaseResponseDTO toResponse(Purchase e) {
        PurchaseResponseDTO dto = new PurchaseResponseDTO();
        dto.setId(e.getId());
        dto.setSupplierId(e.getSupplier().getId());
        dto.setSupplierName(e.getSupplier().getName());
        dto.setPurchaseDate(e.getPurchaseDate());
        dto.setTotalAmount(e.getTotalAmount());
        dto.setStatus(e.getStatus());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setItems(itemRepository.findByPurchaseIdOrderByIdAsc(e.getId())
                                  .stream()
                                  .map(this::itemToResponse)
                                  .toList());
        return dto;
    }

    private PurchaseItemResponseDTO itemToResponse(PurchaseItem item) {
        PurchaseItemResponseDTO dto = new PurchaseItemResponseDTO();
        dto.setId(item.getId());
        dto.setPartId(item.getPart().getId());
        dto.setPartSku(item.getPart().getSku());
        dto.setPartName(item.getPart().getName());
        dto.setQuantity(item.getQuantity());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setLineAmount(item.getLineAmount());
        return dto;
    }
}
