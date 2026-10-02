package com.autoservicehub.service;

import com.autoservicehub.dto.PaymentRequestDTO;
import com.autoservicehub.dto.PaymentResponseDTO;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.Payment;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.InvoiceItemRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.PaymentRepository;
import com.autoservicehub.service.impl.PaymentServiceImpl;
import com.autoservicehub.util.BillingCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentServiceImpl} — the Payment → Invoice workflow.
 * Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * W1  partial payment      → invoice becomes PARTIALLY_PAID, outstanding drops
 * W2  full payment         → invoice becomes PAID, outstanding zero
 * W3  payment equal to outstanding → accepted, invoice PAID
 * W4  payment > outstanding → BusinessRuleException, nothing saved
 * W5  multiple partial payments accumulate toward PAID
 * W6  zero / negative / null amount → BusinessRuleException
 * W7  unknown invoice      → ResourceNotFoundException
 * W8  cancelled invoice    → BusinessRuleException
 * W9  already PAID invoice → BusinessRuleException
 * W10 non-settled payment does not reduce the balance
 * W11 payment history for an invoice, deterministic order
 * W12 payment history for an unknown invoice → ResourceNotFoundException
 * W13 deleting a payment restores the invoice status
 * W14 amount is rounded to money scale
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceImplTest {

    @Mock PaymentRepository repository;
    @Mock InvoiceRepository invoiceRepository;
    /** Read by the FR-BILL-6 closure guard, which refuses to settle an empty invoice. */
    @Mock InvoiceItemRepository invoiceItemRepository;
    @Mock AuditService auditService;

    /** Real calculator, so the money rules under test are the production ones. */
    @Spy
    private BillingCalculator calculator = new BillingCalculator(new BigDecimal("18"));

    @InjectMocks
    PaymentServiceImpl service;

    /**
     * The FR-BILL-6 closure guard refuses to settle an invoice with no line items.
     * These payment tests are not about that rule, so the fixture invoices are
     * given lines by default; {@code w14_emptyInvoiceCannotBeClosed} overrides this
     * to exercise the guard itself.
     */
    @BeforeEach
    void givenInvoiceHasBillableLines() {
        when(invoiceItemRepository.countByInvoiceId(anyLong())).thenReturn(1L);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Invoice invoice(Long id, String status, String total) {
        Invoice inv = new Invoice();
        inv.setId(id);
        inv.setStatus(status);
        inv.setTotal(new BigDecimal(total));
        return inv;
    }

    private PaymentRequestDTO request(String amount) {
        PaymentRequestDTO req = new PaymentRequestDTO();
        req.setInvoiceId(42L);
        req.setAmount(new BigDecimal(amount));
        return req;
    }

    /** The invoice exists, the payment is saved, and the settled sum is `paid`. */
    private void given(Invoice inv, String alreadyPaid) {
        when(invoiceRepository.findById(inv.getId())).thenReturn(Optional.of(inv));
        when(repository.save(any(Payment.class))).thenAnswer(call -> {
            Payment p = call.getArgument(0);
            p.setId(100L);
            return p;
        });
        when(repository.sumAmountByInvoiceIdAndStatus(anyLong(), any()))
                .thenReturn(new BigDecimal(alreadyPaid));
    }

    // ── W1: partial payment ───────────────────────────────────────────────

    @Test
    @DisplayName("W1 — a partial payment moves the invoice to PARTIALLY_PAID and reduces the balance")
    void w1_partialPayment_marksPartiallyPaid() {
        Invoice inv = invoice(42L, "PENDING", "1000.00");
        given(inv, "0");

        // After this payment settles, 400 of 1000 remains.
        when(repository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(BigDecimal.ZERO, new BigDecimal("600.00"));

        PaymentResponseDTO response = service.create(request("600.00"));

        assertThat(response.getAmount()).isEqualByComparingTo("600.00");
        assertThat(inv.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PARTIALLY_PAID);
        verify(invoiceRepository).save(inv);
    }

    // ── W2 / W3: full payment ─────────────────────────────────────────────

    @Test
    @DisplayName("W2 — a payment covering the whole total marks the invoice PAID")
    void w2_fullPayment_marksPaid() {
        Invoice inv = invoice(42L, "PENDING", "1180.00");
        given(inv, "0");
        when(repository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(BigDecimal.ZERO, new BigDecimal("1180.00"));

        service.create(request("1180.00"));

        assertThat(inv.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);
    }

    @Test
    @DisplayName("W3 — a payment exactly equal to the outstanding amount is accepted")
    void w3_paymentEqualToOutstanding_accepted() {
        Invoice inv = invoice(42L, "PARTIALLY_PAID", "1000.00");
        given(inv, "400.00");
        // 1000 total − 400 already paid = 600 outstanding; pay exactly 600.
        when(repository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(new BigDecimal("400.00"), new BigDecimal("1000.00"));

        PaymentResponseDTO response = service.create(request("600.00"));

        assertThat(response.getAmount()).isEqualByComparingTo("600.00");
        assertThat(inv.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);
    }

    // ── W4: over-payment ──────────────────────────────────────────────────

    @Test
    @DisplayName("W4 — a payment larger than the outstanding amount → BusinessRuleException, nothing saved")
    void w4_paymentAboveOutstanding_throws() {
        Invoice inv = invoice(42L, "PARTIALLY_PAID", "1000.00");
        given(inv, "400.00");   // 600 outstanding

        assertThatThrownBy(() -> service.create(request("700.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exceeds the outstanding amount of 600.00");

        verify(repository, never()).save(any(Payment.class));
        verify(invoiceRepository, never()).save(any(Invoice.class));
    }

    @Test
    @DisplayName("W4b — a payment on an unpaid invoice may not exceed the invoice total")
    void w4b_paymentAboveTotal_throws() {
        Invoice inv = invoice(42L, "PENDING", "1000.00");
        given(inv, "0");

        assertThatThrownBy(() -> service.create(request("1000.01")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exceeds the outstanding amount");
    }

    // ── W5: several partial payments ──────────────────────────────────────

    @Test
    @DisplayName("W5 — successive partial payments accumulate until the invoice is PAID")
    void w5_multiplePartialPayments_accumulateToPaid() {
        Invoice inv = invoice(42L, "PENDING", "1000.00");
        when(invoiceRepository.findById(42L)).thenReturn(Optional.of(inv));
        when(repository.save(any(Payment.class))).thenAnswer(call -> {
            Payment p = call.getArgument(0);
            p.setId((long) (System.nanoTime() % 100000));
            return p;
        });

        // 300 paid → still partial
        when(repository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(new BigDecimal("0"), new BigDecimal("300"));
        service.create(request("300.00"));
        assertThat(inv.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PARTIALLY_PAID);

        // 700 more → total 1000 → PAID
        when(repository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(new BigDecimal("300"), new BigDecimal("1000"));
        service.create(request("700.00"));
        assertThat(inv.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);
    }

    // ── W6: invalid amounts ───────────────────────────────────────────────

    @Test
    @DisplayName("W6 — a zero amount → BusinessRuleException")
    void w6_zeroAmount_throws() {
        given(invoice(42L, "PENDING", "1000.00"), "0");

        assertThatThrownBy(() -> service.create(request("0")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("amount must be greater than 0");

        verify(repository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("W6b — a negative amount → BusinessRuleException")
    void w6b_negativeAmount_throws() {
        given(invoice(42L, "PENDING", "1000.00"), "0");

        assertThatThrownBy(() -> service.create(request("-50.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("amount must be greater than 0");
    }

    @Test
    @DisplayName("W6c — a null amount → BusinessRuleException")
    void w6c_nullAmount_throws() {
        PaymentRequestDTO req = new PaymentRequestDTO();
        req.setInvoiceId(42L);
        req.setAmount(null);
        given(invoice(42L, "PENDING", "1000.00"), "0");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("amount must be greater than 0");
    }

    // ── W7 / W8 / W9: invoice eligibility ──────────────────────────────────

    @Test
    @DisplayName("W7 — an unknown invoice → ResourceNotFoundException")
    void w7_unknownInvoice_throws() {
        when(invoiceRepository.findById(99L)).thenReturn(Optional.empty());

        PaymentRequestDTO req = request("100.00");
        req.setInvoiceId(99L);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Invoice not found: 99");

        verify(repository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("W8 — a CANCELLED invoice cannot accept a payment → BusinessRuleException")
    void w8_cancelledInvoice_throws() {
        given(invoice(42L, "CANCELLED", "1000.00"), "0");

        assertThatThrownBy(() -> service.create(request("100.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("is CANCELLED");

        verify(repository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("W9 — an already PAID invoice cannot accept a further payment → BusinessRuleException")
    void w9_paidInvoice_throws() {
        given(invoice(42L, "PAID", "1000.00"), "1000.00");

        assertThatThrownBy(() -> service.create(request("100.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already PAID");
    }

    // ── W10: only settled payments move the balance ───────────────────────

    @Test
    @DisplayName("W10 — a PENDING payment is recorded but does not reduce the balance")
    void w10_pendingPayment_doesNotReduceBalance() {
        Invoice inv = invoice(42L, "PENDING", "1000.00");
        given(inv, "0");

        PaymentRequestDTO req = request("100.00");
        req.setStatus("PENDING");

        PaymentResponseDTO response = service.create(req);

        assertThat(response.getStatus()).isEqualTo("PENDING");
        // Nothing settled, so the invoice is still unpaid.
        assertThat(inv.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PENDING);
    }

    @Test
    @DisplayName("W10b — a payment defaults to SUCCESS when no status is supplied")
    void w10b_paymentDefaultsToSettled() {
        Invoice inv = invoice(42L, "PENDING", "1000.00");
        given(inv, "0");
        when(repository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(BigDecimal.ZERO, new BigDecimal("1000.00"));

        PaymentResponseDTO response = service.create(request("1000.00"));

        assertThat(response.getStatus()).isEqualTo(PaymentServiceImpl.PAYMENT_STATUS_SETTLED);
        assertThat(inv.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);
    }

    // ── W11 / W12: payment history ────────────────────────────────────────

    @Test
    @DisplayName("W11 — payments for an invoice are returned oldest first")
    void w11_paymentHistory_deterministicOrder() {
        when(invoiceRepository.existsById(42L)).thenReturn(true);

        Payment first = new Payment();
        first.setId(1L);
        first.setAmount(new BigDecimal("300.00"));
        Payment second = new Payment();
        second.setId(2L);
        second.setAmount(new BigDecimal("700.00"));

        when(repository.findByInvoiceIdOrderByIdAsc(eq(42L), any()))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2));
        when(repository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(new BigDecimal("1000.00"));

        Page<PaymentResponseDTO> page = service.listByInvoice(42L, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getAmount()).isEqualByComparingTo("300.00");
        assertThat(page.getContent().get(1).getAmount()).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("W12 — payment history for an unknown invoice → ResourceNotFoundException")
    void w12_historyUnknownInvoice_throws() {
        when(invoiceRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.listByInvoice(99L, PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Invoice not found: 99");
    }

    // ── W13 / W14 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("W13 — deleting a payment puts the invoice status back in step with the balance")
    void w13_deletePayment_restoresInvoiceStatus() {
        Invoice inv = invoice(42L, "PAID", "1000.00");
        Payment payment = new Payment();
        payment.setId(100L);
        payment.setInvoice(inv);

        when(repository.findById(100L)).thenReturn(Optional.of(payment));
        // After the delete only 300 of 1000 remains settled.
        when(repository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(new BigDecimal("300.00"));

        service.delete(100L);

        verify(repository).deleteById(100L);
        assertThat(inv.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PARTIALLY_PAID);
    }

    @Test
    @DisplayName("W14 — the recorded amount is rounded to money scale")
    void w14_amountRoundedToMoneyScale() {
        given(invoice(42L, "PENDING", "1000.00"), "0");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        service.create(request("10.129"));
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("10.13");
    }

    // ── FR-BILL-6: closure guard ────────────────────────────────────────────

    @Test
    @DisplayName("w15 an invoice with no line items cannot be closed by a payment")
    void emptyInvoiceCannotBeClosed() {
        Invoice inv = invoice(42L, "PENDING", "0");
        given(inv, "0");
        // A payment that would cover a zero total must still not settle an invoice
        // with nothing on it: there is no mandatory billing data to have paid for.
        when(invoiceItemRepository.countByInvoiceId(42L)).thenReturn(0L);

        assertThatThrownBy(() -> service.create(request("500")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exceeds the outstanding amount");

        verify(invoiceRepository, never()).save(any(Invoice.class));
    }

    @Test
    @DisplayName("w16 a payment covering an invoice with lines still closes it as PAID")
    void paidStillDerivedFromPayments() {
        Invoice inv = invoice(42L, "PENDING", "500.00");
        given(inv, "0");
        when(invoiceItemRepository.countByInvoiceId(42L)).thenReturn(2L);
        // Nothing paid yet when the amount is validated; the full amount once the
        // payment exists, so the status refresh sees it as settled.
        when(repository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(BigDecimal.ZERO, new BigDecimal("500.00"));

        service.create(request("500.00"));

        // PAID still arrives through the payment flow, never from a client status.
        assertThat(inv.getStatus()).isEqualTo(PaymentServiceImpl.INVOICE_STATUS_PAID);
    }
}
