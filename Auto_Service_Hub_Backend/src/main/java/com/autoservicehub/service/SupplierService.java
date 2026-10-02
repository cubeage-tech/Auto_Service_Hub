package com.autoservicehub.service;

import com.autoservicehub.dto.PurchaseResponseDTO;
import com.autoservicehub.dto.SupplierRequestDTO;
import com.autoservicehub.dto.SupplierResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Supplier Management (FR-INV-2) — the counterpart of {@link PartService} in
 * the inventory module.
 *
 * <p>A supplier is the party a purchase order is placed with. Its contact
 * details appear on the purchase, so the rules here are about identity rather
 * than stock: a supplier must be uniquely named, and one that has purchase
 * history cannot be deleted.
 */
public interface SupplierService {

    /**
     * @throws com.autoservicehub.exception.BusinessRuleException another supplier
     *         already uses this name (case- and whitespace-insensitive)
     */
    SupplierResponseDTO create(SupplierRequestDTO request);

    SupplierResponseDTO getById(Long id);

    Page<SupplierResponseDTO> list(Pageable pageable);

    /**
     * Searches suppliers by free text across name, phone, email and address.
     *
     * <p>A blank term is treated as "match everything" rather than as an error,
     * so the same endpoint backs both the plain list and the filtered view, and
     * clearing a search box falls back to the full list rather than an error.
     */
    Page<SupplierResponseDTO> search(String term, Pageable pageable);

    /**
     * Purchase orders placed with one supplier, newest first (FR-INV-2).
     *
     * <p>The supplier's purchase history, reachable from the supplier itself
     * rather than only by hunting through {@code GET /api/v1/purchases}.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown supplier
     */
    Page<PurchaseResponseDTO> purchaseHistory(Long supplierId, Pageable pageable);

    /**
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown supplier
     * @throws com.autoservicehub.exception.BusinessRuleException  the new name belongs
     *         to a different supplier
     */
    SupplierResponseDTO update(Long id, SupplierRequestDTO request);

    /**
     * Removes a supplier that has no purchase history.
     *
     * <p>Deleting one that has been ordered from would orphan every purchase
     * that names it, so that is refused rather than allowed and cleaned up.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown supplier
     * @throws com.autoservicehub.exception.BusinessRuleException  supplier has purchases
     */
    void delete(Long id);
}