package com.autoservicehub.service;

import com.autoservicehub.dto.InvoiceItemRequestDTO;
import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.InvoiceItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.InvoiceItemRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PaymentRepository;
import com.autoservicehub.service.impl.InvoiceServiceImpl;
import com.autoservicehub.util.BillingCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvoiceServiceImpl} — relationships, server-side
 * calculation, GST and the outstanding amount.
 * Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * I1  create with items        → job card linked, GST/total calculated server-side
 * I2  client-supplied totals   → ignored, server values win
 * I3  discount reduces the GST base
 * I4  unknown job card         → ResourceNotFoundException
 * I5  zero quantity            → BusinessRuleException
 * I6  negative unit price      → BusinessRuleException
 * I7  discount above subtotal  → BusinessRuleException
 * I8  outstanding with no payments = total
 * I9  outstanding reduces with successful payments
 * I10 non-SUCCESS payments are ignored
 * I11 over-payment never yields a negative outstanding
 * I12 a PAID invoice cannot be modified or deleted
 * I13 delete removes the invoice's items
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvoiceServiceImplTest {

    @Mock InvoiceRepository     repository;
    @Mock InvoiceItemRepository itemRepository;
    @Mock JobCardRepository     jobCardRepository;
    @Mock PaymentRepository     paymentRepository;

    /** Real calculator, so the money rules under test are the production ones. */
    @Spy
    private BillingCalculator calculator = new BillingCalculator(new BigDecimal("18"));

    @InjectMocks
    InvoiceServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private JobCard jobCard(Long id) {
        Customer c = new Customer();
        c.setId(1L);
        c.setName("Priya Sharma");
        Vehicle v = new Vehicle();
        v.setId(2L);
        v.setRegistrationNo("MH-12-AB-1234");
        v.setModel("Swift VXI");

        JobCard jc = new JobCard();
        jc.setId(id);
        jc.setJobCardNumber("JC-20260930120000");
        jc.setCustomer(c);
        jc.setVehicle(v);
        return jc;
    }

    private InvoiceItemRequestDTO item(String description, int qty, String unitPrice) {
        InvoiceItemRequestDTO dto = new InvoiceItemRequestDTO();
        dto.setDescription(description);
        dto.setQuantity(qty);
        dto.setUnitPrice(new BigDecimal(unitPrice));
        return dto;
    }

    private InvoiceRequestDTO request() {
        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(55L);
        return req;
    }

    /** Assigns ids and makes written lines readable back, as the DB would. */
    private void givenPersisted() {
        when(repository.save(any(Invoice.class))).thenAnswer(inv -> {
            Invoice i = inv.getArgument(0);
            if (i.getId() == null) {
                i.setId(42L);
            }
            return i;
        });
        List<InvoiceItem> written = new ArrayList<>();
        when(itemRepository.save(any(InvoiceItem.class))).thenAnswer(inv -> {
            InvoiceItem saved = inv.getArgument(0);
            saved.setId((long) (written.size() + 1));
            written.add(saved);
            return saved;
        });
        when(itemRepository.findByInvoiceIdOrderByIdAsc(42L))
                .thenAnswer(inv -> new ArrayList<>(written));
    }

    // ── I1 / I2: creation and server-side calculation ─────────────────────

    @Test
    @DisplayName("I1 — create links the job card and calculates GST and total server-side")
    void i1_create_linksJobCardAndCalculates() {
        InvoiceRequestDTO req = request();
        req.setItems(List.of(
                item("Brake pads", 2, "500.00"),
                item("Labour", 2, "150.00")));

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();
        givenNoPayments();

        InvoiceResponseDTO response = service.create(req);

        assertThat(response.getJobCardId()).isEqualTo(55L);
        assertThat(response.getCustomerName()).isEqualTo("Priya Sharma");
        // 2×500 + 2×150 = 1300
        assertThat(response.getSubtotal()).isEqualByComparingTo("1300.00");
        assertThat(response.getGst()).isEqualByComparingTo("234.00");    // 1300 × 18%
        assertThat(response.getTotal()).isEqualByComparingTo("1534.00");
        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getItems()).hasSize(2);
    }

    @Test
    @DisplayName("I2 — client-supplied subtotal/GST/total are ignored and recalculated")
    void i2_clientTotalsAreIgnored() {
        InvoiceRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 2, "500.00")));
        // A tampered client tries to under-bill itself.
        req.setSubtotal(BigDecimal.ONE);
        req.setGst(new BigDecimal("0.01"));
        req.setTotal(BigDecimal.ONE);

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();
        givenNoPayments();

        InvoiceResponseDTO response = service.create(req);

        assertThat(response.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(response.getGst()).isEqualByComparingTo("180.00");
        assertThat(response.getTotal()).isEqualByComparingTo("1180.00");
    }

    @Test
    @DisplayName("I3 — the discount reduces the GST base as well as the total")
    void i3_discountReducesGstBase() {
        InvoiceRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 1, "1000.00")));
        req.setDiscount(new BigDecimal("200.00"));

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();
        givenNoPayments();

        InvoiceResponseDTO response = service.create(req);

        assertThat(response.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(response.getDiscount()).isEqualByComparingTo("200.00");
        assertThat(response.getGst()).isEqualByComparingTo("144.00");    // 800 × 18%
        assertThat(response.getTotal()).isEqualByComparingTo("944.00");
    }

    // ── I4: unknown job card ──────────────────────────────────────────────

    @Test
    @DisplayName("I4 — unknown jobCardId → ResourceNotFoundException, nothing persisted")
    void i4_unknownJobCard_throws() {
        InvoiceRequestDTO req = request();
        req.setJobCardId(99L);
        req.setItems(List.of(item("Any", 1, "10.00")));

        when(jobCardRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobCard not found: 99");

        verify(repository, never()).save(any(Invoice.class));
    }

    // ── I5 / I6 / I7: invalid input ───────────────────────────────────────

    @Test
    @DisplayName("I5 — zero quantity → BusinessRuleException, invoice not saved")
    void i5_zeroQuantity_throws() {
        InvoiceRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 0, "500.00")));

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("quantity must be greater than 0");

        verify(repository, never()).save(any(Invoice.class));
    }

    @Test
    @DisplayName("I6 — negative unit price → BusinessRuleException")
    void i6_negativeUnitPrice_throws() {
        InvoiceRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 1, "-500.00")));

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("unitPrice must not be negative");
    }

    @Test
    @DisplayName("I7 — discount greater than the calculated subtotal → BusinessRuleException")
    void i7_discountAboveSubtotal_throws() {
        InvoiceRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 1, "100.00")));
        req.setDiscount(new BigDecimal("500.00"));

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot exceed the subtotal");
    }

    /** No successful payments recorded against the invoice. */
    private void givenNoPayments() {
        when(paymentRepository.sumAmountByInvoiceIdAndStatus(anyLong(), any()))
                .thenReturn(BigDecimal.ZERO);
    }

    // ── I8 / I9 / I10 / I11: outstanding amount ───────────────────────────

    @Test
    @DisplayName("I8 — with no payments the outstanding amount equals the total")
    void i8_outstandingWithNoPayments_equalsTotal() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setTotal(new BigDecimal("1534.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(paymentRepository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(BigDecimal.ZERO);

        assertThat(service.getOutstandingAmount(42L)).isEqualByComparingTo("1534.00");
    }

    @Test
    @DisplayName("I9 — successful payments reduce the outstanding amount")
    void i9_outstandingReducedBySuccessfulPayments() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setTotal(new BigDecimal("1534.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(paymentRepository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(new BigDecimal("534.00"));

        assertThat(service.getOutstandingAmount(42L)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("I10 — only SUCCESS payments are counted (the query filters by status)")
    void i10_onlySuccessfulPaymentsCounted() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setTotal(new BigDecimal("1000.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        // PENDING/FAILED payments must not appear in the SUCCESS sum.
        when(paymentRepository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(BigDecimal.ZERO);

        assertThat(service.getOutstandingAmount(42L)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("I11 — an over-payment never reports a negative outstanding amount")
    void i11_overPayment_floorsAtZero() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setTotal(new BigDecimal("1000.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(paymentRepository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(new BigDecimal("1500.00"));

        assertThat(service.getOutstandingAmount(42L)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("I11b — the invoice response reports amountPaid and outstanding together")
    void i11b_responseCarriesPaidAndOutstanding() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setJobCard(jobCard(55L));
        invoice.setTotal(new BigDecimal("1180.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(itemRepository.findByInvoiceIdOrderByIdAsc(42L)).thenReturn(List.of());
        when(paymentRepository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(new BigDecimal("180.00"));

        InvoiceResponseDTO response = service.getById(42L);

        assertThat(response.getAmountPaid()).isEqualByComparingTo("180.00");
        assertThat(response.getOutstandingAmount()).isEqualByComparingTo("1000.00");
    }

    // ── I12 / I13: settled invoice and deletion ───────────────────────────

    @Test
    @DisplayName("I12 — a PAID invoice cannot be modified or deleted → BusinessRuleException")
    void i12_paidInvoice_isFrozen() {
        Invoice paid = new Invoice();
        paid.setId(42L);
        paid.setStatus("PAID");

        when(repository.findById(42L)).thenReturn(Optional.of(paid));

        InvoiceRequestDTO req = request();
        req.setItems(List.of(item("Rewrite", 1, "1.00")));

        assertThatThrownBy(() -> service.update(42L, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already PAID");

        assertThatThrownBy(() -> service.delete(42L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already PAID");
    }

    @Test
    @DisplayName("I13 — delete removes the invoice's items with it")
    void i13_delete_removesItems() {
        Invoice existing = new Invoice();
        existing.setId(42L);
        existing.setStatus("PENDING");

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(itemRepository.findByInvoiceIdOrderByIdAsc(42L)).thenReturn(List.of(new InvoiceItem()));

        service.delete(42L);

        verify(itemRepository).deleteAll(any());
        verify(repository).deleteById(42L);
    }
}
