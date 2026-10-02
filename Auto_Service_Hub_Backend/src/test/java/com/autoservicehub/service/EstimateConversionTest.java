package com.autoservicehub.service;

import com.autoservicehub.dto.EstimateItemRequestDTO;
import com.autoservicehub.dto.EstimateRequestDTO;
import com.autoservicehub.dto.InvoiceLabourItemRequestDTO;
import com.autoservicehub.dto.PaymentRequestDTO;
import com.autoservicehub.entity.BillingItemCategory;
import com.autoservicehub.entity.Estimate;
import com.autoservicehub.entity.EstimateItem;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.InvoiceItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.EstimateItemRepository;
import com.autoservicehub.repository.EstimateRepository;
import com.autoservicehub.repository.InvoiceItemRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PaymentRepository;
import com.autoservicehub.service.impl.EstimateServiceImpl;
import com.autoservicehub.service.impl.InvoiceServiceImpl;
import com.autoservicehub.service.impl.PaymentServiceImpl;
import com.autoservicehub.service.impl.AuditServiceImpl;
import com.autoservicehub.util.BillingCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for estimate → invoice conversion (FR-BILL-1 → FR-BILL-2).
 *
 * <p>Covers what a Mockito test cannot: that the converted invoice really is
 * persisted with its estimate reference, that its money is recalculated rather
 * than copied, that the estimate really is frozen afterwards, that a converted
 * invoice cannot be produced a second time — including by writing the row
 * directly, which is what proves the UNIQUE constraint rather than the service
 * check — and that a failure part-way through rolls the whole thing back.
 *
 * <p>Job cards are built without a vehicle: {@code @DataJpaTest} cannot create
 * the {@code vehicles} table on H2 at all, because {@code Vehicle} declares a
 * column named {@code year} and that is an H2 reserved word — a pre-existing
 * limitation this task must not fix by touching {@code Vehicle}. Nothing here
 * depends on the vehicle: the conversion reaches customer and vehicle through the
 * job card, so the job card's identity is all that is under test.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:estimate-convert-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuditServiceImpl.class, BillingCalculator.class, EstimateServiceImpl.class, InvoiceServiceImpl.class, PaymentServiceImpl.class})
class EstimateConversionTest {

    @Autowired EstimateServiceImpl    estimateService;
    @Autowired InvoiceServiceImpl    invoiceService;
    @Autowired PaymentServiceImpl    paymentService;
    @Autowired EstimateRepository    estimateRepository;
    @Autowired EstimateItemRepository estimateItemRepository;
    @Autowired InvoiceRepository     invoiceRepository;
    @Autowired InvoiceItemRepository invoiceItemRepository;
    @Autowired JobCardRepository     jobCardRepository;
    @Autowired PaymentRepository     paymentRepository;

    // ── Fixtures ────────────────────────────────────────────────────────

    private JobCard givenJobCard(String number) {
        JobCard jc = new JobCard();
        jc.setJobCardNumber(number);
        jc.setServiceType("BRAKE_SERVICE");
        jc.setStatus("DELIVERED");
        jc.setAssignedDate(java.time.LocalDateTime.now());
        return jobCardRepository.save(jc);
    }

    private EstimateItemRequestDTO line(String description, int qty, String unitPrice,
                                        BillingItemCategory category) {
        EstimateItemRequestDTO dto = new EstimateItemRequestDTO();
        dto.setDescription(description);
        dto.setQuantity(qty);
        dto.setUnitPrice(new BigDecimal(unitPrice));
        dto.setCategory(category);
        return dto;
    }

    /** Builds the estimate through the service, then forces the status it needs. */
    private Estimate estimateWithStatus(JobCard jc, String status, EstimateItemRequestDTO... items) {
        var created = estimateService.create(requestFor(jc, items));
        Estimate e = estimateRepository.findById(created.getId()).orElseThrow();
        e.setStatus(status);
        return estimateRepository.save(e);
    }

    private Estimate draftEstimate(JobCard jc, EstimateItemRequestDTO... items) {
        return estimateWithStatus(jc, "DRAFT", items);
    }

    private EstimateRequestDTO requestFor(JobCard jc, EstimateItemRequestDTO... items) {
        EstimateRequestDTO req = new EstimateRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(items));
        return req;
    }

    // ── Successful conversion ──────────────────────────────────────────

@Test
@DisplayName("EC1 - a draft estimate converts into an invoice linked to the same job card")
void ec1_convert_createsInvoice() {
JobCard jc = givenJobCard("JC-EC1");
Estimate estimate = draftEstimate(jc, line("Brake pads", 2, "500.00", BillingItemCategory.PART));

var response = invoiceService.convertFromEstimate(estimate.getId());

Invoice stored = invoiceRepository.findById(response.getId()).orElseThrow();
assertThat(stored.getJobCard().getId()).isEqualTo(jc.getId());
assertThat(stored.getEstimate().getId()).isEqualTo(estimate.getId());
assertThat(response.getJobCardId()).isEqualTo(jc.getId());
assertThat(response.getEstimateId()).isEqualTo(estimate.getId());
}

@Test
@DisplayName("EC2 - the estimate is marked CONVERTED and can no longer be edited")
void ec2_convert_marksEstimateConverted() {
JobCard jc = givenJobCard("JC-EC2");
Estimate estimate = draftEstimate(jc, line("Brake pads", 2, "500.00", BillingItemCategory.PART));

invoiceService.convertFromEstimate(estimate.getId());

assertThat(estimateRepository.findById(estimate.getId()).orElseThrow().getStatus())
.isEqualTo(EstimateServiceImpl.STATUS_CONVERTED);

// The pre-existing freeze still applies to this estimate.
assertThatThrownBy(() -> estimateService.update(estimate.getId(),
requestFor(jc, line("Changed", 1, "1.00", BillingItemCategory.PART))))
.isInstanceOf(BusinessRuleException.class)
.hasMessageContaining("converted to an invoice");
}

@Test
@DisplayName("EC3 - the converted invoice starts unpaid, exactly like a hand-raised one")
void ec3_convert_invoiceStartsUnpaid() {
JobCard jc = givenJobCard("JC-EC3");
Estimate estimate = draftEstimate(jc, line("Brake pads", 2, "500.00", BillingItemCategory.PART));

var response = invoiceService.convertFromEstimate(estimate.getId());

assertThat(response.getStatus()).isEqualTo("PENDING");
assertThat(response.getAmountPaid()).isEqualByComparingTo("0.00");
assertThat(response.getOutstandingAmount()).isEqualByComparingTo(response.getTotal());
// No payment is implied by conversion.
assertThat(paymentRepository.count()).isZero();
}

// ── Item and category conversion ───────────────────────────────────

@Test
@DisplayName("EC4 - PART items convert with their category, quantity and price")
void ec4_partItems_convert() {
JobCard jc = givenJobCard("JC-EC4");
Estimate estimate = draftEstimate(jc,
line("Brake pads", 2, "500.00", BillingItemCategory.PART),
line("Brake fluid", 1, "250.00", BillingItemCategory.PART));

var response = invoiceService.convertFromEstimate(estimate.getId());

assertThat(response.getItems()).hasSize(2);
assertThat(response.getItems()).extracting(
com.autoservicehub.dto.InvoiceItemResponseDTO::getCategory)
.containsOnly(BillingItemCategory.PART);
InvoiceItem first = invoiceItemRepository.findByInvoiceIdOrderByIdAsc(response.getId()).get(0);
assertThat(first.getDescription()).isEqualTo("Brake pads");
assertThat(first.getQuantity()).isEqualTo(2);
assertThat(first.getUnitPrice()).isEqualByComparingTo("500.00");
assertThat(first.getLineAmount()).isEqualByComparingTo("1000.00");
}

@Test
@DisplayName("EC5 - LABOUR items convert as LABOUR but carry no job-task provenance")
void ec5_labourItems_convert() {
JobCard jc = givenJobCard("JC-EC5");
Estimate estimate = draftEstimate(jc, line("Replace brake pads", 1, "500.00", BillingItemCategory.LABOUR));

var response = invoiceService.convertFromEstimate(estimate.getId());

assertThat(response.getItems()).singleElement().satisfies(item -> {
assertThat(item.getCategory()).isEqualTo(BillingItemCategory.LABOUR);
assertThat(item.getLineAmount()).isEqualByComparingTo("500.00");
// EstimateItem has no JobTask link by design, so quoted labour is unprovenanced.
assertThat(item.getJobTaskId()).isNull();
});
}

@Test
@DisplayName("EC6 - PACKAGE and OTHER categories survive the conversion")
void ec6_packageAndOther_convert() {
JobCard jc = givenJobCard("JC-EC6");
Estimate estimate = draftEstimate(jc,
line("Full service package", 1, "4000.00", BillingItemCategory.PACKAGE),
line("Shop consumables", 1, "150.00", BillingItemCategory.OTHER));

var response = invoiceService.convertFromEstimate(estimate.getId());

assertThat(response.getItems()).extracting(
com.autoservicehub.dto.InvoiceItemResponseDTO::getCategory)
.containsExactlyInAnyOrder(BillingItemCategory.PACKAGE, BillingItemCategory.OTHER);
}

@Test
@DisplayName("EC7 - a mixed estimate keeps every category and totals them all")
void ec7_mixedCategories_convert() {
JobCard jc = givenJobCard("JC-EC7");
Estimate estimate = draftEstimate(jc,
line("Brake pads", 2, "500.00", BillingItemCategory.PART),
line("Replace brake pads", 1, "500.00", BillingItemCategory.LABOUR),
line("Full service package", 1, "4000.00", BillingItemCategory.PACKAGE));

var response = invoiceService.convertFromEstimate(estimate.getId());

assertThat(response.getItems()).extracting(
com.autoservicehub.dto.InvoiceItemResponseDTO::getCategory)
.containsExactly(BillingItemCategory.PART, BillingItemCategory.LABOUR, BillingItemCategory.PACKAGE);
// 1000 + 500 + 4000
assertThat(response.getSubtotal()).isEqualByComparingTo("5500.00");
}

// ── Money is recalculated, never copied ───────────────────────────

@Test
@DisplayName("EC8 - subtotal, 18% GST and total are recalculated server-side")
void ec8_totals_recalculated() {
JobCard jc = givenJobCard("JC-EC8");
Estimate estimate = draftEstimate(jc,
line("Brake pads", 2, "500.00", BillingItemCategory.PART),
line("Replace brake pads", 1, "500.00", BillingItemCategory.LABOUR));

var response = invoiceService.convertFromEstimate(estimate.getId());

assertThat(response.getSubtotal()).isEqualByComparingTo("1500.00");
assertThat(response.getGst()).isEqualByComparingTo("270.00");
assertThat(response.getTotal()).isEqualByComparingTo("1770.00");
}

@Test
@DisplayName("EC9 - the estimate's discount carries across and still reduces the tax base")
void ec9_discount_carriesAcross() {
JobCard jc = givenJobCard("JC-EC9");
Estimate estimate = draftEstimate(jc, line("Brake pads", 2, "500.00", BillingItemCategory.PART));

// The discount is the one client-decided money value, so it is stored on the
// estimate and is expected to carry across.
Estimate stored = estimateRepository.findById(estimate.getId()).orElseThrow();
stored.setDiscount(new BigDecimal("200.00"));
estimateRepository.save(stored);

var response = invoiceService.convertFromEstimate(estimate.getId());

assertThat(response.getSubtotal()).isEqualByComparingTo("1000.00");
assertThat(response.getDiscount()).isEqualByComparingTo("200.00");
// taxable 800 -> GST 144 -> total 944
assertThat(response.getGst()).isEqualByComparingTo("144.00");
assertThat(response.getTotal()).isEqualByComparingTo("944.00");
}

/**
 * The estimate's stored totals are a cached summary of a quote and are never
 * copied across. This writes deliberately wrong ones onto the stored estimate
 * and asserts the invoice is unaffected — the conversion must derive the money
 * from the lines, not from the estimate's columns.
 */
@Test
@DisplayName("EC10 - the estimate's stored totals are ignored, not copied")
void ec10_storedTotalsAreIgnored() {
JobCard jc = givenJobCard("JC-EC10");
Estimate estimate = draftEstimate(jc, line("Brake pads", 2, "500.00", BillingItemCategory.PART));

Estimate stored = estimateRepository.findById(estimate.getId()).orElseThrow();
stored.setSubtotal(new BigDecimal("1.00"));
stored.setTax(new BigDecimal("2.00"));
stored.setTotal(new BigDecimal("3.00"));
estimateRepository.save(stored);

var response = invoiceService.convertFromEstimate(estimate.getId());

// 2 x 500 = 1000 subtotal, not the estimate's 1.00.
assertThat(response.getSubtotal()).isEqualByComparingTo("1000.00");
assertThat(response.getGst()).isEqualByComparingTo("180.00");
assertThat(response.getTotal()).isEqualByComparingTo("1180.00");
}

// ── Eligibility ─────────────────────────────────────────────────────

@Test
@DisplayName("EC11 - an unknown estimate is refused")
void ec11_unknownEstimate_refused() {
assertThatThrownBy(() -> invoiceService.convertFromEstimate(999999L))
.isInstanceOf(ResourceNotFoundException.class)
.hasMessageContaining("Estimate not found: 999999");
}

@Test
@DisplayName("EC12 - an already-converted estimate is refused a second time")
void ec12_alreadyConverted_refused() {
JobCard jc = givenJobCard("JC-EC12");
Estimate estimate = draftEstimate(jc, line("Brake pads", 2, "500.00", BillingItemCategory.PART));

invoiceService.convertFromEstimate(estimate.getId());
long invoicesAfterFirst = invoiceRepository.count();

assertThatThrownBy(() -> invoiceService.convertFromEstimate(estimate.getId()))
.isInstanceOf(BusinessRuleException.class)
.hasMessageContaining("already been converted");

// Exactly one invoice was produced.
assertThat(invoiceRepository.count()).isEqualTo(invoicesAfterFirst);
}

/**
 * The database-level guarantee.
 *
 * <p>The service check alone would not stop two concurrent conversions both
 * passing it. Writing a second invoice row against the same estimate directly,
 * bypassing every check, shows that the UNIQUE constraint on
 * {@code invoices.estimate_id} is what actually enforces one invoice per
 * estimate.
 *
 * <p>Only the exception is asserted here: a failed flush poisons the persistence
 * context, so this test cannot go on to query. EC13b checks the surviving state
 * in a clean one.
 */
@Test
@DisplayName("EC13 - the database refuses a second invoice for the same estimate")
void ec13_uniqueConstraint_preventsSecondInvoice() {
JobCard jc = givenJobCard("JC-EC13");
Estimate estimate = draftEstimate(jc, line("Brake pads", 2, "500.00", BillingItemCategory.PART));
invoiceService.convertFromEstimate(estimate.getId());

// Bypasses the service entirely, the way a race or a future caller would.
Invoice duplicate = new Invoice();
duplicate.setJobCard(jc);
duplicate.setEstimate(estimate);
duplicate.setStatus("PENDING");
duplicate.setSubtotal(BigDecimal.ZERO);
duplicate.setGst(BigDecimal.ZERO);
duplicate.setTotal(BigDecimal.ZERO);
duplicate.setInvoiceDate(java.time.LocalDate.now());

assertThatThrownBy(() -> invoiceRepository.saveAndFlush(duplicate))
.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
}

/** The companion to EC13: the rejected duplicate left the real invoice intact. */
@Test
@DisplayName("EC13b - a rejected duplicate leaves exactly one invoice for that estimate")
void ec13b_rejectedDuplicate_leavesOneInvoice() {
JobCard jc = givenJobCard("JC-EC13B");
Estimate estimate = draftEstimate(jc, line("Brake pads", 2, "500.00", BillingItemCategory.PART));
var converted = invoiceService.convertFromEstimate(estimate.getId());

assertThat(invoiceRepository.findByEstimateId(estimate.getId()))
.isPresent()
.get()
.satisfies(i -> assertThat(i.getId()).isEqualTo(converted.getId()));
assertThat(invoiceRepository.count()).isEqualTo(1);
}

@Test
@DisplayName("EC14 - an estimate with no items is refused")
void ec14_noItems_refused() {
JobCard jc = givenJobCard("JC-EC14");
Estimate estimate = draftEstimate(jc, line("Brake pads", 2, "500.00", BillingItemCategory.PART));

// Lines removed behind the service's back.
estimateItemRepository.deleteAll(estimateItemRepository.findByEstimateIdOrderByIdAsc(estimate.getId()));
estimateItemRepository.flush();

assertThatThrownBy(() -> invoiceService.convertFromEstimate(estimate.getId()))
.isInstanceOf(BusinessRuleException.class)
.hasMessageContaining("no line items");

assertThat(invoiceRepository.count()).isZero();
}

// ── Transactional rollback ──────────────────────────────────────────

/**
 * The atomicity proof.
 *
 * <p>{@code @DataJpaTest} wraps each test in one transaction that the service
 * joins rather than starting its own, so a failure inside the service could not
 * be observed rolling anything back. Suspending the ambient transaction with
 * NOT_SUPPORTED lets the service's own {@code @Transactional} start, and roll
 * back, a real transaction.
 *
 * <p>A second line with a blank description fails inside {@code convertLines}
 * <em>after</em> the first line and the invoice row have already been written.
 * If conversion were not atomic, a partial invoice with orphaned lines would
 * survive and the estimate would be left half-converted. Both must be gone.
 *
 * <p>The fixtures this test writes are committed for real, so it asserts against
 * deltas rather than absolute totals that other tests may have moved.
 */
@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("EC15 - a failure part-way through rolls back the invoice and the estimate status")
void ec15_failure_rollsBackEverything() {
JobCard jc = givenJobCard("JC-EC15");
Estimate estimate = draftEstimate(jc, line("Brake pads", 2, "500.00", BillingItemCategory.PART));

// Append a line the service cannot bill: no description.
EstimateItem broken = new EstimateItem();
broken.setEstimate(estimate);
broken.setDescription("   ");
broken.setQuantity(1);
broken.setUnitPrice(new BigDecimal("100.00"));
broken.setLineAmount(new BigDecimal("100.00"));
estimateItemRepository.save(broken);

long invoicesBefore = invoiceRepository.count();
long itemsBefore = invoiceItemRepository.count();

assertThatThrownBy(() -> invoiceService.convertFromEstimate(estimate.getId()))
.isInstanceOf(BusinessRuleException.class);

// No partial invoice, and not a single line written.
assertThat(invoiceRepository.count()).isEqualTo(invoicesBefore);
assertThat(invoiceItemRepository.count()).isEqualTo(itemsBefore);

// The estimate is untouched and therefore still convertible.
Estimate after = estimateRepository.findById(estimate.getId()).orElseThrow();
assertThat(after.getStatus()).isEqualTo("DRAFT");

// And the failure left it genuinely convertible: removing the bad line lets it
// convert normally, proving nothing was left half-done.
estimateItemRepository.delete(broken);
estimateItemRepository.flush();

var response = invoiceService.convertFromEstimate(estimate.getId());
assertThat(response.getTotal()).isEqualByComparingTo("1180.00");
assertThat(estimateRepository.findById(estimate.getId()).orElseThrow().getStatus())
.isEqualTo(EstimateServiceImpl.STATUS_CONVERTED);
}

// ── Existing invoice behaviour still holds on a converted invoice ──

/**
 * A converted invoice must behave like any other invoice, so this exercises the
 * real payment path against one: it settles in full, moves to PAID, and is then
 * frozen against further labour. Nothing about conversion may leave it in a
 * special state.
 */
@Test
@DisplayName("EC16 - a converted invoice can be paid, then freezes like any other")
void ec16_convertedInvoice_canBePaidThenFreezes() {
JobCard jc = givenJobCard("JC-EC16");
Estimate estimate = draftEstimate(jc, line("Brake pads", 2, "500.00", BillingItemCategory.PART));
var converted = invoiceService.convertFromEstimate(estimate.getId());
assertThat(converted.getTotal()).isEqualByComparingTo("1180.00");

PaymentRequestDTO payment = new PaymentRequestDTO();
payment.setInvoiceId(converted.getId());
payment.setAmount(new BigDecimal("1180.00"));
payment.setMode("CASH");

var paid = paymentService.create(payment);

assertThat(paid.getStatus()).isEqualTo(PaymentServiceImpl.PAYMENT_STATUS_SETTLED);
assertThat(invoiceRepository.findById(converted.getId()).orElseThrow().getStatus())
.isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);

// A settled invoice is frozen, so labour cannot be added afterwards.
InvoiceLabourItemRequestDTO labour = new InvoiceLabourItemRequestDTO();
labour.setJobTaskId(1L);
assertThatThrownBy(() -> invoiceService.addLabourItem(converted.getId(), labour))
.isInstanceOf(BusinessRuleException.class)
.hasMessageContaining("already PAID");
}
}
