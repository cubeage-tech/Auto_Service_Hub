package com.autoservicehub.service;

import com.autoservicehub.dto.EstimateRequestDTO;
import com.autoservicehub.dto.EstimateResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Billing - Estimates (SRS 4.9)
 */
public interface EstimateService {

    EstimateResponseDTO create(EstimateRequestDTO request);

    EstimateResponseDTO update(Long id, EstimateRequestDTO request);

    EstimateResponseDTO getById(Long id);

    Page<EstimateResponseDTO> list(Pageable pageable);

    void delete(Long id);

    /**
     * Estimates raised against one job card, newest first.
     */
    Page<EstimateResponseDTO> listByJobCard(Long jobCardId, Pageable pageable);

    /**
     * Converts this estimate into an invoice.
     *
     * <p>A thin delegation rather than the implementation: the work is all about
     * creating an invoice, and {@code InvoiceService} owns invoice creation,
     * its line writing, its recalculation and its response shape. Keeping the
     * conversion there is what stops the two documents drifting apart, and this
     * method exists so the estimate API stays a single, coherent resource.
     *
     * <p>On success the estimate is marked CONVERTED and can no longer be
     * edited or converted again. The operation is transactional: if invoice
     * creation fails, the estimate is unchanged and still convertible.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown estimate
     * @throws com.autoservicehub.exception.BusinessRuleException  already converted,
     *         no items, no job card, or a line that cannot be billed
     */
    com.autoservicehub.dto.InvoiceResponseDTO convertToInvoice(Long id);
}
