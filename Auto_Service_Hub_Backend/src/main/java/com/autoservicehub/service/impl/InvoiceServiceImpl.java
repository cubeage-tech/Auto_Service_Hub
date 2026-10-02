package com.autoservicehub.service.impl;

import com.autoservicehub.dto.InvoiceItemRequestDTO;
import com.autoservicehub.dto.InvoiceItemResponseDTO;
import com.autoservicehub.dto.InvoiceLabourItemRequestDTO;
import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.BillingItemCategory;
import com.autoservicehub.entity.Estimate;
import com.autoservicehub.entity.EstimateItem;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.InvoiceItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.EstimateItemRepository;
import com.autoservicehub.repository.EstimateRepository;
import com.autoservicehub.repository.InvoiceItemRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.PaymentRepository;
import com.autoservicehub.service.AuditService;
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

    /**
     * Estimate status meaning "this quote has become an invoice".
     *
     * <p>Referenced from {@code EstimateServiceImpl}, which owns the estimate
     * lifecycle and freezes a converted one, rather than redeclared here. That
     * way the two sides of the conversion can never drift apart on what the word
     * means.
     */
    public static final String STATUS_ESTIMATE_CONVERTED = EstimateServiceImpl.STATUS_CONVERTED;

    /** Entity name recorded on this module's audit entries. */
    private static final String AUDIT_ENTITY = "INVOICE";

    private final InvoiceRepository     repository;
    private final InvoiceItemRepository itemRepository;
    private final JobCardRepository     jobCardRepository;
    private final JobTaskRepository     jobTaskRepository;
    private final EstimateRepository    estimateRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final PaymentRepository     paymentRepository;
    private final BillingCalculator     calculator;
    private final AuditService          auditService;

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

        InvoiceResponseDTO response = toResponse(saved);
        auditService.recordSuccess(AUDIT_ENTITY, saved.getId(), AuditAction.INVOICE_CREATE,
                "Invoice raised on job card " + request.getJobCardId()
                        + ": lines " + request.getItems().size()
                        + ", subtotal " + saved.getSubtotal()
                        + ", discount " + saved.getDiscount()
                        + ", gst " + saved.getGst()
                        + ", total " + saved.getTotal());
        return response;
    }

    @Override
    public InvoiceResponseDTO update(Long id, InvoiceRequestDTO request) {
        Invoice existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));

        // A settled invoice is a financial record: its value can no longer be
        // rewritten by editing lines.
        assertNotSettled(existing);
        validateItems(request.getItems());

        // Captured BEFORE recalculate, which overwrites it. "The invoice was edited and
        // its value moved from X to Y" is the question an audit of this answers.
        BigDecimal totalBefore = existing.getTotal();

        mapToEntity(request, existing);
        replaceItems(request.getItems(), existing);
        recalculate(existing);

        InvoiceResponseDTO response = toResponse(repository.save(existing));
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.INVOICE_UPDATE,
                "Invoice updated: lines " + request.getItems().size()
                        + ", total " + totalBefore + " -> " + response.getTotal()
                        + ", status " + response.getStatus());
        return response;
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
        // Captured before the delete: afterwards there is nothing left to describe.
        BigDecimal total = existing.getTotal();
        repository.deleteById(id);
        // Deleting an invoice removes a customer's bill, so it is one of the
        // actions an audit exists to answer for.
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.INVOICE_DELETE,
                "Invoice deleted: total " + total);
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

    // ══════════════════════════════════════════════════════════════════════
    // FR-BILL-1 → FR-BILL-2: estimate → invoice conversion
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Convert an estimate into an invoice.
     *
     * <p>Order matters: everything is checked before anything is written, so a
     * rejected estimate leaves no trace and stays convertible.
     *
     * <p>{@code Invoice.estimate} is set, which both records the provenance and
     * carries the one-invoice-per-estimate guarantee.
     */
    @Override
    public InvoiceResponseDTO convertFromEstimate(Long estimateId) {
        Estimate estimate = estimateRepository.findById(estimateId)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate not found: " + estimateId));

        assertConvertible(estimate);

        // Mandatory billing data: an invoice must bill a job. Estimate.jobCard is
        // NOT NULL, but the check is explicit rather than assumed, because an
        // invoice with no job card cannot be closed or paid against later.
        if (estimate.getJobCard() == null) {
            throw new BusinessRuleException(
                    "Estimate " + estimateId + " has no job card and cannot be converted.");
        }

        List<EstimateItem> lines = estimateItemRepository
                .findByEstimateIdOrderByIdAsc(estimateId);
        if (lines.isEmpty()) {
            throw new BusinessRuleException(
                    "Estimate " + estimateId + " has no line items and cannot be converted.");
        }

        // The customer and vehicle are reached through the job card rather than
        // copied, so they cannot drift between the two documents.
        Invoice invoice = new Invoice();
        invoice.setJobCard(estimate.getJobCard());
        invoice.setEstimate(estimate);
        invoice.setInvoiceDate(LocalDate.now());
        // A converted invoice starts exactly like a hand-raised one: unpaid.
        // Payment is a separate operation and must never be implied here.
        invoice.setStatus(PaymentServiceImpl.INVOICE_STATUS_PENDING);
        // The one client-decided money value carries across; it is re-validated
        // against the recalculated subtotal below.
        invoice.setDiscount(estimate.getDiscount());

        Invoice saved = repository.save(invoice);
        convertLines(lines, saved);
        // Subtotal, GST and total are recomputed from the stored invoice lines.
        // The estimate's own subtotal/tax/total are deliberately ignored.
        recalculate(saved);
        repository.save(saved);

        // Last, so a failure anywhere above leaves the estimate convertible.
        estimate.setStatus(STATUS_ESTIMATE_CONVERTED);
        estimateRepository.save(estimate);

        InvoiceResponseDTO response = toResponse(saved);
        auditService.recordSuccess(AUDIT_ENTITY, saved.getId(), AuditAction.ESTIMATE_CONVERT,
                "Estimate " + estimateId + " converted to invoice " + saved.getId()
                        + ": lines " + lines.size()
                        + ", discount " + saved.getDiscount()
                        + ", total " + saved.getTotal());
        return response;
    }

    /** An estimate may only be converted once, and only while it is still a draft-like quote. */
    private void assertConvertible(Estimate estimate) {
        if (STATUS_ESTIMATE_CONVERTED.equalsIgnoreCase(estimate.getStatus())) {
            throw new BusinessRuleException(
                    "Estimate " + estimate.getId()
                    + " has already been converted to an invoice and cannot be converted again.");
        }
        // Belt and braces alongside the UNIQUE constraint on invoices.estimate_id:
        // this turns a race into a readable refusal instead of a constraint error.
        Invoice existing = repository.findByEstimateId(estimate.getId()).orElse(null);
        if (existing != null) {
            throw new BusinessRuleException(
                    "Estimate " + estimate.getId() + " has already been converted to invoice "
                    + existing.getId() + ".");
        }
    }

    /**
     * Turn each quoted line into a billed line.
     *
     * <p>Only description, quantity, unit price and category carry across. The
     * line amount is recomputed rather than copied, so a stored estimate figure
     * can never set an invoice figure.
     *
     * <p>No {@code jobTask} is attached. {@code EstimateItem} deliberately has no
     * task link — an estimate is quoted before the work is done, so it may quote
     * labour for a task that does not exist yet. A converted labour line
     * therefore carries the LABOUR category but no task provenance, and is not
     * covered by the duplicate guard in {@link #addLabourItem}. This is the
     * documented limitation of converting quoted labour: it is a quoted charge,
     * not a verified one.
     */
    private void convertLines(List<EstimateItem> lines, Invoice invoice) {
        for (EstimateItem line : lines) {
            if (line.getDescription() == null || line.getDescription().isBlank()) {
                throw new BusinessRuleException(
                        "Estimate item " + line.getId() + " has no description and cannot be billed.");
            }

            // Refuses a non-positive quantity, a negative price or sub-paise
            // precision, exactly as it does for a hand-raised invoice.
            BigDecimal lineAmount = calculator.lineAmount(line.getQuantity(), line.getUnitPrice());

            InvoiceItem item = new InvoiceItem();
            item.setInvoice(invoice);
            item.setDescription(line.getDescription());
            item.setQuantity(line.getQuantity());
            item.setUnitPrice(line.getUnitPrice());
            item.setLineAmount(lineAmount);
            item.setCategory(line.getCategory() == null ? BillingItemCategory.PART : line.getCategory());
            itemRepository.save(item);
        }
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
            e.setStatus(resolveClientStatus(r.getStatus()));
        }
        if (r.getInvoiceDate() != null) {
            e.setInvoiceDate(r.getInvoiceDate());
        }
    }

    /**
     * Decides what status a client-requested status is allowed to become
     * (FR-BILL-6, "prevent final invoice closure when mandatory billing data is
     * missing").
     *
     * <p>{@code PAID} and {@code PARTIALLY_PAID} are <em>derived</em> from recorded
     * payments by {@link PaymentServiceImpl}; they are never accepted from a
     * client. Without this, a caller could mark an invoice paid with no payment
     * against it, which contradicts the payment flow and BR-04's server-side
     * recalculation.
     *
     * <p>{@code PENDING} and {@code CANCELLED} remain client-settable because they
     * record a decision rather than an amount. Any other value is rejected rather
     * than stored, so the status set stays closed the way task and job-card
     * statuses are.
     */
    private String resolveClientStatus(String requested) {
        String normalized = requested.trim().toUpperCase();
        return switch (normalized) {
            case PaymentServiceImpl.INVOICE_STATUS_PENDING,
                 PaymentServiceImpl.INVOICE_STATUS_CANCELLED -> normalized;
            case PaymentServiceImpl.INVOICE_STATUS_PAID,
                 PaymentServiceImpl.INVOICE_STATUS_PARTIALLY_PAID ->
                    throw new BusinessRuleException(
                        "Invoice status " + normalized + " is derived from recorded payments "
                        + "and cannot be set directly. Record a payment instead.");
            default -> throw new BusinessRuleException(
                "Unsupported invoice status: '" + requested.trim() + "'. Allowed values are "
                + PaymentServiceImpl.INVOICE_STATUS_PENDING + ", "
                + PaymentServiceImpl.INVOICE_STATUS_CANCELLED + ".");
        };
    }

    /**
     * Refuses to let an invoice with nothing billable on it be treated as settled
     * (FR-BILL-6).
     *
     * <p>An empty invoice has no mandatory billing data, so closing it would
     * record a financial fact about work that was never billed. Line count is
     * checked rather than "total is zero" because a zero-value invoice can still
     * be legitimate (fully discounted work) — the line is what makes it billable.
     */
    private void assertClosable(Invoice invoice) {
        long lineCount = itemRepository.countByInvoiceId(invoice.getId());
        if (lineCount == 0) {
            throw new BusinessRuleException(
                    "Invoice " + invoice.getId() + " has no line items and cannot be closed.");
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
     *
     * <p>Lines that came from a repair task are carried across rather than
     * dropped. They are not in the submitted payload — a client has no way to
     * express one, since {@link InvoiceItemRequestDTO} has no jobTask field —
     * so a plain delete-all would silently drop the labour the invoice was
     * already billing whenever anyone edited it. Re-creating them from their
     * tasks keeps the invoice equal to its parts plus exactly the labour it has
     * been told to charge, once.
     */
    private void replaceItems(List<InvoiceItemRequestDTO> requested, Invoice invoice) {
        List<InvoiceItem> taskLabour = itemRepository
                .findByInvoiceIdAndJobTaskIdIsNotNullOrderByIdAsc(invoice.getId());

        itemRepository.deleteAll(itemRepository.findByInvoiceIdOrderByIdAsc(invoice.getId()));

        for (InvoiceItemRequestDTO itemRequest : requested) {
            InvoiceItem item = new InvoiceItem();
            item.setInvoice(invoice);
            item.setDescription(itemRequest.getDescription());
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(itemRequest.getUnitPrice());
            item.setLineAmount(calculator.lineAmount(itemRequest.getQuantity(), itemRequest.getUnitPrice()));
            // Null means "not stated", which reads as PART.
            item.setCategory(itemRequest.getCategory());
            itemRepository.save(item);
        }

        for (InvoiceItem carried : taskLabour) {
            InvoiceItem item = new InvoiceItem();
            item.setInvoice(invoice);
            item.setJobTask(carried.getJobTask());
            item.setDescription(carried.getDescription());
            item.setQuantity(carried.getQuantity());
            item.setUnitPrice(carried.getUnitPrice());
            // Recalculated, never copied: the amount must still agree with the
            // stored quantity and unit price after a round trip.
            item.setLineAmount(calculator.lineAmount(carried.getQuantity(), carried.getUnitPrice()));
            item.setCategory(BillingItemCategory.LABOUR);
            itemRepository.save(item);
        }
    }

    @Override
    public InvoiceResponseDTO addLabourItem(Long invoiceId, InvoiceLabourItemRequestDTO request) {
        Invoice invoice = repository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));

        // A settled invoice is a financial record: adding labour to it would
        // change what the customer has already paid.
        assertNotSettled(invoice);

        JobTask task = jobTaskRepository.findById(request.getJobTaskId())
                .orElseThrow(() -> new ResourceNotFoundException("JobTask not found: " + request.getJobTaskId()));

        // The task must belong to the job this invoice bills. A task from another
        // job card means a different vehicle or a different customer, and
        // charging one for the other's work is the exact error this guards.
        assertTaskBelongsToInvoice(task, invoice);

        // One task, one charge. Checked before the write so the rejected call
        // leaves nothing behind.
        if (itemRepository.existsByInvoiceIdAndJobTaskId(invoiceId, task.getId())) {
            throw new BusinessRuleException(
                    "JobTask " + task.getId() + " has already been billed on invoice " + invoiceId + ".");
        }

        // The task's own labour cost is the unit price. A task with no costed
        // labour contributes nothing, and BillingCalculator refuses anything
        // negative or sub-paise rather than letting it into the invoice.
        BigDecimal labourCost = task.getLabourCost() == null ? BigDecimal.ZERO : task.getLabourCost();
        BigDecimal lineAmount = calculator.lineAmount(1, labourCost);

        InvoiceItem item = new InvoiceItem();
        item.setInvoice(invoice);
        item.setJobTask(task);
        item.setDescription(task.getDescription());
        item.setQuantity(1);
        item.setUnitPrice(labourCost);
        item.setLineAmount(lineAmount);
        item.setCategory(BillingItemCategory.LABOUR);
        itemRepository.save(item);

        // Subtotal, GST and total are recomputed from the stored lines exactly
        // as they are for a manually created invoice - BillingCalculator stays
        // the only place money is computed.
        recalculate(invoice);
        repository.save(invoice);

        InvoiceResponseDTO response = toResponse(invoice);
        auditService.recordSuccess(AUDIT_ENTITY, invoice.getId(), AuditAction.INVOICE_LABOUR_ADDED,
                "Labour billed from job task " + task.getId()
                        + ": '" + task.getDescription() + "'"
                        + ", amount " + lineAmount
                        + "; new total " + response.getTotal());
        return response;
    }

    /**
     * A task may only be billed onto an invoice for the job card it belongs to.
     *
     * <p>The job card is the primary rule. The vehicle is compared as well,
     * because a job card owns the work but a vehicle is what the customer owns:
     * if the two sides ever disagree about which car is being billed, charging
     * one customer for work on another's vehicle is the worst possible invoice
     * error, and the one most likely to be disputed.
     *
     * <p>A null on either side is a rejection rather than a pass. An invoice or
     * a task with no job card has nothing to prove the work was done for this
     * customer, and treating "unknown" as "matches" would bill on trust.
     */
    private void assertTaskBelongsToInvoice(JobTask task, Invoice invoice) {
        JobCard taskCard = task.getJobCard();
        JobCard invoiceCard = invoice.getJobCard();

        Long taskJobCardId = taskCard == null ? null : taskCard.getId();
        Long invoiceJobCardId = invoiceCard == null ? null : invoiceCard.getId();

        if (taskJobCardId == null || invoiceJobCardId == null
                || !taskJobCardId.equals(invoiceJobCardId)) {
            throw new BusinessRuleException(
                    "JobTask " + task.getId() + " belongs to job card " + taskJobCardId
                    + " and cannot be billed on invoice " + invoice.getId()
                    + ", which bills job card " + invoiceJobCardId + ".");
        }

        if (!sameVehicle(taskCard, invoiceCard)) {
            throw new BusinessRuleException(
                    "JobTask " + task.getId() + " is work on a different vehicle and cannot be "
                    + "billed on invoice " + invoice.getId() + ".");
        }
    }

    /**
     * Do the two job cards refer to the same vehicle?
     *
     * <p>Only enforced when both sides name a vehicle. {@code JobCard.vehicle}
     * is nullable and existing rows may predate it, so a job card with no
     * vehicle cannot fail this check — the job-card comparison above has already
     * proven the two are the same job.
     */
    private boolean sameVehicle(JobCard taskCard, JobCard invoiceCard) {
        Long taskVehicleId = taskCard.getVehicle() == null ? null : taskCard.getVehicle().getId();
        Long invoiceVehicleId = invoiceCard.getVehicle() == null ? null : invoiceCard.getVehicle().getId();

        if (taskVehicleId == null || invoiceVehicleId == null) {
            return true;
        }
        return taskVehicleId.equals(invoiceVehicleId);
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
        if (e.getEstimate() != null) {
            dto.setEstimateId(e.getEstimate().getId());
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
        // A line stored before the category existed is a part: that is what every
        // line meant before this column, and reporting it as anything else would
        // invent information the row does not carry.
        dto.setCategory(item.getCategory() == null ? BillingItemCategory.PART : item.getCategory());
        if (item.getJobTask() != null) {
            dto.setJobTaskId(item.getJobTask().getId());
        }
        return dto;
    }
}

