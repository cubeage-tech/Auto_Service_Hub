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
        "spring.datasource.url=jdbc:h2:mem:billing-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// BillingCalculator is a @Component in util, which @DataJpaTest does not scan,
// so it is imported explicitly alongside the services under test.
@Import({BillingCalculator.class, EstimateServiceImpl.class, InvoiceServiceImpl.class, PaymentServiceImpl.class})
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
}
