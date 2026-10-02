package com.autoservicehub.service;

import com.autoservicehub.dto.StockMovementRequestDTO;
import com.autoservicehub.dto.StockMovementResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Inventory stock movements (SRS 4.7) — the ledger behind every change to a
 * part's stock on hand.
 *
 * <p>Movement types:
 * <ul>
 *   <li>{@code IN} — goods received; stock increases.</li>
 *   <li>{@code OUT} — stock issued; stock decreases.</li>
 *   <li>{@code ADJUSTMENT} — stock take; applies a signed delta.</li>
 * </ul>
 *
 * <p>Every method that changes stock does so in one transaction: the part's
 * {@code stockQty} is updated and the movement row is written together, or
 * neither happens.
 */
public interface StockMovementService {

    /**
     * Records a movement and applies it to the part's stock.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown part or job card
     * @throws com.autoservicehub.exception.BusinessRuleException  invalid type/quantity,
     *         or insufficient stock for an OUT/negative ADJUSTMENT
     */
    StockMovementResponseDTO create(StockMovementRequestDTO request);

    /**
     * Consumes spare parts against a job card during repair. Always produces an
     * OUT movement and reduces the part's stock, so the "Spare Parts" step of
     * the workflow and the inventory ledger stay in step.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown job card or part
     * @throws com.autoservicehub.exception.BusinessRuleException  non-positive quantity,
     *         or insufficient stock
     */
    StockMovementResponseDTO consumeForJobCard(Long jobCardId, Long partId,
                                              Integer quantity, String reason);

    StockMovementResponseDTO getById(Long id);

    /** Movement history for one part, newest first. */
    Page<StockMovementResponseDTO> listByPart(Long partId, Pageable pageable);

    /** Parts consumed against one job card, oldest first. */
    Page<StockMovementResponseDTO> listByJobCard(Long jobCardId, Pageable pageable);
}