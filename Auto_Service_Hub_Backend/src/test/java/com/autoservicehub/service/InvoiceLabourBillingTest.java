package com.autoservicehub.service;

import com.autoservicehub.dto.InvoiceItemRequestDTO;
import com.autoservicehub.dto.InvoiceLabourItemRequestDTO;
import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.entity.BillingItemCategory;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.InvoiceItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.InvoiceItemRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.service.impl.InvoiceServiceImpl;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for labour-to-invoice billing (FR-BILL-2 / FR-BILL-3).
 *
 * <p>Covers what a Mockito test cannot: that a generated labour line is really
 * persisted with its task link and category, that the duplicate guard holds
 * against the actual database rather than a stub, that a task from another job
 * card is refused, that the whole invoice is one transaction so a rejected line
 * leaves no trace, and that existing part billing, GST, discount and the PAID
 * freeze all still behave exactly as before.
 *
 * <p>{@code BillingCalculator} is imported rather than mocked: it is the single
 * place money is computed, and every figure asserted here is meant to come from
 * the production rules.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:invoice-labour-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuditServiceImpl.class, BillingCalculator.class, InvoiceServiceImpl.class})
class InvoiceLabourBillingTest {

    @Autowired InvoiceServiceImpl     invoiceService;
    @Autowired InvoiceRepository      invoiceRepository;
    @Autowired InvoiceItemRepository  invoiceItemRepository;
    @Autowired JobCardRepository      jobCardRepository;
    @Autowired JobTaskRepository      jobTaskRepository;

    // ── Fixtures ────────────────────────────────────────────────────────

    /**
     * A job card with no customer or vehicle.
     *
     * <p>{@code JobCard.customer} and {@code JobCard.vehicle} are both nullable,
     * and this deliberately leaves them null. {@code @DataJpaTest} cannot create
     * the {@code vehicles} table on H2 at all — {@code Vehicle} declares a
     * column named {@code year}, which is a reserved word in H2 — and that is a
     * pre-existing test-infrastructure limitation this task must not work
     * around by touching {@code Vehicle}. The existing {@code
     * BillingPersistenceTest} builds its job cards the same way.
     *
     * <p>Nothing under test depends on the vehicle: the rule is that a task must
     * belong to the invoice's job card, and job card identity alone decides it.
     */
    private JobCard givenJobCard(String number) {
        JobCard jc = new JobCard();
        jc.setJobCardNumber(number);
        jc.setServiceType("BRAKE_SERVICE");
        jc.setStatus("DELIVERED");
        jc.setAssignedDate(java.time.LocalDateTime.now());
        return jobCardRepository.save(jc);
    }

    private JobTask givenTask(JobCard jc, String description, String labourCost) {
        JobTask t = new JobTask();
        t.setJobCard(jc);
        t.setDescription(description);
        t.setStatus("COMPLETED");
        t.setLabourCost(labourCost == null ? null : new BigDecimal(labourCost));
        return jobTaskRepository.save(t);
    }

    private InvoiceItemRequestDTO partLine(String description, int qty, String unitPrice) {
        InvoiceItemRequestDTO dto = new InvoiceItemRequestDTO();
        dto.setDescription(description);
        dto.setQuantity(qty);
        dto.setUnitPrice(new BigDecimal(unitPrice));
        return dto;
    }

    private InvoiceRequestDTO invoiceRequest(JobCard jc, InvoiceItemRequestDTO... items) {
        InvoiceRequestDTO req = new InvoiceRequestDTO();
        req.setJobCardId(jc.getId());
        req.setItems(List.of(items));
        return req;
    }

    private InvoiceLabourItemRequestDTO labour(Long jobTaskId) {
        InvoiceLabourItemRequestDTO req = new InvoiceLabourItemRequestDTO();
        req.setJobTaskId(jobTaskId);
        return req;
    }

    // ── A labour line is generated from the task ────────────────────────

@Test
@DisplayName("ILB1 - labour is billed from the task's own description, quantity and cost")
void ilb1_labourLine_generatedFromTask() {
JobCard jc = givenJobCard("JC-ILB1");
JobTask task = givenTask(jc, "Replace front brake pads", "500.00");
var invoice = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 2, "500.00")));

var response = invoiceService.addLabourItem(invoice.getId(), labour(task.getId()));

InvoiceItem line = invoiceItemRepository.findByInvoiceIdOrderByIdAsc(invoice.getId())
.stream().filter(i -> i.getJobTask() != null)
.findFirst().orElseThrow();

assertThat(line.getDescription()).isEqualTo("Replace front brake pads");
assertThat(line.getQuantity()).isEqualTo(1);
assertThat(line.getUnitPrice()).isEqualByComparingTo("500.00");
assertThat(line.getLineAmount()).isEqualByComparingTo("500.00");
assertThat(line.getCategory()).isEqualTo(BillingItemCategory.LABOUR);
assertThat(line.getJobTask().getId()).isEqualTo(task.getId());

assertThat(response.getItems()).anySatisfy(item -> {
assertThat(item.getCategory()).isEqualTo(BillingItemCategory.LABOUR);
assertThat(item.getJobTaskId()).isEqualTo(task.getId());
});
}

@Test
@DisplayName("ILB2 - parts keep PART category and an unstated category reads as PART")
void ilb2_partsDefaultToPart() {
JobCard jc = givenJobCard("JC-ILB2");

// No category supplied, exactly as an existing client would send it.
var response = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 2, "500.00")));

assertThat(response.getItems()).singleElement()
.satisfies(item -> assertThat(item.getCategory()).isEqualTo(BillingItemCategory.PART));
}

@Test
@DisplayName("ILB3 - an explicit category on a manual line is stored and reported")
void ilb3_explicitCategory_stored() {
JobCard jc = givenJobCard("JC-ILB3");
InvoiceItemRequestDTO pkg = partLine("Full service package", 1, "4000.00");
pkg.setCategory(BillingItemCategory.PACKAGE);

var response = invoiceService.create(invoiceRequest(jc, pkg));

assertThat(response.getItems()).singleElement()
.satisfies(item -> assertThat(item.getCategory()).isEqualTo(BillingItemCategory.PACKAGE));
}

// ── Money: labour amount, GST, discount, total ──────────────────────

@Test
@DisplayName("ILB4 - parts plus labour: subtotal, 18% GST and total are server-calculated")
void ilb4_labourAndParts_totalsCalculated() {
JobCard jc = givenJobCard("JC-ILB4");
JobTask task = givenTask(jc, "Replace front brake pads", "500.00");
var invoice = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 2, "500.00")));

var response = invoiceService.addLabourItem(invoice.getId(), labour(task.getId()));

// 2 x 500 parts = 1000, plus 500 labour = 1500 subtotal.
assertThat(response.getSubtotal()).isEqualByComparingTo("1500.00");
assertThat(response.getGst()).isEqualByComparingTo("270.00");
assertThat(response.getTotal()).isEqualByComparingTo("1770.00");
}

@Test
@DisplayName("ILB5 - a discount applies after labour is added and reduces the taxable base")
void ilb5_discount_appliesToLabourInclusiveSubtotal() {
JobCard jc = givenJobCard("JC-ILB5");
JobTask task = givenTask(jc, "Replace front brake pads", "500.00");

InvoiceRequestDTO req = invoiceRequest(jc, partLine("Brake pads", 2, "500.00"));
req.setDiscount(new BigDecimal("500.00"));
var invoice = invoiceService.create(req);

var response = invoiceService.addLabourItem(invoice.getId(), labour(task.getId()));

// subtotal 1500 - discount 500 = taxable 1000; GST 180; total 1180.
assertThat(response.getSubtotal()).isEqualByComparingTo("1500.00");
assertThat(response.getDiscount()).isEqualByComparingTo("500.00");
assertThat(response.getGst()).isEqualByComparingTo("180.00");
assertThat(response.getTotal()).isEqualByComparingTo("1180.00");
}

@Test
@DisplayName("ILB6 - a task with no costed labour bills a zero line rather than failing")
void ilb6_zeroCostLabour_isZeroLine() {
JobCard jc = givenJobCard("JC-ILB6");
JobTask task = givenTask(jc, "Road test", null);
var invoice = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 1, "500.00")));

var response = invoiceService.addLabourItem(invoice.getId(), labour(task.getId()));

assertThat(response.getSubtotal()).isEqualByComparingTo("500.00");
assertThat(response.getItems()).filteredOn(i -> i.getJobTaskId() != null)
.singleElement()
.satisfies(item -> assertThat(item.getLineAmount()).isEqualByComparingTo("0.00"));
}

// ── The guards that stop a wrong invoice ────────────────────────────

@Test
@DisplayName("ILB7 - the same task cannot be billed twice on one invoice")
void ilb7_duplicateTask_rejected() {
JobCard jc = givenJobCard("JC-ILB7");
JobTask task = givenTask(jc, "Replace front brake pads", "500.00");
var invoice = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 1, "500.00")));

invoiceService.addLabourItem(invoice.getId(), labour(task.getId()));
long linesAfterFirst = invoiceItemRepository.count();

assertThatThrownBy(() -> invoiceService.addLabourItem(invoice.getId(), labour(task.getId())))
.isInstanceOf(BusinessRuleException.class)
.hasMessageContaining("already been billed");

// Nothing extra written, and the total did not double.
assertThat(invoiceItemRepository.count()).isEqualTo(linesAfterFirst);
assertThat(invoiceRepository.findById(invoice.getId()).orElseThrow().getTotal())
.isEqualByComparingTo("1180.00");
}

@Test
@DisplayName("ILB8 - a task from a different job card cannot be billed")
void ilb8_wrongJobCard_rejected() {
JobCard invoiced = givenJobCard("JC-ILB8-A");
JobCard other = givenJobCard("JC-ILB8-B");
JobTask foreignTask = givenTask(other, "Work on someone else's car", "900.00");
var invoice = invoiceService.create(invoiceRequest(invoiced, partLine("Brake pads", 1, "500.00")));

assertThatThrownBy(() -> invoiceService.addLabourItem(invoice.getId(), labour(foreignTask.getId())))
.isInstanceOf(BusinessRuleException.class)
.hasMessageContaining("cannot be billed on invoice");

assertThat(invoiceItemRepository.count()).isEqualTo(1);
assertThat(invoiceRepository.findById(invoice.getId()).orElseThrow().getTotal())
.isEqualByComparingTo("590.00");
}

@Test
@DisplayName("ILB9 - an unknown task is refused")
void ilb9_unknownTask_rejected() {
JobCard jc = givenJobCard("JC-ILB9");
var invoice = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 1, "500.00")));

assertThatThrownBy(() -> invoiceService.addLabourItem(invoice.getId(), labour(999999L)))
.isInstanceOf(ResourceNotFoundException.class)
.hasMessageContaining("JobTask not found: 999999");
}

@Test
@DisplayName("ILB10 - an unknown invoice is refused")
void ilb10_unknownInvoice_rejected() {
assertThatThrownBy(() -> invoiceService.addLabourItem(999999L, labour(1L)))
.isInstanceOf(ResourceNotFoundException.class)
.hasMessageContaining("Invoice not found: 999999");
}

@Test
@DisplayName("ILB11 - labour cannot be added to an invoice that is already PAID")
void ilb11_paidInvoice_frozen() {
JobCard jc = givenJobCard("JC-ILB11");
JobTask task = givenTask(jc, "Replace front brake pads", "500.00");
InvoiceRequestDTO req = invoiceRequest(jc, partLine("Brake pads", 1, "500.00"));
var invoice = invoiceService.create(req);
BigDecimal totalBefore = invoice.getTotal();

// Settled the way a settled invoice actually gets settled. It used to set
// status=PAID through the create request, but FR-BILL-6 now refuses that:
// PAID is derived from recorded payments, so a client can no longer declare an
// invoice paid. Writing it to the stored row keeps this test's actual subject —
// that a PAID invoice is frozen — without reintroducing the loophole.
Invoice settled = invoiceRepository.findById(invoice.getId()).orElseThrow();
settled.setStatus("PAID");
invoiceRepository.save(settled);

assertThatThrownBy(() -> invoiceService.addLabourItem(invoice.getId(), labour(task.getId())))
.isInstanceOf(BusinessRuleException.class)
.hasMessageContaining("already PAID");

assertThat(invoiceItemRepository.count()).isEqualTo(1);
assertThat(invoiceRepository.findById(invoice.getId()).orElseThrow().getTotal())
.isEqualByComparingTo(totalBefore);
}

@Test
@DisplayName("ILB12 - a negative labour cost on a task cannot be billed")
void ilb12_negativeLabour_rejected() {
JobCard jc = givenJobCard("JC-ILB12");
JobTask task = givenTask(jc, "Badly costed task", "-250.00");
var invoice = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 1, "500.00")));

// JobTaskService refuses to create a negative cost, so this guards a row that
// reached the table another way: the invoice must not absorb it either.
assertThatThrownBy(() -> invoiceService.addLabourItem(invoice.getId(), labour(task.getId())))
.isInstanceOf(BusinessRuleException.class)
.hasMessageContaining("must not be negative");

assertThat(invoiceItemRepository.count()).isEqualTo(1);
}

// ── No loss and no duplication across an invoice update ──────────────

@Test
@DisplayName("ILB13 - editing an invoice keeps task labour instead of deleting it")
void ilb13_update_preservesTaskLabour() {
JobCard jc = givenJobCard("JC-ILB13");
JobTask task = givenTask(jc, "Replace front brake pads", "500.00");
var invoice = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 2, "500.00")));
invoiceService.addLabourItem(invoice.getId(), labour(task.getId()));

// The caller edits the parts only, sending no labour line at all.
InvoiceRequestDTO edit = invoiceRequest(jc, partLine("Brake pads", 3, "500.00"));
var response = invoiceService.update(invoice.getId(), edit);

// 3 x 500 parts = 1500, plus the 500 labour that survived = 2000.
assertThat(response.getSubtotal()).isEqualByComparingTo("2000.00");
assertThat(response.getItems()).filteredOn(i -> i.getJobTaskId() != null)
.singleElement()
.satisfies(item -> {
assertThat(item.getDescription()).isEqualTo("Replace front brake pads");
assertThat(item.getLineAmount()).isEqualByComparingTo("500.00");
assertThat(item.getCategory()).isEqualTo(BillingItemCategory.LABOUR);
});
}

@Test
@DisplayName("ILB14 - editing an invoice repeatedly never duplicates the labour")
void ilb14_repeatedUpdates_doNotDuplicateLabour() {
JobCard jc = givenJobCard("JC-ILB14");
JobTask task = givenTask(jc, "Replace front brake pads", "500.00");
var invoice = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 2, "500.00")));
invoiceService.addLabourItem(invoice.getId(), labour(task.getId()));

InvoiceRequestDTO edit = invoiceRequest(jc, partLine("Brake pads", 2, "500.00"));
var first = invoiceService.update(invoice.getId(), edit);
var second = invoiceService.update(invoice.getId(), edit);
var third = invoiceService.update(invoice.getId(), edit);

// One part line and one labour line, every time.
assertThat(first.getItems()).hasSize(2);
assertThat(second.getItems()).hasSize(2);
assertThat(third.getItems()).hasSize(2);
assertThat(third.getSubtotal()).isEqualByComparingTo("1500.00");
assertThat(third.getTotal()).isEqualByComparingTo("1770.00");
assertThat(invoiceItemRepository.count()).isEqualTo(2);
}

@Test
@DisplayName("ILB15 - the same task may legitimately be billed on a second invoice")
void ilb15_sameTaskOnSecondInvoice_allowed() {
JobCard jc = givenJobCard("JC-ILB15");
JobTask task = givenTask(jc, "Replace front brake pads", "500.00");
var first = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 2, "500.00")));
var second = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 2, "500.00")));

invoiceService.addLabourItem(first.getId(), labour(task.getId()));
var response = invoiceService.addLabourItem(second.getId(), labour(task.getId()));

assertThat(response.getSubtotal()).isEqualByComparingTo("1500.00");
}

@Test
@DisplayName("ILB16 - deleting an invoice still removes its labour lines")
void ilb16_delete_removesLabourLines() {
JobCard jc = givenJobCard("JC-ILB16");
JobTask task = givenTask(jc, "Replace front brake pads", "500.00");
var invoice = invoiceService.create(invoiceRequest(jc, partLine("Brake pads", 2, "500.00")));
invoiceService.addLabourItem(invoice.getId(), labour(task.getId()));
assertThat(invoiceItemRepository.count()).isEqualTo(2);

invoiceService.delete(invoice.getId());

assertThat(invoiceRepository.findById(invoice.getId())).isEmpty();
assertThat(invoiceItemRepository.count()).isZero();
}
}
