package com.autoservicehub.service;

import com.autoservicehub.dto.InvoiceItemRequestDTO;
import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.dto.PaymentRequestDTO;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PaymentRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the Payment → Invoice workflow against a real database.
 *
 * <p>Covers what a Mockito unit test cannot: that the invoice status really is
 * persisted as the balance changes, that the outstanding amount is derived from
 * payments that actually exist, and that a rejected payment leaves nothing
 * behind.
 *
 * <p>Uses {@code @DataJpaTest}. application.yml pins MySQLDialect for the real
 * MySQL deployment, but the MySQL DDL it generates is not executable on H2, so
 * the schema would never be created — the dialect is therefore overridden to
 * match the actual JDBC metadata. The database name is isolated from the shared
 * {@code testdb} used by the other slices, because {@code create-drop} from one
 * context drops the schema for all of them.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuditServiceImpl.class, BillingCalculator.class, InvoiceServiceImpl.class, PaymentServiceImpl.class})
class PaymentWorkflowPersistenceTest {

    @Autowired PaymentServiceImpl  paymentService;
    @Autowired InvoiceServiceImpl  invoiceService;
    @Autowired InvoiceRepository   invoiceRepository;
    @Autowired PaymentRepository   paymentRepository;
    @Autowired JobCardRepository   jobCardRepository;

    // ── Fixtures ─────────────────────────────────────────────────────────

    /** An invoice of 1000.00 line value, so its total is 1180.00 with 18% GST. */
    private Invoice givenInvoice() {
        JobCard jc = new JobCard();
        jc.setJobCardNumber("JC-TEST-1");
        jc.setServiceType("BRAKE_SERVICE");
        jc.setStatus("IN_REPAIR");
        jc = jobCardRepository.save(jc);

        InvoiceItemRequestDTO line = new InvoiceItemRequestDTO();
        line.setDescription("Brake pads");
        line.setQuantity(2);
        line.setUnitPrice(new BigDecimal("500.00"));

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(line));

        return invoiceRepository.findById(invoiceService.create(req).getId()).orElseThrow();
    }

    private PaymentRequestDTO pay(Long invoiceId, String amount) {
        PaymentRequestDTO req = new PaymentRequestDTO();
        req.setInvoiceId(invoiceId);
        req.setAmount(new BigDecimal(amount));
        return req;
    }

    // ── R1 / R2: the invoice status really is persisted ───────────────────

    @Test
    @DisplayName("R1 — a partial payment persists the invoice as PARTIALLY_PAID")
    void r1_partialPayment_persistsPartiallyPaid() {
        Invoice inv = givenInvoice();
        assertThat(inv.getTotal()).isEqualByComparingTo("1180.00");

        paymentService.create(pay(inv.getId(), "500.00"));

        Invoice stored = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(stored.getStatus())
                .isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PARTIALLY_PAID);
        assertThat(invoiceService.getOutstandingAmount(inv.getId()))
                .isEqualByComparingTo("680.00");
    }

    @Test
    @DisplayName("R2 — a full payment persists the invoice as PAID with nothing outstanding")
    void r2_fullPayment_persistsPaid() {
        Invoice inv = givenInvoice();

        paymentService.create(pay(inv.getId(), "1180.00"));

        Invoice stored = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);
        assertThat(invoiceService.getOutstandingAmount(inv.getId())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("R3 — several partial payments accumulate until the invoice is PAID")
    void r3_multiplePartials_accumulateToPaid() {
        Invoice inv = givenInvoice();

        paymentService.create(pay(inv.getId(), "300.00"));
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PARTIALLY_PAID);

        paymentService.create(pay(inv.getId(), "880.00"));
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);
    }

    // ── R4: over-payment is refused ───────────────────────────────────────

    @Test
    @DisplayName("R4 — a payment beyond the outstanding amount is rejected and changes nothing")
    void r4_overPayment_rejected() {
        Invoice inv = givenInvoice();
        paymentService.create(pay(inv.getId(), "500.00"));
        long paymentsBefore = paymentRepository.count();

        assertThatThrownBy(() -> paymentService.create(pay(inv.getId(), "700.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exceeds the outstanding amount");

        assertThat(paymentRepository.count()).isEqualTo(paymentsBefore);
        assertThat(invoiceService.getOutstandingAmount(inv.getId()))
                .isEqualByComparingTo("680.00");
    }

    @Test
    @DisplayName("R5 — a payment exactly equal to the outstanding amount is accepted")
    void r5_paymentEqualToOutstanding_accepted() {
        Invoice inv = givenInvoice();
        paymentService.create(pay(inv.getId(), "500.00"));

        paymentService.create(pay(inv.getId(), "680.00"));

        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);
    }

    // ── R6: unknown / cancelled invoices ──────────────────────────────────

    @Test
    @DisplayName("R6 — a payment against an unknown invoice → ResourceNotFoundException")
    void r6_unknownInvoice_throws() {
        assertThatThrownBy(() -> paymentService.create(pay(999L, "100.00")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Invoice not found: 999");
    }

    @Test
    @DisplayName("R7 — a CANCELLED invoice accepts no payment and keeps its status")
    void r7_cancelledInvoice_rejected() {
        Invoice inv = givenInvoice();
        inv.setStatus("CANCELLED");
        invoiceRepository.save(inv);
        long paymentsBefore = paymentRepository.count();

        assertThatThrownBy(() -> paymentService.create(pay(inv.getId(), "100.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("is CANCELLED");

        assertThat(paymentRepository.count()).isEqualTo(paymentsBefore);
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo("CANCELLED");
    }

    // ── R8: payment history ───────────────────────────────────────────────

    @Test
    @DisplayName("R8 — payment history for an invoice is returned oldest first")
    void r8_paymentHistory_oldestFirst() {
        Invoice inv = givenInvoice();
        paymentService.create(pay(inv.getId(), "300.00"));
        paymentService.create(pay(inv.getId(), "400.00"));

        var page = paymentService.listByInvoice(inv.getId(), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getAmount()).isEqualByComparingTo("300.00");
        assertThat(page.getContent().get(1).getAmount()).isEqualByComparingTo("400.00");
    }

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
    @DisplayName("R9 — a rejected payment rolls back, leaving no payment and no status change")
    void r9_rejectedPayment_rollsBack() {
        Invoice inv = givenInvoice();
        inv.setStatus("CANCELLED");
        invoiceRepository.save(inv);

        assertThatThrownBy(() -> paymentService.create(pay(inv.getId(), "100.00")))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(paymentRepository.count()).isZero();
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("R10 — deleting a payment returns the invoice to PENDING")
    void r10_deletePayment_restoresStatus() {
        Invoice inv = givenInvoice();
        Long paymentId = paymentService.create(pay(inv.getId(), "1180.00")).getId();
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);

        paymentService.delete(paymentId);

        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PENDING);
        assertThat(invoiceService.getOutstandingAmount(inv.getId()))
                .isEqualByComparingTo("1180.00");
    }
}
