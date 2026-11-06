package com.autoservicehub.service;

import com.autoservicehub.dto.EstimateItemRequestDTO;
import com.autoservicehub.dto.EstimateRequestDTO;
import com.autoservicehub.dto.InvoiceItemRequestDTO;
import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.dto.PaymentRequestDTO;
import com.autoservicehub.entity.Estimate;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.exception.BusinessRuleException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Integration tests for billing against a real database.
 *
 * <p>Covers what a Mockito unit test cannot: that the computed totals are what
 * actually get persisted, that a rejected line item leaves the estimate/invoice
 * and its lines unwritten (real transaction rollback), and that the outstanding
 * amount is derived from payments that really exist.
 *
 * <p>Uses {@code @DataJpaTest}. application.yml pins MySQLDialect for the real
 * MySQL deployment, but the MySQL DDL it generates is not executable on H2, so
 * the schema would never be created — the dialect is therefore overridden here
 * to match the actual JDBC metadata. The database name is also isolated from the
 * shared {@code testdb} used by the other slices, because {@code create-drop}
 * from one context drops the schema for all of them.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:billing-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// BillingCalculator is a @Component in util, which @DataJpaTest does not scan,
// so it is imported explicitly alongside the services under test.
@Import({ AuditServiceImpl.class, BillingCalculator.class, EstimateServiceImpl.class, InvoiceServiceImpl.class, PaymentServiceImpl.class})
class BillingPersistenceTest {

    @Autowired EstimateServiceImpl     estimateService;
    @Autowired InvoiceServiceImpl      invoiceService;
    @Autowired PaymentServiceImpl      paymentService;
    @Autowired EstimateRepository      estimateRepository;
    @Autowired EstimateItemRepository  estimateItemRepository;
    @Autowired InvoiceRepository       invoiceRepository;
    @Autowired InvoiceItemRepository   invoiceItemRepository;
    @Autowired PaymentRepository       paymentRepository;
    @Autowired JobCardRepository       jobCardRepository;

    // ── Fixtures ─────────────────────────────────────────────────────────

    private JobCard givenJobCard() {
        JobCard jc = new JobCard();
        jc.setJobCardNumber("JC-TEST-1");
        jc.setServiceType("BRAKE_SERVICE");
        jc.setStatus("IN_REPAIR");
        return jobCardRepository.save(jc);
    }

    private EstimateItemRequestDTO estLine(String d, int q, String p) {
        EstimateItemRequestDTO dto = new EstimateItemRequestDTO();
        dto.setDescription(d);
        dto.setQuantity(q);
        dto.setUnitPrice(new BigDecimal(p));
        return dto;
    }

    private InvoiceItemRequestDTO invLine(String d, int q, String p) {
        InvoiceItemRequestDTO dto = new InvoiceItemRequestDTO();
        dto.setDescription(d);
        dto.setQuantity(q);
        dto.setUnitPrice(new BigDecimal(p));
        return dto;
    }

    // ── P1–P3: the computed totals are what actually get persisted ────────

    @Test
    @DisplayName("P1 — an estimate persists its job card, lines and calculated totals")
    void p1_estimate_persistsCalculatedTotals() {
        JobCard jc = givenJobCard();

        EstimateRequestDTO req = new EstimateRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(estLine("Brake pads", 2, "500.00"), estLine("Labour", 2, "150.00")));

        var response = estimateService.create(req);

        Estimate stored = estimateRepository.findById(response.getId()).orElseThrow();
        assertThat(stored.getJobCard().getId()).isEqualTo(jc.getId());
        assertThat(stored.getSubtotal()).isEqualByComparingTo("1300.00");
        assertThat(stored.getTax()).isEqualByComparingTo("234.00");
        assertThat(stored.getTotal()).isEqualByComparingTo("1534.00");

        List<com.autoservicehub.entity.EstimateItem> lines =
                estimateItemRepository.findByEstimateIdOrderByIdAsc(stored.getId());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getEstimate().getId()).isEqualTo(stored.getId());
        assertThat(lines.get(0).getLineAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("P2 — an invoice persists its calculated GST and total")
    void p2_invoice_persistsCalculatedTotals() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));

        var response = invoiceService.create(req);

        Invoice stored = invoiceRepository.findById(response.getId()).orElseThrow();
        assertThat(stored.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(stored.getGst()).isEqualByComparingTo("180.00");
        assertThat(stored.getTotal()).isEqualByComparingTo("1180.00");
        assertThat(invoiceItemRepository.findByInvoiceIdOrderByIdAsc(stored.getId())).hasSize(1);
    }

    @Test
    @DisplayName("P3 — a client-supplied total is ignored in the persisted row")
    void p3_clientTotalIgnoredInDatabase() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        req.setSubtotal(BigDecimal.ONE);
        req.setGst(new BigDecimal("0.01"));
        req.setTotal(BigDecimal.ONE);

        invoiceService.create(req);

        Invoice stored = invoiceRepository.findAll().get(0);
        assertThat(stored.getTotal()).isEqualByComparingTo("1180.00");
    }

    // ── P4 / P5: a rejected line item leaves nothing behind ───────────────

    /**
     * Rolled back by the service's own transaction.
     *
     * <p>{@code @DataJpaTest} wraps each test in one transaction, which the
     * service joins rather than starting its own — so a failure inside the
     * service could not be observed rolling anything back. Suspending the
     * ambient transaction with NOT_SUPPORTED lets {@code @Transactional} on the
     * service start, and roll back, a real transaction of its own.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("P4 — a zero-quantity line leaves no estimate and no line items behind")
    void p4_invalidLine_rollsBackEstimate() {
        JobCard jc = givenJobCard();

        EstimateRequestDTO req = new EstimateRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(estLine("Valid", 1, "100.00"), estLine("Invalid", 0, "50.00")));

        assertThatThrownBy(() -> estimateService.create(req))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(estimateRepository.count()).isZero();
        assertThat(estimateItemRepository.count()).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("P5 — an over-large discount leaves no invoice behind")
    void p5_invalidDiscount_rollsBackInvoice() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(invLine("Brake pads", 1, "100.00")));
        req.setDiscount(new BigDecimal("500.00"));

        assertThatThrownBy(() -> invoiceService.create(req))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(invoiceRepository.count()).isZero();
        assertThat(invoiceItemRepository.count()).isZero();
    }

    // ── P6–P8: outstanding amount from real payments ──────────────────────

    @Test
    @DisplayName("P6 — a recorded payment reduces the invoice's outstanding amount")
    void p6_payment_reducesOutstanding() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO inv = new InvoiceRequestDTO();
        inv.setJobCardId(jc.getId());
        inv.setItems(List.of(invLine("Brake pads", 2, "500.00")));   // total 1180
        Long invoiceId = invoiceService.create(inv).getId();

        PaymentRequestDTO pay = new PaymentRequestDTO();
        pay.setInvoiceId(invoiceId);
        pay.setAmount(new BigDecimal("180.00"));
        pay.setMode("UPI");
        pay.setStatus("SUCCESS");
        paymentService.create(pay);

        assertThat(invoiceService.getOutstandingAmount(invoiceId)).isEqualByComparingTo("1000.00");
        assertThat(paymentRepository.findByInvoiceIdOrderByIdAsc(invoiceId)).hasSize(1);
    }

    @Test
    @DisplayName("P7 — a pending payment does not reduce the outstanding amount")
    void p7_pendingPayment_doesNotReduceOutstanding() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO inv = new InvoiceRequestDTO();
        inv.setJobCardId(jc.getId());
        inv.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        Long invoiceId = invoiceService.create(inv).getId();

        PaymentRequestDTO pay = new PaymentRequestDTO();
        pay.setInvoiceId(invoiceId);
        pay.setAmount(new BigDecimal("180.00"));
        pay.setStatus("PENDING");
        paymentService.create(pay);

        assertThat(invoiceService.getOutstandingAmount(invoiceId)).isEqualByComparingTo("1180.00");
    }

    @Test
    @DisplayName("P8 — a payment against an unknown invoice → ResourceNotFoundException")
    void p8_unknownInvoice_throws() {
        PaymentRequestDTO pay = new PaymentRequestDTO();
        pay.setInvoiceId(999L);
        pay.setAmount(new BigDecimal("10.00"));
        pay.setStatus("SUCCESS");

        assertThatThrownBy(() -> paymentService.create(pay))
                .isInstanceOf(com.autoservicehub.exception.ResourceNotFoundException.class)
                .hasMessageContaining("Invoice not found: 999");
    }

    // ── P9: deleting an invoice removes its lines ─────────────────────────

    @Test
    @DisplayName("P9 — deleting an invoice removes its line items with it")
    void p9_deleteInvoice_removesLines() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO inv = new InvoiceRequestDTO();
        inv.setJobCardId(jc.getId());
        inv.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        Long invoiceId = invoiceService.create(inv).getId();

        assertThat(invoiceItemRepository.findByInvoiceIdOrderByIdAsc(invoiceId)).hasSize(1);

        invoiceService.delete(invoiceId);

        assertThat(invoiceRepository.findById(invoiceId)).isEmpty();
        assertThat(invoiceItemRepository.findByInvoiceIdOrderByIdAsc(invoiceId)).isEmpty();
    }

    /* ================================================================
       Per-document GST rate, persisted
       ================================================================ */

    @Test
    @DisplayName("P10 — a per-document gstRate is stored on the invoice and drives the total")
    void p10_invoice_persistsGstRate() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal("5"));
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));

        var response = invoiceService.create(req);

        Invoice stored = invoiceRepository.findById(response.getId()).orElseThrow();
        assertThat(stored.getGstRate()).isEqualByComparingTo("5");
        assertThat(stored.getGst()).isEqualByComparingTo("50.00");
        assertThat(stored.getTotal()).isEqualByComparingTo("1050.00");
    }

    @Test
    @DisplayName("P11 — an invoice with no gstRate is priced at the configured default")
    void p11_invoice_withoutGstRate_usesDefault() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));

        var response = invoiceService.create(req);

        Invoice stored = invoiceRepository.findById(response.getId()).orElseThrow();
        assertThat(stored.getGstRate()).isEqualByComparingTo("18");
        assertThat(stored.getGst()).isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("P12 — a converted invoice keeps the rate its estimate was quoted at")
    void p12_conversion_keepsQuotedRate() {
        JobCard jc = givenJobCard();

        EstimateRequestDTO estimateReq = new EstimateRequestDTO();
        estimateReq.setJobCardId(jc.getId());
        estimateReq.setGstRate(new BigDecimal("12"));
        estimateReq.setItems(List.of(estLine("Brake pads", 1, "1000.00")));
        var estimate = estimateService.create(estimateReq);
        assertThat(estimate.getTax()).isEqualByComparingTo("120.00");

        var invoice = invoiceService.convertFromEstimate(estimate.getId());

        assertThat(invoice.getGstRate()).isEqualByComparingTo("12");
        assertThat(invoice.getGst()).isEqualByComparingTo("120.00");
        assertThat(invoice.getTotal()).isEqualByComparingTo("1120.00");
    }

    // ── Every rate the Figma dropdown offers, persisted ──────────────────

    /**
     * All four offered rates, end to end through the real service and a real
     * database write. 2 x 500.00 = 1000.00 subtotal, no discount, so the GST is
     * the rate applied to 1000.00 and the total is 1000.00 + that.
     */
    @ParameterizedTest(name = "an invoice at {0}% persists rate, GST and total")
    @CsvSource({
            "18, 180.00, 1180.00",
            "5,   50.00, 1050.00",
            "12, 120.00, 1120.00",
            "28, 280.00, 1280.00"
    })
    @DisplayName("P13 — each selectable GST rate is stored and drives GST and total")
    void p13_allOfferedRates_persisted(String rate, String expectedGst, String expectedTotal) {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal(rate));
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));

        var response = invoiceService.create(req);

        Invoice stored = invoiceRepository.findById(response.getId()).orElseThrow();
        assertThat(stored.getGstRate()).isEqualByComparingTo(rate);
        assertThat(stored.getGst()).isEqualByComparingTo(expectedGst);
        assertThat(stored.getTotal()).isEqualByComparingTo(expectedTotal);
        // The rate reaches the response too, so the UI can show what was billed.
        assertThat(response.getGstRate()).isEqualByComparingTo(rate);
    }

    /**
     * The same four rates on the estimate path. Estimates and invoices are
     * separate services with separate columns, so "it works for invoices" says
     * nothing about estimates.
     */
    @ParameterizedTest(name = "an estimate at {0}% persists rate, tax and total")
    @CsvSource({
            "18, 180.00, 1180.00",
            "5,   50.00, 1050.00",
            "12, 120.00, 1120.00",
            "28, 280.00, 1280.00"
    })
    @DisplayName("P14 — each selectable GST rate is stored on an estimate too")
    void p14_allOfferedRates_persistedOnEstimate(String rate, String expectedTax, String expectedTotal) {
        JobCard jc = givenJobCard();

        EstimateRequestDTO req = new EstimateRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal(rate));
        req.setItems(List.of(estLine("Brake pads", 2, "500.00")));

        var response = estimateService.create(req);

        Estimate stored = estimateRepository.findById(response.getId()).orElseThrow();
        assertThat(stored.getGstRate()).isEqualByComparingTo(rate);
        assertThat(stored.getTax()).isEqualByComparingTo(expectedTax);
        assertThat(stored.getTotal()).isEqualByComparingTo(expectedTotal);
        assertThat(response.getGstRate()).isEqualByComparingTo(rate);
    }

    /**
     * GST is charged on the post-discount amount, so the rate and the discount
     * have to compose. 1000.00 less 100.00 leaves 900.00 of taxable value:
     * 18% -> 162.00, 28% -> 252.00.
     */
    @Test
    @DisplayName("P15 — a discount reduces the taxable base before GST is applied")
    void p15_discountLowersGstBase() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO at18 = new InvoiceRequestDTO();
        at18.setJobCardId(jc.getId());
        at18.setGstRate(new BigDecimal("18"));
        at18.setDiscount(new BigDecimal("100.00"));
        at18.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        var eighteen = invoiceService.create(at18);

        InvoiceRequestDTO at28 = new InvoiceRequestDTO();
        at28.setJobCardId(jc.getId());
        at28.setGstRate(new BigDecimal("28"));
        at28.setDiscount(new BigDecimal("100.00"));
        at28.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        var twentyEight = invoiceService.create(at28);

        assertThat(eighteen.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(eighteen.getDiscount()).isEqualByComparingTo("100.00");
        assertThat(eighteen.getGst()).isEqualByComparingTo("162.00");
        assertThat(eighteen.getTotal()).isEqualByComparingTo("1062.00");

        assertThat(twentyEight.getGst()).isEqualByComparingTo("252.00");
        assertThat(twentyEight.getTotal()).isEqualByComparingTo("1152.00");
    }

    /**
     * GST lands on a half-paisa and must round HALF_UP to two places:
     * 900.01 x 18% = 162.0018 -> 162.00, and 333.33 x 12% = 39.9996 -> 40.00.
     */
    @ParameterizedTest(name = "taxable {0} at {1}% rounds to {2}")
    @CsvSource({
            "900.01, 18, 162.00",
            "333.33, 12,  40.00",
            "0.01,   28,  0.00"
    })
    @DisplayName("P16 — GST is rounded HALF_UP to paise at the chosen rate")
    void p16_gstRoundedHalfUp(String taxable, String rate, String expectedGst) {
        JobCard jc = givenJobCard();
        // qty 1 at the taxable amount, so the line total is exactly that value.
        BigDecimal amount = new BigDecimal(taxable);

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal(rate));
        req.setItems(List.of(invLine("Rounding probe", 1, amount.toPlainString())));

        var response = invoiceService.create(req);

        assertThat(response.getSubtotal()).isEqualByComparingTo(amount);
        assertThat(response.getGst()).isEqualByComparingTo(expectedGst);
        assertThat(response.getTotal())
                .isEqualByComparingTo(amount.add(new BigDecimal(expectedGst)));
    }

    // ── An invalid rate is refused, and nothing is written ───────────────

    @ParameterizedTest(name = "gstRate {0} is rejected")
    @ValueSource(strings = {"-1", "101", "150"})
    @DisplayName("P17 — an out-of-range GST rate leaves no invoice behind")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void p17_invalidRate_rollsBackInvoice(String rate) {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal(rate));
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));

        assertThatThrownBy(() -> invoiceService.create(req))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(invoiceRepository.findAll()).isEmpty();
        assertThat(invoiceItemRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("P18 — an invalid GST rate on an estimate is rejected too")
    void p18_invalidRateOnEstimate_rejected() {
        JobCard jc = givenJobCard();

        EstimateRequestDTO req = new EstimateRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal("-5"));
        req.setItems(List.of(estLine("Brake pads", 2, "500.00")));

        assertThatThrownBy(() -> estimateService.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("gstRate must not be negative");
    }

    // ── A client cannot talk its way to a cheaper invoice ────────────────

    /**
     * The whole point of calculating server-side: a caller may state the rate,
     * but every rupee below is recomputed. The client asks for 5% while
     * asserting a 0-rupee total; the 5% is honoured and the asserted figures
     * are discarded.
     */
    @Test
    @DisplayName("P19 — asserted totals are ignored even when the rate is honoured")
    void p19_assertedTotalsIgnoredAlongsideHonouredRate() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal("5"));
        req.setSubtotal(BigDecimal.ZERO);
        req.setGst(BigDecimal.ZERO);
        req.setTotal(BigDecimal.ZERO);
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));

        var response = invoiceService.create(req);

        assertThat(response.getGstRate()).isEqualByComparingTo("5");
        assertThat(response.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(response.getGst()).isEqualByComparingTo("50.00");
        assertThat(response.getTotal()).isEqualByComparingTo("1050.00");

        Invoice stored = invoiceRepository.findById(response.getId()).orElseThrow();
        assertThat(stored.getTotal()).isEqualByComparingTo("1050.00");
    }

    /**
     * The same attack on the estimate path, plus a large discount — the one
     * client-supplied money value there is, so it is the one that has to be
     * re-validated against the calculated subtotal rather than trusted.
     */
    @Test
    @DisplayName("P20 — an estimate's asserted total is ignored and a discount over the subtotal is refused")
    void p20_estimateAssertedTotalIgnored() {
        JobCard jc = givenJobCard();

        EstimateRequestDTO honest = new EstimateRequestDTO();
        honest.setJobCardId(jc.getId());
        honest.setGstRate(new BigDecimal("28"));
        honest.setSubtotal(BigDecimal.ZERO);
        honest.setTax(BigDecimal.ZERO);
        honest.setTotal(BigDecimal.ZERO);
        honest.setItems(List.of(estLine("Brake pads", 2, "500.00")));
        var saved = estimateService.create(honest);

        assertThat(saved.getTax()).isEqualByComparingTo("280.00");
        assertThat(saved.getTotal()).isEqualByComparingTo("1280.00");

        // A discount larger than the subtotal is a decision, not a computation,
        // so it is the one client money value that must be checked — and refused.
        EstimateRequestDTO overDiscounted = new EstimateRequestDTO();
        overDiscounted.setJobCardId(jc.getId());
        overDiscounted.setGstRate(new BigDecimal("28"));
        overDiscounted.setDiscount(new BigDecimal("5000.00"));
        overDiscounted.setItems(List.of(estLine("Brake pads", 2, "500.00")));

        assertThatThrownBy(() -> estimateService.create(overDiscounted))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot exceed the subtotal");
    }

    // ── Pay Later: an unpaid invoice, and no payment row ──────────────────

    /**
     * What "Pay Later" means in the data model: the invoice exists and is
     * fully outstanding, and there is no payment behind it.
     *
     * <p>The UI records nothing when Pay Later is chosen, and the API refuses a
     * PAY_LATER payment outright (see {@code PaymentMode}), so the correct
     * outcome is simply an invoice with zero payments and a PENDING status. If
     * any of that ever changed, this is the test that catches it.
     */
    @Test
    @DisplayName("P21 — a Pay Later invoice is left PENDING with its full balance outstanding")
    void p21_payLater_leavesInvoiceUnpaid() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal("18"));
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));

        var invoice = invoiceService.create(req);

        // No payment was created...
        assertThat(paymentRepository.findByInvoiceIdOrderByIdAsc(invoice.getId())).isEmpty();
        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("0.00");
        // ...so the invoice is still owed in full and still open.
        assertThat(invoice.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PENDING);
        assertThat(invoice.getStatus()).isNotEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);
        assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("1180.00");
    }

    @Test
    @DisplayName("P22 — posting a PAY_LATER payment is refused and settles nothing")
    void p22_payLaterPaymentRefused() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        var invoice = invoiceService.create(req);

        PaymentRequestDTO payLater = new PaymentRequestDTO();
        payLater.setInvoiceId(invoice.getId());
        payLater.setAmount(invoice.getTotal());
        payLater.setMode("PAY_LATER");

        // The amount would have covered the invoice exactly, so without this
        // check the invoice would have been marked PAID for money never received.
        assertThatThrownBy(() -> paymentService.create(payLater))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PAY_LATER is not a payment mode");

        assertThat(paymentRepository.findAll()).isEmpty();
        Invoice stored = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PENDING);
    }

    /** Every mode the dropdown offers, other than Pay Later, really does pay. */
    @ParameterizedTest(name = "mode {0} records a payment and settles the invoice")
    @ValueSource(strings = {"UPI", "CASH", "CARD", "BANK_TRANSFER"})
    @DisplayName("P23 — each real payment mode records a payment and closes the invoice")
    void p23_supportedModes_settleInvoice(String mode) {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal("12"));
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        var invoice = invoiceService.create(req);

        PaymentRequestDTO payment = new PaymentRequestDTO();
        payment.setInvoiceId(invoice.getId());
        payment.setAmount(invoice.getTotal());
        payment.setMode(mode);

        var saved = paymentService.create(payment);

        assertThat(saved.getMode()).isEqualTo(mode);
        assertThat(saved.getInvoiceStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);
        assertThat(saved.getInvoiceOutstandingAmount()).isEqualByComparingTo("0.00");
        assertThat(paymentRepository.findByInvoiceIdOrderByIdAsc(invoice.getId())).hasSize(1);
    }

    // ── Notes: saved, read back, cleared, and never invented ─────────────

    @Test
    @DisplayName("P24 — an invoice persists its notes and returns them on read")
    void p24_invoice_notesRoundTrip() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        req.setNotes("Payment due within 7 days. Parts ETA next Tuesday.");

        var created = invoiceService.create(req);

        // Returned by the create call itself...
        assertThat(created.getNotes()).isEqualTo("Payment due within 7 days. Parts ETA next Tuesday.");
        // ...and actually in the column, not just echoed back from the request.
        Invoice stored = invoiceRepository.findById(created.getId()).orElseThrow();
        assertThat(stored.getNotes()).isEqualTo("Payment due within 7 days. Parts ETA next Tuesday.");
        // ...and on a fresh read, which is what reopening the document does.
        assertThat(invoiceService.getById(created.getId()).getNotes())
                .isEqualTo("Payment due within 7 days. Parts ETA next Tuesday.");
    }

    @Test
    @DisplayName("P25 — editing an invoice rewrites its notes and the new value survives")
    void p25_invoice_notesAreEditable() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        req.setNotes("Original note.");
        var created = invoiceService.create(req);

        req.setNotes("Corrected note after customer called.");
        invoiceService.update(created.getId(), req);

        assertThat(invoiceRepository.findById(created.getId()).orElseThrow().getNotes())
                .isEqualTo("Corrected note after customer called.");
        assertThat(invoiceService.getById(created.getId()).getNotes())
                .isEqualTo("Corrected note after customer called.");
    }

    @ParameterizedTest(name = "blank notes [{0}] are stored as null, not as an empty string")
    @ValueSource(strings = {"", "   ", "\n\t "})
    @DisplayName("P26 — cleared notes are stored as NULL so 'no notes' has one representation")
    void p26_blankNotesAreStoredAsNull(String blank) {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        req.setNotes("To be cleared.");
        var created = invoiceService.create(req);

        req.setNotes(blank);
        invoiceService.update(created.getId(), req);

        Invoice stored = invoiceRepository.findById(created.getId()).orElseThrow();
        assertThat(stored.getNotes()).isNull();
        assertThat(invoiceService.getById(created.getId()).getNotes()).isNull();
    }

    @Test
    @DisplayName("P27 — an invoice raised without notes stays null rather than defaulting to text")
    void p27_absentNotesRemainNull() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        // notes never set at all — the case an older client sends.
        var created = invoiceService.create(req);

        assertThat(invoiceRepository.findById(created.getId()).orElseThrow().getNotes()).isNull();
        assertThat(invoiceService.getById(created.getId()).getNotes()).isNull();
    }

    @Test
    @DisplayName("P28 — an estimate persists its notes and returns them on read")
    void p28_estimate_notesRoundTrip() {
        JobCard jc = givenJobCard();

        EstimateRequestDTO req = new EstimateRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(estLine("Brake pads", 2, "500.00")));
        req.setNotes("Quote assumes customer-supplied bolts.");

        var created = estimateService.create(req);

        assertThat(created.getNotes()).isEqualTo("Quote assumes customer-supplied bolts.");
        assertThat(estimateRepository.findById(created.getId()).orElseThrow().getNotes())
                .isEqualTo("Quote assumes customer-supplied bolts.");
        assertThat(estimateService.getById(created.getId()).getNotes())
                .isEqualTo("Quote assumes customer-supplied bolts.");

        req.setNotes("Revised: bolts included.");
        estimateService.update(created.getId(), req);
        assertThat(estimateService.getById(created.getId()).getNotes())
                .isEqualTo("Revised: bolts included.");

        req.setNotes("   ");
        estimateService.update(created.getId(), req);
        assertThat(estimateRepository.findById(created.getId()).orElseThrow().getNotes()).isNull();
    }

    @Test
    @DisplayName("P29 — converting an estimate carries its notes onto the invoice")
    void p29_conversionCarriesNotes() {
        JobCard jc = givenJobCard();

        EstimateRequestDTO req = new EstimateRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(estLine("Brake pads", 2, "500.00")));
        req.setNotes("Valid for 30 days only.");
        var estimate = estimateService.create(req);

        var invoice = invoiceService.convertFromEstimate(estimate.getId());

        assertThat(invoice.getNotes()).isEqualTo("Valid for 30 days only.");
        assertThat(invoiceRepository.findById(invoice.getId()).orElseThrow().getNotes())
                .isEqualTo("Valid for 30 days only.");
    }

    @Test
    @DisplayName("P30 — notes do not disturb the calculated totals")
    void p30_notesDoNotAffectTotals() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal("18"));
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));
        req.setNotes("A note that should not be billed for.");

        var response = invoiceService.create(req);

        // Same numbers as the same invoice raised with no notes at all:
        // 1000.00 subtotal, 18% GST, nothing for the note.
        assertThat(response.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(response.getGst()).isEqualByComparingTo("180.00");
        assertThat(response.getTotal()).isEqualByComparingTo("1180.00");
    }
}
