package com.autoservicehub.service;

import com.autoservicehub.dto.InvoiceItemRequestDTO;
import com.autoservicehub.dto.InvoiceLabourItemRequestDTO;
import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import com.autoservicehub.entity.BillingItemCategory;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.InvoiceItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.InvoiceItemRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.PaymentRepository;
import com.autoservicehub.service.impl.InvoiceServiceImpl;
import com.autoservicehub.util.BillingCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    @Mock JobTaskRepository     jobTaskRepository;
    @Mock PaymentRepository     paymentRepository;
    @Mock AuditService auditService;

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

    /** A standalone vehicle, for the cross-vehicle guard. */
    private Vehicle vehicle(Long id, String registrationNo) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setRegistrationNo(registrationNo);
        v.setModel("Swift VXI");
        return v;
    }

    private InvoiceLabourItemRequestDTO labourRequest(Long jobTaskId) {
        InvoiceLabourItemRequestDTO req = new InvoiceLabourItemRequestDTO();
        req.setJobTaskId(jobTaskId);
        return req;
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

    // ── ILB: labour-to-invoice integration (FR-BILL-2) ───────────────────
    //
    // The rules are proved against a real database in InvoiceLabourBillingTest.
    // These cover the paths H2 cannot set up: notably the vehicle mismatch,
    // because @DataJpaTest cannot create the VEHICLES table at all — Vehicle
    // declares a column named `year`, an H2 reserved word, which is a
    // pre-existing limitation this task must not fix by touching Vehicle.

    @Test
    @DisplayName("ILB1 — addLabourItem generates a LABOUR line from the task")
    void ilb1_addLabour_generatesLineFromTask() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setJobCard(jobCard(55L));
        invoice.setStatus("PENDING");
        invoice.setDiscount(BigDecimal.ZERO);

        JobTask task = new JobTask();
        task.setId(7L);
        task.setJobCard(jobCard(55L));
        task.setDescription("Replace front brake pads");
        task.setLabourCost(new BigDecimal("500.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(jobTaskRepository.findById(7L)).thenReturn(Optional.of(task));
        givenPersisted();

        var response = service.addLabourItem(42L, labourRequest(7L));

        assertThat(response.getSubtotal()).isEqualByComparingTo("500.00");
        assertThat(response.getItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getDescription()).isEqualTo("Replace front brake pads");
                    assertThat(item.getQuantity()).isEqualTo(1);
                    assertThat(item.getUnitPrice()).isEqualByComparingTo("500.00");
                    assertThat(item.getLineAmount()).isEqualByComparingTo("500.00");
                    assertThat(item.getCategory()).isEqualTo(BillingItemCategory.LABOUR);
                    assertThat(item.getJobTaskId()).isEqualTo(7L);
                });
    }

    @Test
    @DisplayName("ILB2 — a task from another job card is refused")
    void ilb2_wrongJobCard_refused() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setJobCard(jobCard(55L));
        invoice.setStatus("PENDING");

        JobTask foreign = new JobTask();
        foreign.setId(7L);
        foreign.setJobCard(jobCard(999L));
        foreign.setLabourCost(new BigDecimal("900.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(jobTaskRepository.findById(7L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.addLabourItem(42L, labourRequest(7L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be billed on invoice");

        verify(itemRepository, never()).save(any(InvoiceItem.class));
    }

    /**
     * A task belonging to a different vehicle belongs to a different customer.
     * The job-card comparison alone would not catch this if the same job card
     * id were ever paired with another vehicle, so the vehicle is checked too.
     */
    @Test
    @DisplayName("ILB3 — a task belonging to another vehicle is refused")
    void ilb3_differentVehicle_refused() {
        JobCard invoicedJob = jobCard(55L);
        invoicedJob.setVehicle(vehicle(2L, "MH-12-AB-1234"));

        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setJobCard(invoicedJob);
        invoice.setStatus("PENDING");

        JobCard otherCar = jobCard(55L);
        otherCar.setVehicle(vehicle(3L, "MH-12-XY-9999"));
        JobTask task = new JobTask();
        task.setId(7L);
        task.setJobCard(otherCar);
        task.setLabourCost(new BigDecimal("500.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(jobTaskRepository.findById(7L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.addLabourItem(42L, labourRequest(7L)))
                .isInstanceOf(BusinessRuleException.class);

        verify(itemRepository, never()).save(any(InvoiceItem.class));
    }

    @Test
    @DisplayName("ILB4 — a task already billed on this invoice is refused")
    void ilb4_duplicateTask_refused() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setJobCard(jobCard(55L));
        invoice.setStatus("PENDING");

        JobTask task = new JobTask();
        task.setId(7L);
        task.setJobCard(jobCard(55L));
        task.setLabourCost(new BigDecimal("500.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(jobTaskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(itemRepository.existsByInvoiceIdAndJobTaskId(42L, 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.addLabourItem(42L, labourRequest(7L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already been billed");

        verify(itemRepository, never()).save(any(InvoiceItem.class));
    }

    @Test
    @DisplayName("ILB5 — labour cannot be added to a PAID invoice")
    void ilb5_paidInvoice_refused() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setJobCard(jobCard(55L));
        invoice.setStatus("PAID");

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.addLabourItem(42L, labourRequest(7L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already PAID");

        verify(itemRepository, never()).save(any(InvoiceItem.class));
    }

    @Test
    @DisplayName("ILB6 — an unknown task is refused")
    void ilb6_unknownTask_refused() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setJobCard(jobCard(55L));
        invoice.setStatus("PENDING");

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(jobTaskRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addLabourItem(42L, labourRequest(7L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobTask not found: 7");
    }

    @Test
    @DisplayName("ILB7 — a negative labour cost on a task is refused")
    void ilb7_negativeLabour_refused() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setJobCard(jobCard(55L));
        invoice.setStatus("PENDING");

        JobTask task = new JobTask();
        task.setId(7L);
        task.setJobCard(jobCard(55L));
        task.setLabourCost(new BigDecimal("-250.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(jobTaskRepository.findById(7L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.addLabourItem(42L, labourRequest(7L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must not be negative");

        verify(itemRepository, never()).save(any(InvoiceItem.class));
    }

    /**
     * The double-count guard on update.
     *
     * <p>{@code replaceItems} deletes every line and rewrites the submitted
     * ones. Task-sourced labour is not in the payload — a client cannot express
     * it — so without being carried across it would be silently dropped whenever
     * anyone edited the invoice, and the caller would have no way to restore it.
     * Carrying it keeps the invoice at parts + exactly the labour already billed.
     */
    @Test
    @DisplayName("ILB8 — updating an invoice carries task labour across instead of dropping it")
    void ilb8_update_carriesTaskLabourAcross() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setJobCard(jobCard(55L));
        invoice.setStatus("PENDING");
        invoice.setDiscount(BigDecimal.ZERO);

        JobTask task = new JobTask();
        task.setId(7L);
        task.setJobCard(jobCard(55L));
        task.setDescription("Replace front brake pads");

        InvoiceItem carried = new InvoiceItem();
        carried.setId(90L);
        carried.setInvoice(invoice);
        carried.setJobTask(task);
        carried.setDescription("Replace front brake pads");
        carried.setQuantity(1);
        carried.setUnitPrice(new BigDecimal("500.00"));
        carried.setLineAmount(new BigDecimal("500.00"));
        carried.setCategory(BillingItemCategory.LABOUR);

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        when(itemRepository.findByInvoiceIdAndJobTaskIdIsNotNullOrderByIdAsc(42L))
                .thenReturn(List.of(carried));
        givenPersisted();

        InvoiceRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 2, "500.00")));
        service.update(42L, req);

        // The submitted part and the carried labour were both written.
        ArgumentCaptor<InvoiceItem> captor = ArgumentCaptor.forClass(InvoiceItem.class);
        verify(itemRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        assertThat(captor.getAllValues()).anySatisfy(i -> {
            assertThat(i.getJobTask()).isSameAs(task);
            assertThat(i.getCategory()).isEqualTo(BillingItemCategory.LABOUR);
            // Recalculated on the way through, not copied from the old row.
            assertThat(i.getLineAmount()).isEqualByComparingTo("500.00");
        });
    }

    @Test
    @DisplayName("ILB9 — an existing line with no category is reported as PART")
    void ilb9_legacyNullCategory_reportedAsPart() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setJobCard(jobCard(55L));
        invoice.setStatus("PENDING");

        // A row written before the category column existed: the field is null.
        InvoiceItem legacy = new InvoiceItem();
        legacy.setId(1L);
        legacy.setDescription("Brake pads");
        legacy.setQuantity(2);
        legacy.setUnitPrice(new BigDecimal("500.00"));
        legacy.setLineAmount(new BigDecimal("1000.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(invoice));
        when(itemRepository.findByInvoiceIdOrderByIdAsc(42L)).thenReturn(List.of(legacy));
        when(paymentRepository.sumAmountByInvoiceIdAndStatus(42L, "SUCCESS"))
                .thenReturn(BigDecimal.ZERO);

        assertThat(service.getById(42L).getItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getCategory()).isEqualTo(BillingItemCategory.PART);
                    assertThat(item.getJobTaskId()).isNull();
                });
    }

    // ── FR-BILL-6: closure guards ───────────────────────────────────────────

    @Test
    @DisplayName("I14 a client cannot mark an invoice PAID directly")
    void directPaidIsRejected() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();

        InvoiceRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 1, "500.00")));
        req.setStatus("PAID");

        // PAID is derived from recorded payments; accepting it from a client would
        // let an invoice be "settled" with nothing paid against it.
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("derived from recorded payments");
    }

    @Test
    @DisplayName("I15 a client cannot mark an invoice PARTIALLY_PAID either")
    void directPartiallyPaidIsRejected() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();

        InvoiceRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 1, "500.00")));
        req.setStatus("PARTIALLY_PAID");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("derived from recorded payments");
    }

    @Test
    @DisplayName("I16 an unsupported status is rejected rather than stored")
    void unknownStatusIsRejected() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();

        InvoiceRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 1, "500.00")));
        req.setStatus("SETTLED_SOMHOW");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unsupported invoice status");
    }

    @Test
    @DisplayName("I17 PENDING and CANCELLED remain client-settable")
    void pendingAndCancelledAreAllowed() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();
        givenNoPayments();

        InvoiceRequestDTO pending = request();
        pending.setItems(List.of(item("Brake pads", 1, "500.00")));
        pending.setStatus("PENDING");
        assertThat(service.create(pending).getStatus()).isEqualTo("PENDING");

        InvoiceRequestDTO cancelled = request();
        cancelled.setItems(List.of(item("Brake pads", 1, "500.00")));
        cancelled.setStatus("CANCELLED");
        assertThat(service.create(cancelled).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("I18 an update cannot set PAID either")
    void updateCannotSetPaid() {
        Invoice existing = new Invoice();
        existing.setId(42L);
        existing.setStatus("PENDING");
        existing.setJobCard(jobCard(55L));

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();

        InvoiceRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 1, "500.00")));
        req.setStatus("PAID");

        assertThatThrownBy(() -> service.update(42L, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("derived from recorded payments");
    }
}
