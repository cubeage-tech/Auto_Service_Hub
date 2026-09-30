package com.autoservicehub.service.impl;

import com.autoservicehub.dto.InvoiceItemRequestDTO;
import com.autoservicehub.dto.InvoiceItemResponseDTO;
import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.InvoiceItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.InvoiceItemRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PaymentRepository;
import com.autoservicehub.service.InvoiceService;
import com.autoservicehub.util.BillingCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Billing - Invoices (SRS 4.9).
 *
 * <p>An invoice bills one {@link JobCard}. As with estimates, {@code subtotal},
 * {@code gst} and {@code total} are computed server-side by
 * {@link BillingCalculator} from the line items; the client's values for those
 * fields are ignored. The outstanding amount is derived from the recorded
 * payments — no gateway integration, payments are simply entered.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class InvoiceServiceImpl implements InvoiceService {

    /**
     * Only payments with this status reduce the outstanding amount.
     *
     * <p>Defined on {@link PaymentServiceImpl}, which owns the payment
     * lifecycle; referenced here rather than redeclared so the two can never
     * drift apart.
     */
    public static final String PAYMENT_STATUS_SETTLED = PaymentServiceImpl.PAYMENT_STATUS_SETTLED;

    private final InvoiceRepository     repository;
    private final InvoiceItemRepository itemRepository;
    private final JobCardRepository     jobCardRepository;
    private final PaymentRepository     paymentRepository;
    private final BillingCalculator     calculator;

    @Override
    public InvoiceResponseDTO create(InvoiceRequestDTO request) {
        // Validate every line before anything is written, so a malformed
        // payload cannot even reach the database.
        validateItems(request.getItems());

        Invoice entity = new Invoice();
        mapToEntity(request, entity);
        entity.setInvoiceDate(request.getInvoiceDate() != null
                ? request.getInvoiceDate()
                : LocalDate.now());
        entity.setStatus(request.getStatus() != null ? request.getStatus() : "PENDING");

        Invoice saved = repository.save(entity);
        // Totals depend on the persisted lines, so they are computed only once
        // the invoice has an id and the lines have been written.
        replaceItems(request.getItems(), saved);
        recalculate(saved);

        return toResponse(saved);
    }

    @Override
    public InvoiceResponseDTO update(Long id, InvoiceRequestDTO request) {
        Invoice existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));

        // A settled invoice is a financial record: its value can no longer be
        // rewritten by editing lines.
        assertNotSettled(existing);
        validateItems(request.getItems());

        mapToEntity(request, existing);
        replaceItems(request.getItems(), existing);
        recalculate(existing);

        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvoiceResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        Invoice existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));
        assertNotSettled(existing);

        // Lines are owned by the invoice, so they are removed with it.
        itemRepository.deleteAll(itemRepository.findByInvoiceIdOrderByIdAsc(id));
        repository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvoiceResponseDTO> listByJobCard(Long jobCardId, Pageable pageable) {
        if (!jobCardRepository.existsById(jobCardId)) {
            throw new ResourceNotFoundException("JobCard not found: " + jobCardId);
        }
        return repository.findByJobCardIdOrderByInvoiceDateDescIdDesc(jobCardId, pageable)
                         .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getOutstandingAmount(Long id) {
        Invoice invoice = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));
        return outstandingAmount(invoice);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void mapToEntity(InvoiceRequestDTO r, Invoice e) {
        e.setJobCard(jobCardRepository.findById(r.getJobCardId())
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + r.getJobCardId())));
        // The discount is the one client-supplied money value: it is a decision,
        // not a computation, so it is carried onto the entity here and validated
        // against the calculated subtotal in recalculate().
        e.setDiscount(r.getDiscount());
        if (r.getStatus() != null) {
            e.setStatus(r.getStatus());
        }
        if (r.getInvoiceDate() != null) {
            e.setInvoiceDate(r.getInvoiceDate());
        }
    }

    /**
     * Rejects the whole request if any line is unusable, before any write is
     * attempted. The same calculation is applied again when the line is stored,
     * so this is a fail-fast guard rather than a second source of truth.
     */
    private void validateItems(List<InvoiceItemRequestDTO> items) {
        for (InvoiceItemRequestDTO item : items) {
            calculator.lineAmount(item.getQuantity(), item.getUnitPrice());
        }
    }

    /**
     * Replaces the invoice's lines with the submitted ones. The client cannot
     * choose a line's parent, and a line's amount is always recalculated, so a
     * tampered payload cannot change what the customer is billed.
     */
    private void replaceItems(List<InvoiceItemRequestDTO> requested, Invoice invoice) {
        itemRepository.deleteAll(itemRepository.findByInvoiceIdOrderByIdAsc(invoice.getId()));

        for (InvoiceItemRequestDTO itemRequest : requested) {
            InvoiceItem item = new InvoiceItem();
            item.setInvoice(invoice);
            item.setDescription(itemRequest.getDescription());
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(itemRequest.getUnitPrice());
            item.setLineAmount(calculator.lineAmount(itemRequest.getQuantity(), itemRequest.getUnitPrice()));
            itemRepository.save(item);
        }
    }

    /**
     * Computes subtotal, GST and total from the stored lines and the requested
     * discount, then writes them onto the invoice.
     *
     * <p>This is the only place an invoice's value is decided; the client's
     * subtotal/GST/total are never read.
     */
    private void recalculate(Invoice invoice) {
        BigDecimal lineSum = itemRepository.findByInvoiceIdOrderByIdAsc(invoice.getId())
                                            .stream()
                                            .map(InvoiceItem::getLineAmount)
                                            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal subtotal = calculator.subtotal(lineSum);
        BigDecimal discount = calculator.discount(invoice.getDiscount(), subtotal);
        BigDecimal taxable  = calculator.taxableAmount(subtotal, discount);
        BigDecimal gst      = calculator.tax(taxable);
        BigDecimal total    = calculator.total(taxable, gst);

        invoice.setSubtotal(subtotal);
        invoice.setDiscount(discount);
        invoice.setGst(gst);
        invoice.setTotal(total);
    }

    /** A settled invoice is a financial record, so its value is frozen. */
    private void assertNotSettled(Invoice invoice) {
        if ("PAID".equalsIgnoreCase(invoice.getStatus())) {
            throw new BusinessRuleException(
                    "Invoice " + invoice.getId() + " is already PAID and can no longer be modified or deleted.");
        }
    }

    /**
     * outstanding = total − successful payments, floored at zero so an
     * over-payment never reports a negative balance.
     */
    private BigDecimal outstandingAmount(Invoice invoice) {
        BigDecimal total = invoice.getTotal() == null ? BigDecimal.ZERO : invoice.getTotal();
        BigDecimal paid  = paymentRepository.sumAmountByInvoiceIdAndStatus(
                invoice.getId(), PAYMENT_STATUS_SETTLED);
        if (paid == null) {
            paid = BigDecimal.ZERO;
        }
        BigDecimal outstanding = total.subtract(paid);
        return outstanding.signum() < 0 ? BillingCalculator.money(BigDecimal.ZERO) : BillingCalculator.money(outstanding);
    }

    private InvoiceResponseDTO toResponse(Invoice e) {
        InvoiceResponseDTO dto = new InvoiceResponseDTO();
        dto.setId(e.getId());

        if (e.getJobCard() != null) {
            dto.setJobCardId(e.getJobCard().getId());
            if (e.getJobCard().getCustomer() != null) {
                dto.setCustomerName(e.getJobCard().getCustomer().getName());
            }
            if (e.getJobCard().getVehicle() != null) {
                dto.setVehicleInfo(e.getJobCard().getVehicle().getRegistrationNo()
                        + " | " + e.getJobCard().getVehicle().getModel());
            }
        }

        dto.setSubtotal(e.getSubtotal());
        dto.setDiscount(e.getDiscount());
        dto.setGst(e.getGst());
        dto.setTotal(e.getTotal());
        dto.setStatus(e.getStatus());
        dto.setInvoiceDate(e.getInvoiceDate());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());

        if (e.getId() != null) {
            dto.setItems(itemRepository.findByInvoiceIdOrderByIdAsc(e.getId())
                                       .stream()
                                       .map(this::itemToResponse)
                                       .toList());
            BigDecimal paid = paymentRepository.sumAmountByInvoiceIdAndStatus(
                    e.getId(), PAYMENT_STATUS_SETTLED);
            dto.setAmountPaid(paid == null ? BillingCalculator.money(BigDecimal.ZERO) : BillingCalculator.money(paid));
            dto.setOutstandingAmount(outstandingAmount(e));
        }
        return dto;
    }

    private InvoiceItemResponseDTO itemToResponse(InvoiceItem item) {
        InvoiceItemResponseDTO dto = new InvoiceItemResponseDTO();
        dto.setId(item.getId());
        dto.setDescription(item.getDescription());
        dto.setQuantity(item.getQuantity());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setLineAmount(item.getLineAmount());
        return dto;
    }
}

