package com.autoservicehub.service;

import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Billing - Invoices (SRS 4.9)
 */
public interface InvoiceService {

    InvoiceResponseDTO create(InvoiceRequestDTO request);

    InvoiceResponseDTO update(Long id, InvoiceRequestDTO request);

    InvoiceResponseDTO getById(Long id);

    Page<InvoiceResponseDTO> list(Pageable pageable);

    void delete(Long id);

    /**
     * Invoices raised against one job card, newest first.
     */
    Page<InvoiceResponseDTO> listByJobCard(Long jobCardId, Pageable pageable);

    /**
     * Amount still owed on an invoice: its total minus its successful payments.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown invoice
     */
    java.math.BigDecimal getOutstandingAmount(Long id);

    /**
     * Bills one repair task's labour onto an invoice (FR-BILL-2).
     *
     * <p>The line is generated entirely from the task: its description, a
     * quantity of 1, and its unit price from {@code JobTask.labourCost}. The
     * invoice's subtotal, GST and total are then recalculated server-side.
     *
     * <p>Three things are refused rather than quietly allowed, because each one
     * would produce a wrong invoice:
     *
     * <ul>
     *   <li>a task belonging to a different job card — it would bill one
     *       customer's job onto another's;</li>
     *   <li>a task already billed on this invoice — the customer would be
     *       charged twice for one piece of work;</li>
     *   <li>an invoice that is already PAID — its value is a financial record
     *       and must not move.</li>
     * </ul>
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown invoice or task
     * @throws com.autoservicehub.exception.BusinessRuleException  the invoice is PAID,
     *         the task belongs to another job card, or it is already billed here
     */
    InvoiceResponseDTO addLabourItem(Long invoiceId,
                                    com.autoservicehub.dto.InvoiceLabourItemRequestDTO request);

    /**
     * Converts an accepted estimate into an invoice (FR-BILL-1 → FR-BILL-2).
     *
     * <p>Lives here rather than on the estimate service because it is the
     * invoice that is being created: this service owns invoice creation, its
     * line writing, its recalculation and its response shape, and reusing those
     * is what keeps the two documents from drifting apart.
     *
     * <p>Everything monetary is recomputed from the estimate's lines by
     * {@link com.autoservicehub.util.BillingCalculator}. The estimate's stored
     * {@code subtotal}/{@code tax}/{@code total} are never copied across: they
     * are a cached summary of a quote, and the invoice is a separate document
     * that may be reviewed, discounted further or adjusted after conversion.
     *
     * <p>The whole operation is one transaction. If any line fails to convert,
     * the invoice is never created and the estimate is left untouched and still
     * convertible.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown estimate
     * @throws com.autoservicehub.exception.BusinessRuleException  the estimate is
     *         already converted, has no items, is missing a job card, or one of
     *         its lines cannot be billed
     */
    InvoiceResponseDTO convertFromEstimate(Long estimateId);
}
