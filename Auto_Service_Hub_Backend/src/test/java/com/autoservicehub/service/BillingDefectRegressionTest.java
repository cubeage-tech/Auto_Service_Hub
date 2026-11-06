package com.autoservicehub.service;

import com.autoservicehub.dto.EstimateItemRequestDTO;
import com.autoservicehub.dto.EstimateRequestDTO;
import com.autoservicehub.dto.InvoiceItemRequestDTO;
import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.dto.PaymentRequestDTO;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Payment;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.EstimateRepository;
import com.autoservicehub.repository.InvoiceItemRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PaymentRepository;
import com.autoservicehub.service.impl.AuditServiceImpl;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the billing defects fixed against a real database.
 *
 * <p>Each test here corresponds to a defect that was reproducible and has been
 * repaired; they exist to fail loudly if the repair is undone. They run against
 * the same {@code @DataJpaTest} slice as {@link BillingPersistenceTest}, so the
 * foreign keys really are enforced — which is the whole point of the deletion
 * tests: with a mocked repository the restrictive {@code payments.invoice_id}
 * constraint that caused the HTTP 500 does not exist to be violated.
 *
 * <p>D1  deleting an invoice that has payments is refused, not a 500
 * <p>D2  deleting a converted estimate is refused
 * <p>D3  a GST rate finer than currency precision is rejected rather than
 *      silently rounded away in the database
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:billing-defect;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuditServiceImpl.class, BillingCalculator.class, EstimateServiceImpl.class, InvoiceServiceImpl.class, PaymentServiceImpl.class })
class BillingDefectRegressionTest {

    @Autowired InvoiceServiceImpl     invoiceService;
    @Autowired EstimateServiceImpl    estimateService;
    @Autowired PaymentServiceImpl     paymentService;
    @Autowired InvoiceRepository      invoiceRepository;
    @Autowired InvoiceItemRepository  invoiceItemRepository;
    @Autowired EstimateRepository     estimateRepository;
    @Autowired PaymentRepository      paymentRepository;
    @Autowired JobCardRepository      jobCardRepository;

    // ── Fixtures ─────────────────────────────────────────────────────────

    private JobCard givenJobCard() {
        JobCard jc = new JobCard();
        jc.setJobCardNumber("JC-DEFECT-1");
        jc.setServiceType("BRAKE_SERVICE");
        jc.setStatus("IN_REPAIR");
        return jobCardRepository.save(jc);
    }

    private InvoiceItemRequestDTO invLine(String d, int q, String p) {
        InvoiceItemRequestDTO dto = new InvoiceItemRequestDTO();
        dto.setDescription(d);
        dto.setQuantity(q);
        dto.setUnitPrice(new BigDecimal(p));
        return dto;
    }

    private Long invoiceWithPartialPayment(String mode) {
        JobCard jc = givenJobCard();
        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));   // total 1180.00
        Long invoiceId = invoiceService.create(req).getId();

        PaymentRequestDTO payment = new PaymentRequestDTO();
        payment.setInvoiceId(invoiceId);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setMode(mode);
        paymentService.create(payment);
        return invoiceId;
    }

    // ── D1: deleting an invoice that has payments ────────────────────────

    /**
     * The original defect: {@code payments.invoice_id} is a restrictive foreign
     * key, so deleting an invoice that had a payment raised
     * {@code DataIntegrityViolationException} at flush time and surfaced as an
     * opaque HTTP 500.
     *
     * <p>The requirement is that the invoice survives AND the payment history
     * survives. Asserting both is the point — a fix that nulled the FK or
     * cascaded the delete would keep the invoice gone and silently destroy a
     * customer's proof of payment.
     */
    @Test
    @DisplayName("D1 — deleting an invoice that has payments is refused, and both records survive")
    void d1_deleteInvoiceWithPayment_isRefused() {
        Long invoiceId = invoiceWithPartialPayment("UPI");

        assertThatThrownBy(() -> invoiceService.delete(invoiceId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("has recorded payments");

        // The invoice is still there...
        Invoice stillThere = invoiceRepository.findById(invoiceId).orElseThrow();
        assertThat(stillThere.getStatus())
                .isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PARTIALLY_PAID);
        // ...with its lines...
        assertThat(invoiceItemRepository.findByInvoiceIdOrderByIdAsc(invoiceId)).hasSize(1);
        // ...and, critically, the payment is untouched.
        List<Payment> payments = paymentRepository.findByInvoiceIdOrderByIdAsc(invoiceId);
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getAmount()).isEqualByComparingTo("100.00");
        assertThat(payments.get(0).getInvoice().getId()).isEqualTo(invoiceId);
    }

    /**
     * The guard must not depend on the payment being {@code SUCCESS}. A payment
     * that was voided or left pending is still a row the foreign key protects,
     * and it is still part of the invoice's history.
     */
    @Test
    @DisplayName("D1b — a non-settled payment also blocks deletion")
    void d1b_voidedPaymentAlsoBlocksDeletion() {
        Long invoiceId = invoiceWithPartialPayment("CASH");

        Payment payment = paymentRepository.findByInvoiceIdOrderByIdAsc(invoiceId).get(0);
        payment.setStatus("VOID");
        paymentRepository.save(payment);

        assertThatThrownBy(() -> invoiceService.delete(invoiceId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("has recorded payments");

        assertThat(invoiceRepository.findById(invoiceId)).isPresent();
    }

    /** Deleting the payment first makes the invoice deletable again. */
    @Test
    @DisplayName("D1c — once the payments are gone the invoice can be deleted")
    void d1c_deleteAfterPaymentsRemoved_succeeds() {
        Long invoiceId = invoiceWithPartialPayment("CARD");

        paymentRepository.deleteAll(paymentRepository.findByInvoiceIdOrderByIdAsc(invoiceId));
        paymentRepository.flush();

        invoiceService.delete(invoiceId);

        assertThat(invoiceRepository.findById(invoiceId)).isEmpty();
        assertThat(invoiceItemRepository.findByInvoiceIdOrderByIdAsc(invoiceId)).isEmpty();
    }

    // ── D2: deleting a converted estimate ────────────────────────────────

    @Test
    @DisplayName("D2 — an estimate already converted to an invoice cannot be deleted")
    void d2_deleteConvertedEstimate_isRefused() {
        JobCard jc = givenJobCard();

        EstimateRequestDTO req = new EstimateRequestDTO();
        req.setJobCardId(jc.getId());
        EstimateItemRequestDTO line = new EstimateItemRequestDTO();
        line.setDescription("Brake pads");
        line.setQuantity(2);
        line.setUnitPrice(new BigDecimal("500.00"));
        req.setItems(List.of(line));
        Long estimateId = estimateService.create(req).getId();

        invoiceService.convertFromEstimate(estimateId);

        assertThatThrownBy(() -> estimateService.delete(estimateId))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(estimateRepository.findById(estimateId)).isPresent();
        assertThat(invoiceRepository.findByEstimateId(estimateId)).isPresent();
    }

    // ── D3: GST rate precision ────────────────────────────────────────────

    /**
     * {@code gst_rate} is {@code DECIMAL(...,2)}. Accepting 18.999 would compute
     * GST from 18.999 in memory while the database stored 19.00, so the API's
     * answer and the persisted document would disagree. The rate is rejected
     * instead.
     */
    @Test
    @DisplayName("D3 — a gstRate finer than currency precision is rejected")
    void d3_gstRateBeyondMoneyScale_isRejected() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal("18.999"));
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));

        assertThatThrownBy(() -> invoiceService.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("more than 2 decimal places");

        assertThat(invoiceRepository.findAll()).isEmpty();
    }

    /** The boundary itself is legal — 18.50 is representable at DECIMAL(...,2). */
    @Test
    @DisplayName("D3b — a gstRate at exactly currency precision is accepted")
    void d3b_gstRateAtMoneyScale_isAccepted() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal("18.50"));
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));   // subtotal 1000.00

        var response = invoiceService.create(req);

        Invoice stored = invoiceRepository.findById(response.getId()).orElseThrow();
        assertThat(stored.getGstRate()).isEqualByComparingTo("18.50");
        assertThat(stored.getGst()).isEqualByComparingTo("185.00");   // 1000 x 18.50%
        assertThat(stored.getTotal()).isEqualByComparingTo("1185.00");
    }

    /** Trailing zeros are not excess precision and must not be rejected. */
    @Test
    @DisplayName("D3c — a trailing-zero gstRate such as 18.00 is accepted")
    void d3c_trailingZeroGstRate_isAccepted() {
        JobCard jc = givenJobCard();

        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setGstRate(new BigDecimal("18.00"));
        req.setItems(List.of(invLine("Brake pads", 2, "500.00")));

        var response = invoiceService.create(req);

        Invoice stored = invoiceRepository.findById(response.getId()).orElseThrow();
        assertThat(stored.getGstRate()).isEqualByComparingTo("18");
        assertThat(stored.getGst()).isEqualByComparingTo("180.00");
    }
}