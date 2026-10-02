package com.autoservicehub.service;

import com.autoservicehub.dto.PurchaseRequestDTO;
import com.autoservicehub.dto.PurchaseResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Purchasing (FR-INV-2) — purchase orders raised against a supplier, and the
 * goods receipt that turns an order into stock.
 *
 * <p>A purchase is created in PENDING state and does not affect stock. Stock
 * only moves when the order is received, and it moves through
 * {@link StockMovementService} as IN movements, so the inventory ledger stays
 * the single record of every change to a part's stock on hand.
 */
public interface PurchaseService {

    /**
     * Records a purchase order against a supplier with one or more items.
     *
     * <p>Every line's amount, and therefore the order total, is calculated
     * server-side; any total supplied by the client is ignored.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown supplier or part
     * @throws com.autoservicehub.exception.BusinessRuleException  a line has a
     *         non-positive quantity or a negative price, or no items were supplied
     */
    PurchaseResponseDTO create(PurchaseRequestDTO request);

    PurchaseResponseDTO getById(Long id);

    Page<PurchaseResponseDTO> list(Pageable pageable);

    /** Purchase history for one supplier, newest first. */
    Page<PurchaseResponseDTO> listBySupplier(Long supplierId, Pageable pageable);

    /**
     * Receives a purchase: every line's quantity is added to its part's stock
     * and an IN stock movement is recorded against it.
     *
     * <p>Runs as one transaction. If any line fails, the whole receipt is rolled
     * back — no part's stock moves, no movement is recorded and the purchase
     * stays PENDING, so a partially received order can never be mistaken for a
     * complete one.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown purchase
     * @throws com.autoservicehub.exception.BusinessRuleException  the purchase has
     *         already been received, or one of its lines cannot be applied
     */
    PurchaseResponseDTO receive(Long id);
}