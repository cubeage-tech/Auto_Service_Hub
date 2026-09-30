package com.autoservicehub.service.impl;

import com.autoservicehub.dto.PaymentRequestDTO;
import com.autoservicehub.dto.PaymentResponseDTO;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.Payment;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.PaymentRepository;
import com.autoservicehub.service.PaymentService;
import com.autoservicehub.util.BillingCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Billing - Payments (SRS 4.9).
 *
 * <p>A payment settles an {@link Invoice}. Recording one is a single
 * transaction that, in order:
 * <ol>
 *   <li>loads the invoice (404 if unknown),</li>
 *   <li>refuses invoices that must not be paid (already settled, cancelled),</li>
 *   <li>recomputes the outstanding amount from the invoice's successful payments,</li>
 *   <li>refuses a payment larger than that outstanding amount,</li>
 *   <li>saves the payment,</li>
 *   <li>recomputes the invoice's status from the new balance.</li>
 * </ol>
 * All of it commits together or not at all.
 *
 * <p>The invoice's own total is never taken from the caller: it was already
 * calculated server-side by {@code InvoiceServiceImpl} from the invoice's line
 * items, and the balance here is derived from that stored total plus the
 * payments actually recorded.
 *
 * <p>Payments are recorded by staff — there is no gateway integration or
 * callback verification here, by design.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentServiceImpl implements PaymentService {

    /** Payment status that reduces the outstanding balance. */
    public static final String PAYMENT_STATUS_SETTLED = "SUCCESS";

    /** Invoice status: nothing paid yet. */
    public static final String INVOICE_STATUS_PENDING        = "PENDING";
    /** Invoice status: some, but not all, of the total has been paid. */
    public static final String INVOICE_STATUS_PARTIALLY_PAID = "PARTIALLY_PAID";
    /** Invoice status: the total has been paid in full. */
    public static final String INVOICE_STATUS_PAID           = "PAID";
    /** Invoice status: void — must never accept a payment. */
    public static final String INVOICE_STATUS_CANCELLED      = "CANCELLED";

    private final PaymentRepository repository;
    private final InvoiceRepository invoiceRepository;

    @Override
    public PaymentResponseDTO create(PaymentRequestDTO request) {
        Invoice invoice = findPayableInvoice(request.getInvoiceId());
        BigDecimal amount = requirePositiveAmount(request.getAmount());
        String status = resolvePaymentStatus(request.getStatus());

        // Only a payment that actually settles reduces the balance, so only
        // that kind is bounded by what is outstanding.
        if (isSettled(status)) {
            BigDecimal outstanding = outstandingAmount(invoice);
            if (amount.compareTo(outstanding) > 0) {
                throw new BusinessRuleException(
                        "Payment of " + amount.toPlainString() + " exceeds the outstanding amount of "
                        + outstanding.toPlainString() + " on invoice " + invoice.getId() + ".");
            }
        }

        Payment entity = new Payment();
        entity.setInvoice(invoice);
        entity.setAmount(amount);
        entity.setMode(request.getMode());
        entity.setTransactionRef(request.getTransactionRef());
        entity.setStatus(status);
        entity.setPaidAt(request.getPaidAt() != null ? request.getPaidAt() : LocalDateTime.now());

        Payment saved = repository.save(entity);
        refreshInvoiceStatus(invoice);

        return toResponse(saved);
    }

    @Override
    public PaymentResponseDTO update(Long id, PaymentRequestDTO request) {
        Payment existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
        Invoice invoice = findPayableInvoice(request.getInvoiceId());

        BigDecimal amount = requirePositiveAmount(request.getAmount());
        String status = resolvePaymentStatus(request.getStatus());
        if (isSettled(status)) {
            BigDecimal outstanding = outstandingAmount(invoice);
            if (amount.compareTo(outstanding) > 0) {
                throw new BusinessRuleException(
                        "Payment of " + amount.toPlainString() + " exceeds the outstanding amount of "
                        + outstanding.toPlainString() + " on invoice " + invoice.getId() + ".");
            }
        }

        Invoice previousInvoice = existing.getInvoice();

        existing.setInvoice(invoice);
        existing.setAmount(amount);
        existing.setMode(request.getMode());
        existing.setTransactionRef(request.getTransactionRef());
        existing.setStatus(status);
        existing.setPaidAt(request.getPaidAt() != null ? request.getPaidAt() : existing.getPaidAt());

        Payment saved = repository.save(existing);
        // Both invoices can be affected if the payment was moved to another.
        if (previousInvoice != null && !previousInvoice.getId().equals(invoice.getId())) {
            refreshInvoiceStatus(previousInvoice);
        }
        refreshInvoiceStatus(invoice);

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponseDTO getById(Long id) {
        Payment found = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
        return toResponse(found);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PaymentResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PaymentResponseDTO> listByInvoice(Long invoiceId, Pageable pageable) {
        if (!invoiceRepository.existsById(invoiceId)) {
            throw new ResourceNotFoundException("Invoice not found: " + invoiceId);
        }
        // Oldest first, so the order the customer paid in is preserved.
        return repository.findByInvoiceIdOrderByIdAsc(invoiceId, pageable)
                         .map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        Payment existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
        Invoice invoice = existing.getInvoice();
        repository.deleteById(id);
        // Removing money received must put the invoice's status back in step
        // with the reduced balance, so a fully-paid invoice stops reading PAID.
        if (invoice != null) {
            refreshInvoiceStatus(invoice);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Loads the invoice and refuses one that must not be paid: an unknown
     * invoice is a 404, a void or already-settled one is a 409.
     */
    private Invoice findPayableInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));

        if (INVOICE_STATUS_CANCELLED.equalsIgnoreCase(invoice.getStatus())) {
            throw new BusinessRuleException(
                    "Invoice " + invoiceId + " is CANCELLED and cannot accept a payment.");
        }
        if (INVOICE_STATUS_PAID.equalsIgnoreCase(invoice.getStatus())) {
            throw new BusinessRuleException(
                    "Invoice " + invoiceId + " is already PAID and cannot accept a further payment.");
        }
        return invoice;
    }

    private BigDecimal requirePositiveAmount(BigDecimal requested) {
        if (requested == null || requested.signum() <= 0) {
            throw new BusinessRuleException("amount must be greater than 0, got: " + requested);
        }
        return BillingCalculator.money(requested);
    }

    /** Payments default to SUCCESS: recording one means the money was received. */
    private String resolvePaymentStatus(String requested) {
        return requested == null || requested.isBlank()
                ? PAYMENT_STATUS_SETTLED
                : requested.trim().toUpperCase();
    }

    private boolean isSettled(String paymentStatus) {
        return PAYMENT_STATUS_SETTLED.equalsIgnoreCase(paymentStatus);
    }

    /**
     * outstanding = invoice total − successful payments, floored at zero.
     *
     * <p>The total is the stored, server-calculated one; the sum is read from
     * the payments table. Neither can be influenced by the caller.
     */
    private BigDecimal outstandingAmount(Invoice invoice) {
        BigDecimal total = invoice.getTotal() == null ? BigDecimal.ZERO : invoice.getTotal();
        BigDecimal outstanding = total.subtract(settledSum(invoice));
        return outstanding.signum() < 0
                ? BillingCalculator.money(BigDecimal.ZERO)
                : BillingCalculator.money(outstanding);
    }

    private BigDecimal settledSum(Invoice invoice) {
        BigDecimal paid = repository.sumAmountByInvoiceIdAndStatus(
                invoice.getId(), PAYMENT_STATUS_SETTLED);
        return paid == null ? BigDecimal.ZERO : paid;
    }

    /**
     * Puts the invoice's status in step with what has actually been paid.
     *
     * <p>A cancelled invoice is left alone — voiding it must not be undone by
     * a later payment. A fully covered balance becomes PAID, a partial one
     * PARTIALLY_PAID, and an unpaid one returns to PENDING.
     */
    private void refreshInvoiceStatus(Invoice invoice) {
        if (invoice == null || INVOICE_STATUS_CANCELLED.equalsIgnoreCase(invoice.getStatus())) {
            return;
        }

        BigDecimal total = invoice.getTotal() == null ? BigDecimal.ZERO : invoice.getTotal();
        BigDecimal paid  = settledSum(invoice);

        String status;
        if (paid.compareTo(total) >= 0) {
            status = INVOICE_STATUS_PAID;          // fully covered (a zero-total invoice too)
        } else if (paid.signum() > 0) {
            status = INVOICE_STATUS_PARTIALLY_PAID;
        } else {
            status = INVOICE_STATUS_PENDING;
        }

        if (!status.equals(invoice.getStatus())) {
            invoice.setStatus(status);
            invoiceRepository.save(invoice);
        }
    }

    private PaymentResponseDTO toResponse(Payment entity) {
        PaymentResponseDTO dto = new PaymentResponseDTO();
        dto.setId(entity.getId());
        if (entity.getInvoice() != null) {
            dto.setInvoiceId(entity.getInvoice().getId());
            // Surfaced so a client sees the effect of the payment without a
            // second call. Both are derived here, never supplied by the caller.
            dto.setInvoiceStatus(entity.getInvoice().getStatus());
            dto.setInvoiceOutstandingAmount(outstandingAmount(entity.getInvoice()));
        }
        dto.setAmount(entity.getAmount());
        dto.setMode(entity.getMode());
        dto.setTransactionRef(entity.getTransactionRef());
        dto.setStatus(entity.getStatus());
        dto.setPaidAt(entity.getPaidAt());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
