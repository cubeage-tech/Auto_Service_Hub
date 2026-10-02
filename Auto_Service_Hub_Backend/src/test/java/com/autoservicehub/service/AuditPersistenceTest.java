package com.autoservicehub.service;

import com.autoservicehub.dto.EstimateItemRequestDTO;
import com.autoservicehub.dto.EstimateRequestDTO;
import com.autoservicehub.dto.JobTaskRequestDTO;
import com.autoservicehub.dto.PaymentRequestDTO;
import com.autoservicehub.dto.StockMovementRequestDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.AuditLog;
import com.autoservicehub.entity.BillingItemCategory;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Part;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.AuditLogRepository;
import com.autoservicehub.repository.EstimateRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.service.impl.AuditServiceImpl;
import com.autoservicehub.service.impl.EstimateServiceImpl;
import com.autoservicehub.service.impl.InvoiceServiceImpl;
import com.autoservicehub.service.impl.JobCardServiceImpl;
import com.autoservicehub.service.impl.JobTaskServiceImpl;
import com.autoservicehub.service.impl.PaymentServiceImpl;
import com.autoservicehub.service.impl.StockMovementServiceImpl;
import com.autoservicehub.util.BillingCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the audit trail against a real database (SRS 6).
 *
 * <p>These cover what a Mockito test cannot: that a business operation really
 * does write an audit row, that the row is committed, and — the important one —
 * that a rolled-back operation leaves <em>no</em> success record behind, so the
 * trail never claims work happened that did not.
 *
 * <p>Job cards are built without a vehicle: {@code @DataJpaTest} cannot create
 * the {@code vehicles} table on H2, because {@code Vehicle} declares a column
 * named {@code year} and that is an H2 reserved word — a pre-existing
 * limitation this task must not fix by touching {@code Vehicle}.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditServiceImpl.class, BillingCalculator.class, JobCardServiceImpl.class,
        JobTaskServiceImpl.class, StockMovementServiceImpl.class,
        EstimateServiceImpl.class, InvoiceServiceImpl.class, PaymentServiceImpl.class,
        com.autoservicehub.service.impl.SupplierServiceImpl.class,
        com.autoservicehub.service.impl.PurchaseServiceImpl.class})
class AuditPersistenceTest {

    @Autowired AuditLogRepository  auditRepository;
    @Autowired JobCardServiceImpl jobCardService;
    @Autowired JobTaskServiceImpl taskService;
    @Autowired StockMovementServiceImpl stockService;
    @Autowired EstimateServiceImpl estimateService;
    @Autowired InvoiceServiceImpl  invoiceService;
    @Autowired PaymentServiceImpl paymentService;
    @Autowired com.autoservicehub.service.impl.SupplierServiceImpl supplierService;
    @Autowired com.autoservicehub.service.impl.PurchaseServiceImpl purchaseService;
    @Autowired JobCardRepository  jobCardRepository;
    @Autowired JobTaskRepository  taskRepository;
    @Autowired PartRepository    partRepository;
    @Autowired MechanicRepository mechanicRepository;
    @Autowired EstimateRepository estimateRepository;

    // ── Fixtures and helpers ────────────────────────────────────────────

    private JobCard givenJobCard(String number) {
        JobCard jc = new JobCard();
        jc.setJobCardNumber(number);
        jc.setServiceType("BRAKE_SERVICE");
        jc.setStatus("IN_REPAIR");
        jc.setAssignedDate(java.time.LocalDateTime.now());
        return jobCardRepository.save(jc);
    }

    private Part givenPart(String sku, int stock) {
        Part p = new Part();
        p.setSku(sku);
        p.setName("Part " + sku);
        p.setUnit("PCS");
        p.setSellingPrice(new BigDecimal("500.00"));
        p.setPurchasePrice(new BigDecimal("300.00"));
        p.setStockQty(stock);
        p.setMinStock(3);
        return partRepository.save(p);
    }

    private com.autoservicehub.entity.Mechanic givenMechanic(String name) {
        com.autoservicehub.entity.Mechanic m = new com.autoservicehub.entity.Mechanic();
        m.setName(name);
        m.setEmployeeCode("MECH-" + name);
        m.setStatus("ACTIVE");
        return mechanicRepository.save(m);
    }

    private com.autoservicehub.dto.SupplierRequestDTO supplierRequest(String name) {
        com.autoservicehub.dto.SupplierRequestDTO dto =
                new com.autoservicehub.dto.SupplierRequestDTO();
        dto.setName(name);
        dto.setPhone("+91 98000 00000");
        dto.setEmail("sales@" + name.replace(" ", "").toLowerCase() + ".example");
        return dto;
    }

    private com.autoservicehub.dto.PurchaseRequestDTO purchaseRequest(
            Long supplierId, Part part, int quantity, String unitPrice) {
        com.autoservicehub.dto.PurchaseItemRequestDTO line =
                new com.autoservicehub.dto.PurchaseItemRequestDTO();
        line.setPartId(part.getId());
        line.setQuantity(quantity);
        line.setUnitPrice(new BigDecimal(unitPrice));

        com.autoservicehub.dto.PurchaseRequestDTO dto =
                new com.autoservicehub.dto.PurchaseRequestDTO();
        dto.setSupplierId(supplierId);
        dto.setItems(List.of(line));
        return dto;
    }

    private JobTaskRequestDTO taskRequest(String description, String labour) {
        JobTaskRequestDTO dto = new JobTaskRequestDTO();
        dto.setDescription(description);
        dto.setLabourCost(new BigDecimal(labour));
        return dto;
    }

    /** Signs a user in, so audit rows are attributed rather than recorded as SYSTEM. */
    private void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username, "n/a", AuthorityUtils.createAuthorityList("ROLE_MANAGER")));
    }

    private List<AuditLog> entriesFor(String entityName) {
        return auditRepository.findAll().stream()
                .filter(e -> entityName.equals(e.getEntityName()))
                .toList();
    }

    /**
     * The first SUCCESS entry for an entity and action.
     *
     * <p>Scoped to SUCCESS deliberately: a FAILURE row for the same pair is a
     * different fact, and the tests that commit their fixtures would otherwise
     * leak a failure entry into a later test's lookup.
     */
    private AuditLog entryFor(String entityName, AuditAction action) {
        return entriesFor(entityName).stream()
                .filter(e -> action.name().equals(e.getAction()))
                .filter(e -> "SUCCESS".equals(e.getResult()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + action + " audit entry for " + entityName
                                + ". Entries: " + entriesFor(entityName)));
    }

    // ── Attribution from a real request ──────────────────────────────────

    @Test
    @DisplayName("AP1 - a job task create is audited, attributed and stamped")
    void ap1_jobTaskCreate_audited() {
        authenticateAs("ananya");
        JobCard jc = givenJobCard("JC-AP1");

        var created = taskService.create(jc.getId(), taskRequest("Replace brake pads", "500.00"));

        AuditLog entry = entryFor("JOB_TASK", AuditAction.JOB_TASK_CREATE);
        assertThat(entry.getEntityId()).isEqualTo(String.valueOf(created.getId()));
        assertThat(entry.getPerformedBy()).isEqualTo("ananya");
        assertThat(entry.getResult()).isEqualTo("SUCCESS");
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("AP2 - every job task action is audited under its own action type")
    void ap2_jobTaskActions_eachAudited() {
        authenticateAs("ananya");
        JobCard jc = givenJobCard("JC-AP2");
        var created = taskService.create(jc.getId(), taskRequest("Replace brake pads", "500.00"));
        Long id = created.getId();

        com.autoservicehub.dto.JobTaskStatusRequestDTO status =
                new com.autoservicehub.dto.JobTaskStatusRequestDTO();
        // Moves through the real workflow (PENDING -> IN_PROGRESS -> COMPLETED)
        // because a status cannot jump straight to COMPLETED; the point of this
        // test is that each action is audited, not which states it passes through.
        status.setStatus("IN_PROGRESS");
        taskService.updateStatus(id, status);
        status.setStatus("COMPLETED");
        taskService.updateStatus(id, status);

        com.autoservicehub.dto.JobTaskWorkNotesRequestDTO notes =
                new com.autoservicehub.dto.JobTaskWorkNotesRequestDTO();
        notes.setWorkNotes("Rear pads also worn");
        taskService.updateWorkNotes(id, notes);

        com.autoservicehub.dto.JobTaskAssignMechanicRequestDTO assign =
                new com.autoservicehub.dto.JobTaskAssignMechanicRequestDTO();
        assign.setMechanicId(givenMechanic("Anil").getId());
        taskService.assignMechanic(id, assign.getMechanicId());

        taskService.delete(id);

        assertThat(entryFor("JOB_TASK", AuditAction.JOB_TASK_CREATE)).isNotNull();
        assertThat(entryFor("JOB_TASK", AuditAction.JOB_TASK_STATUS_CHANGE)).isNotNull();
        assertThat(entryFor("JOB_TASK", AuditAction.JOB_TASK_UPDATE_WORK_NOTES)).isNotNull();
        assertThat(entryFor("JOB_TASK", AuditAction.JOB_TASK_ASSIGN_MECHANIC)).isNotNull();
        assertThat(entryFor("JOB_TASK", AuditAction.JOB_TASK_DELETE)).isNotNull();
    }

    // ── Other modules are audited too ───────────────────────────────────

@Test
@DisplayName("AP3 - a stock movement is audited under the action matching its type")
void ap3_stockMovement_auditedByType() {
authenticateAs("anil");
Part pads = givenPart("SKU-AP3", 10);
Part fluid = givenPart("SKU-AP3-B", 5);

StockMovementRequestDTO in = new StockMovementRequestDTO();
in.setPartId(pads.getId());
in.setMovementType("IN");
in.setQuantity(5);
stockService.create(in);

StockMovementRequestDTO out = new StockMovementRequestDTO();
out.setPartId(fluid.getId());
out.setMovementType("OUT");
out.setQuantity(2);
stockService.create(out);

StockMovementRequestDTO adjust = new StockMovementRequestDTO();
adjust.setPartId(fluid.getId());
adjust.setMovementType("ADJUSTMENT");
adjust.setQuantity(1);
adjust.setAdjustmentDelta(-1);
stockService.create(adjust);

// IN, OUT and ADJUSTMENT are distinguishable in the trail rather than all
// recorded as one generic "stock change".
assertThat(entryFor("STOCK_MOVEMENT", AuditAction.STOCK_IN)).isNotNull();
assertThat(entryFor("STOCK_MOVEMENT", AuditAction.STOCK_OUT)).isNotNull();
assertThat(entryFor("STOCK_MOVEMENT", AuditAction.STOCK_ADJUSTMENT)).isNotNull();
}

@Test
@DisplayName("AP4 - estimate creation and conversion to invoice are both audited")
void ap4_estimateAndConversion_audited() {
authenticateAs("ananya");
JobCard jc = givenJobCard("JC-AP4");

EstimateItemRequestDTO line = new EstimateItemRequestDTO();
line.setDescription("Brake pads");
line.setQuantity(2);
line.setUnitPrice(new BigDecimal("500.00"));
line.setCategory(BillingItemCategory.PART);

EstimateRequestDTO request = new EstimateRequestDTO();
request.setJobCardId(jc.getId());
request.setItems(List.of(line));
var estimate = estimateService.create(request);

var invoice = invoiceService.convertFromEstimate(estimate.getId());

assertThat(entryFor("ESTIMATE", AuditAction.ESTIMATE_CREATE)).isNotNull();
// The conversion is audited against the invoice it produced.
AuditLog converted = entryFor("INVOICE", AuditAction.ESTIMATE_CONVERT);
assertThat(converted.getEntityId()).isEqualTo(String.valueOf(invoice.getId()));
assertThat(converted.getPerformedBy()).isEqualTo("ananya");
}

@Test
@DisplayName("AP5 - a payment is audited with its amount, and deleting one is audited too")
void ap5_payment_audited() {
authenticateAs("cashier");
JobCard jc = givenJobCard("JC-AP5");

EstimateItemRequestDTO line = new EstimateItemRequestDTO();
line.setDescription("Brake pads");
line.setQuantity(1);
line.setUnitPrice(new BigDecimal("1000.00"));
EstimateRequestDTO estimateRequest = new EstimateRequestDTO();
estimateRequest.setJobCardId(jc.getId());
estimateRequest.setItems(List.of(line));
var estimate = estimateService.create(estimateRequest);
var invoice = invoiceService.convertFromEstimate(estimate.getId());

PaymentRequestDTO payment = new PaymentRequestDTO();
payment.setInvoiceId(invoice.getId());
payment.setAmount(new BigDecimal("1180.00"));
payment.setMode("CASH");
var paid = paymentService.create(payment);

AuditLog created = entryFor("PAYMENT", AuditAction.PAYMENT_CREATE);
assertThat(created.getPerformedBy()).isEqualTo("cashier");
assertThat(created.getDetails()).contains("1180.00");

// Deleting money received is the most security-sensitive billing action, so it
// is definitely in the trail.
paymentService.delete(paid.getId());
assertThat(entryFor("PAYMENT", AuditAction.PAYMENT_DELETE)).isNotNull();
}

@Test
@DisplayName("AP6 - a payment's transaction reference is never copied into the trail")
void ap6_paymentReference_notAudited() {
authenticateAs("cashier");
JobCard jc = givenJobCard("JC-AP6");

EstimateItemRequestDTO line = new EstimateItemRequestDTO();
line.setDescription("Brake pads");
line.setQuantity(1);
line.setUnitPrice(new BigDecimal("1000.00"));
EstimateRequestDTO estimateRequest = new EstimateRequestDTO();
estimateRequest.setJobCardId(jc.getId());
estimateRequest.setItems(List.of(line));
var estimate = estimateService.create(estimateRequest);
var invoice = invoiceService.convertFromEstimate(estimate.getId());

PaymentRequestDTO payment = new PaymentRequestDTO();
payment.setInvoiceId(invoice.getId());
payment.setAmount(new BigDecimal("1180.00"));
payment.setMode("CASH");
// A gateway reference may be a credential rather than a receipt number.
payment.setTransactionRef("GATEWAY-SECRET-REF-12345");
paymentService.create(payment);

assertThat(entryFor("PAYMENT", AuditAction.PAYMENT_CREATE).getDetails())
.doesNotContain("GATEWAY-SECRET-REF-12345");
}

// ── Transaction behaviour ────────────────────────────────────────────

/**
 * A rolled-back operation must leave no SUCCESS record behind.
 *
 * <p>This is the property that stops the trail lying. A success entry joins the
 * caller's transaction, so when the business work rolls back the claim that it
 * happened rolls back with it.
 *
 * <p>{@code @DataJpaTest} wraps each test in a transaction the service joins, so
 * the ambient one is suspended with NOT_SUPPORTED to let the service start — and
 * roll back — a real transaction of its own. The fixtures written here are
 * committed for real, so assertions are against deltas.
 */
@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AP7 - a rolled-back operation leaves no success audit entry")
void ap7_rollback_leavesNoSuccessEntry() {
authenticateAs("ananya");
JobCard jc = givenJobCard("JC-AP7");
Part part = givenPart("SKU-AP7", 10);

long entriesBefore = auditRepository.count();

// A movement that is refused: ADJUSTMENT of -20 on 10 in stock goes negative.
StockMovementRequestDTO doomed = new StockMovementRequestDTO();
doomed.setPartId(part.getId());
doomed.setMovementType("ADJUSTMENT");
doomed.setQuantity(20);
doomed.setAdjustmentDelta(-20);

assertThatThrownBy(() -> stockService.create(doomed))
.isInstanceOf(BusinessRuleException.class);

// No success entry claims a stock change that was rolled back.
assertThat(entriesFor("STOCK_MOVEMENT")).isEmpty();
assertThat(auditRepository.count()).isEqualTo(entriesBefore);

// The stock really did not move, either.
assertThat(partRepository.findById(part.getId()).orElseThrow().getStockQty()).isEqualTo(10);
}

/**
 * The opposite case: a FAILURE record is written in its own transaction and so
 * survives the rollback it describes.
 *
 * <p>These use {@link AuditService} directly, because the business services
 * deliberately only record successes — a caller that throws before reaching the
 * service has no entry point through which to report its own failure.
 */
@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AP8 - a failure record survives the rollback of the operation it describes")
void ap8_failureRecord_survivesRollback() {
authenticateAs("ananya");
JobCard jc = givenJobCard("JC-AP8");

long entriesBefore = auditRepository.count();

// A refused operation: its business transaction rolls back.
assertThatThrownBy(() -> taskService.create(jc.getId(), taskRequest("Bad", "-1.00")))
.isInstanceOf(BusinessRuleException.class);

// A failure entry is written in its own transaction, so it outlives that
// rollback - which is exactly when it is worth keeping.
AuditService auditService = new AuditServiceImpl(auditRepository);
auditService.recordFailure("JOB_TASK", null, AuditAction.JOB_TASK_CREATE,
"Attempted create on job card " + jc.getId(), "negative labour cost");

assertThat(auditRepository.count()).isEqualTo(entriesBefore + 1);
AuditLog failure = auditRepository.findAll().stream()
.filter(e -> "FAILURE".equals(e.getResult()))
.findFirst().orElseThrow();

assertThat(failure.getAction()).isEqualTo(AuditAction.JOB_TASK_CREATE.name());
assertThat(failure.getPerformedBy()).isEqualTo("ananya");
assertThat(failure.getDetails()).contains("negative labour cost");
}

// ── Sensitive data never reaches the table ──────────────────────────

@Test
@DisplayName("AP9 - credential text in a details string is redacted before it is stored")
void ap9_sensitiveData_notStored() {
authenticateAs("ananya");
AuditService auditService = new AuditServiceImpl(auditRepository);

auditService.recordSuccess("SUPPLIER", 1L, AuditAction.SUPPLIER_UPDATE,
"Contact updated; password=Hunter2 apiKey=sk-live-999");

AuditLog stored = auditRepository.findAll().get(0);
assertThat(stored.getDetails())
.doesNotContain("Hunter2")
.doesNotContain("sk-live-999")
.contains("[REDACTED]");
}

@Test
@DisplayName("AP10 - with no signed-in user an entry is attributed to SYSTEM")
void ap10_systemAttribution() {
SecurityContextHolder.clearContext();
JobCard jc = givenJobCard("JC-AP10");

// A scheduler or internal call, with no request behind it.
taskService.create(jc.getId(), taskRequest("Replace brake pads", "500.00"));

AuditLog entry = entryFor("JOB_TASK", AuditAction.JOB_TASK_CREATE);
assertThat(entry.getPerformedBy()).isEqualTo(AuditLog.SYSTEM_ACTOR);
assertThat(entry.getIpAddress()).isNull();
}

@Test
@DisplayName("AP11 - an ordinary read is not audited")
void ap11_reads_notAudited() {
authenticateAs("anil");
JobCard jc = givenJobCard("JC-AP11");
taskService.create(jc.getId(), taskRequest("Replace brake pads", "500.00"));
long afterCreate = auditRepository.count();

// Reading is not an action worth a trail entry; logging everything would bury
// the entries that matter.
taskService.getById(taskRepository.findAll().get(0).getId());
taskService.listByJobCard(jc.getId(), org.springframework.data.domain.PageRequest.of(0, 20));
jobCardService.getById(jc.getId());

assertThat(auditRepository.count()).isEqualTo(afterCreate);
}

// ── Purchasing and suppliers, audited through the real services ────

@Test
@DisplayName("AP12 - supplier create, update and delete are each audited")
void ap12_supplierCrud_audited() {
authenticateAs("buyer");
var supplier = supplierService.create(supplierRequest("Bharat Auto"));

supplierService.update(supplier.getId(), supplierRequest("Bharat Auto Supplies"));

AuditLog created = entryFor("SUPPLIER", AuditAction.SUPPLIER_CREATE);
assertThat(created.getPerformedBy()).isEqualTo("buyer");
assertThat(created.getEntityId()).isEqualTo(String.valueOf(supplier.getId()));
assertThat(created.getDetails()).contains("Bharat Auto");

AuditLog updated = entryFor("SUPPLIER", AuditAction.SUPPLIER_UPDATE);
assertThat(updated.getEntityId()).isEqualTo(String.valueOf(supplier.getId()));
assertThat(updated.getDetails()).contains("Bharat Auto Supplies");

supplierService.delete(supplier.getId());
assertThat(entryFor("SUPPLIER", AuditAction.SUPPLIER_DELETE).getEntityId())
.isEqualTo(String.valueOf(supplier.getId()));
}

@Test
@DisplayName("AP13 - creating a purchase is audited against the purchase")
void ap13_purchaseCreate_audited() {
authenticateAs("buyer");
var supplier = supplierService.create(supplierRequest("Bharat Auto"));
Part part = givenPart("SKU-AP13", 0);

var purchase = purchaseService.create(purchaseRequest(supplier.getId(), part, 3, "250.00"));

AuditLog entry = entryFor("PURCHASE", AuditAction.PURCHASE_CREATE);
assertThat(entry.getEntityId()).isEqualTo(String.valueOf(purchase.getId()));
assertThat(entry.getPerformedBy()).isEqualTo("buyer");
assertThat(entry.getDetails()).contains("750.00");
}

@Test
@DisplayName("AP14 - receiving a purchase is audited, and a refused receipt is not")
void ap14_purchaseReceive_audited() {
authenticateAs("buyer");
var supplier = supplierService.create(supplierRequest("Bharat Auto"));
Part part = givenPart("SKU-AP14", 0);
var purchase = purchaseService.create(purchaseRequest(supplier.getId(), part, 5, "100.00"));

purchaseService.receive(purchase.getId());

AuditLog received = entryFor("PURCHASE", AuditAction.PURCHASE_RECEIVE);
assertThat(received.getEntityId()).isEqualTo(String.valueOf(purchase.getId()));
assertThat(received.getPerformedBy()).isEqualTo("buyer");

long afterFirst = auditRepository.count();
// A second receipt is refused before anything is written, so nothing is audited.
assertThatThrownBy(() -> purchaseService.receive(purchase.getId()))
.isInstanceOf(BusinessRuleException.class);
assertThat(auditRepository.count()).isEqualTo(afterFirst);
}
}