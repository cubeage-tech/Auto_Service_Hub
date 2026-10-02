package com.autoservicehub.service;

import com.autoservicehub.dto.PurchaseItemRequestDTO;
import com.autoservicehub.dto.PurchaseRequestDTO;
import com.autoservicehub.entity.Part;
import com.autoservicehub.entity.Purchase;
import com.autoservicehub.entity.PurchaseItem;
import com.autoservicehub.entity.Supplier;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.PurchaseItemRepository;
import com.autoservicehub.repository.PurchaseRepository;
import com.autoservicehub.repository.StockMovementRepository;
import com.autoservicehub.repository.SupplierRepository;
import com.autoservicehub.service.impl.PurchaseServiceImpl;
import com.autoservicehub.service.impl.StockMovementServiceImpl;
import com.autoservicehub.service.impl.SupplierServiceImpl;
import com.autoservicehub.service.impl.AuditServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;

/**
 * Integration tests for purchasing against a real database.
 *
 * <p>These cover what a Mockito unit test cannot: that the calculated total is
 * what actually gets persisted, that receiving really increases
 * {@code Part.stockQty}, that it does so by writing rows through the existing
 * stock-movement implementation, that a purchase cannot be received twice, and
 * that a failure part-way through a receipt rolls the whole thing back leaving
 * no movement and no stock change.
 *
 * <p>{@code StockMovementServiceImpl} is imported rather than mocked on
 * purpose: receiving delegates to it, so mocking it would assert only that the
 * delegation happened. Using the real one proves the integration - and that no
 * stock-update logic was duplicated in the purchasing service.
 *
 * <p>Uses {@code @DataJpaTest} for the same reason as the existing slices:
 * application.yml pins MySQLDialect, whose DDL H2 cannot execute, so the
 * dialect is overridden to match the real JDBC metadata and the database name is
 * isolated from the shared {@code testdb}.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:purchasing-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuditServiceImpl.class, SupplierServiceImpl.class, PurchaseServiceImpl.class, StockMovementServiceImpl.class})
class PurchasingPersistenceTest {

    @Autowired PurchaseServiceImpl     purchaseService;
    @Autowired SupplierServiceImpl     supplierService;
    @Autowired PurchaseRepository       purchaseRepository;
    @Autowired PurchaseItemRepository   itemRepository;
    @Autowired SupplierRepository       supplierRepository;
    @Autowired PartRepository           partRepository;
    @Autowired StockMovementRepository  movementRepository;

    /**
     * A spy, not a mock: every test gets the real behaviour. Only PP12 stubs it,
     * and {@link #reset()} after each test puts it back, so one test cannot
     * change what the others observe.
     */
    @SpyBean StockMovementServiceImpl   stockMovementService;

    @AfterEach
    void clearMovementStubbing() {
        reset(stockMovementService);
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private Supplier givenSupplier(String name) {
        Supplier s = new Supplier();
        s.setName(name);
        s.setPhone("+91 98000 00000");
        s.setEmail(name.replace(" ", "").toLowerCase() + "@example.com");
        return supplierRepository.save(s);
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

    private PurchaseItemRequestDTO line(Long partId, int qty, String unitPrice) {
        PurchaseItemRequestDTO dto = new PurchaseItemRequestDTO();
        dto.setPartId(partId);
        dto.setQuantity(qty);
        dto.setUnitPrice(new BigDecimal(unitPrice));
        return dto;
    }

    private PurchaseRequestDTO request(Long supplierId, PurchaseItemRequestDTO... items) {
        PurchaseRequestDTO dto = new PurchaseRequestDTO();
        dto.setSupplierId(supplierId);
        dto.setItems(List.of(items));
        return dto;
    }

    private Purchase givenPurchase(Supplier supplier, String status) {
        Purchase p = new Purchase();
        p.setSupplier(supplier);
            p.setPurchaseDate(LocalDate.of(2026, 1, 15));
            p.setTotalAmount(BigDecimal.ZERO);
            p.setStatus(status);
            return purchaseRepository.save(p);
        }

        private PurchaseItem givenItem(Purchase purchase, Part part, int qty, String unitPrice) {
            PurchaseItem item = new PurchaseItem();
            item.setPurchase(purchase);
            item.setPart(part);
            item.setQuantity(qty);
            item.setUnitPrice(new BigDecimal(unitPrice));
            item.setLineAmount(new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(qty)));
            return itemRepository.save(item);
        }

        // ── Supplier persistence ───────────────────────────────────────────

    @Test
    @DisplayName("PP1 - a created supplier is persisted with its contact details")
    void pp1_supplierCreate_persists() {
    var response = supplierService.create(supplierRequest("Bharat Auto"));

    Supplier stored = supplierRepository.findById(response.getId()).orElseThrow();
    assertThat(stored.getName()).isEqualTo("Bharat Auto");
    assertThat(stored.getEmail()).isEqualTo("sales@bharatauto.example");
    assertThat(stored.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("PP2 - a duplicate supplier name is rejected case-insensitively and nothing is stored")
    void pp2_duplicateSupplierName_rejected() {
    supplierService.create(supplierRequest("Bharat Auto"));
    long before = supplierRepository.count();

    assertThatThrownBy(() -> supplierService.create(supplierRequest("  bharat auto ")))
    .isInstanceOf(BusinessRuleException.class)
    .hasMessageContaining("already exists");

    assertThat(supplierRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("PP3 - a supplier with purchase history cannot be deleted")
    void pp3_deleteSupplierWithHistory_rejected() {
    Supplier supplier = givenSupplier("Bharat Auto");
    Part part = givenPart("SKU-P1", 0);
    givenItem(givenPurchase(supplier, PurchaseServiceImpl.STATUS_PENDING), part, 1, "300.00");

    assertThatThrownBy(() -> supplierService.delete(supplier.getId()))
    .isInstanceOf(BusinessRuleException.class)
    .hasMessageContaining("purchase history");

    assertThat(supplierRepository.findById(supplier.getId())).isPresent();
    }

    // ── The stored total is the server-side calculation ─────────────────

    @Test
    @DisplayName("PP4 - the persisted total and line amounts are the calculated ones")
    void pp4_purchase_persistsCalculatedTotal() {
    Supplier supplier = givenSupplier("Bharat Auto");
    Part pads = givenPart("SKU-P4-A", 0);
    Part fluid = givenPart("SKU-P4-B", 0);

    var response = purchaseService.create(request(supplier.getId(),
    line(pads.getId(), 2, "300.00"),
    line(fluid.getId(), 4, "125.50")));

    Purchase stored = purchaseRepository.findById(response.getId()).orElseThrow();
    assertThat(stored.getTotalAmount()).isEqualByComparingTo("1102.00");
    assertThat(stored.getStatus()).isEqualTo(PurchaseServiceImpl.STATUS_PENDING);
    assertThat(stored.getSupplier().getId()).isEqualTo(supplier.getId());

    List<PurchaseItem> items = itemRepository.findByPurchaseIdOrderByIdAsc(stored.getId());
    assertThat(items).hasSize(2);
    assertThat(items.get(0).getLineAmount()).isEqualByComparingTo("600.00");
    assertThat(items.get(1).getLineAmount()).isEqualByComparingTo("502.00");
    assertThat(items.get(1).getPart().getSku()).isEqualTo("SKU-P4-B");
    }

    @Test
    @DisplayName("PP5 - creating a purchase does not move stock")
    void pp5_purchaseCreate_doesNotTouchStock() {
    Supplier supplier = givenSupplier("Bharat Auto");
    Part part = givenPart("SKU-P5", 4);

    purchaseService.create(request(supplier.getId(), line(part.getId(), 10, "300.00")));

    assertThat(partRepository.findById(part.getId()).orElseThrow().getStockQty()).isEqualTo(4);
    assertThat(movementRepository.count()).isZero();
    }

    // ── Receiving: stock, ledger and status ─────────────────────────────

    @Test
    @DisplayName("PP6 - receiving increases stock and records one IN movement per line")
    void pp6_receive_increasesStockAndRecordsMovements() {
    Supplier supplier = givenSupplier("Bharat Auto");
    Part pads = givenPart("SKU-P6-A", 4);
    Part fluid = givenPart("SKU-P6-B", 1);
    var response = purchaseService.create(request(supplier.getId(),
    line(pads.getId(), 6, "300.00"),
    line(fluid.getId(), 9, "125.50")));

    var received = purchaseService.receive(response.getId());

    assertThat(received.getStatus()).isEqualTo(PurchaseServiceImpl.STATUS_RECEIVED);
    assertThat(partRepository.findById(pads.getId()).orElseThrow().getStockQty()).isEqualTo(10);
    assertThat(partRepository.findById(fluid.getId()).orElseThrow().getStockQty()).isEqualTo(10);

    assertThat(movementRepository.findByPartIdOrderByCreatedAtDescIdDesc(pads.getId()))
    .hasSize(1)
    .first()
    .satisfies(m -> {
    assertThat(m.getMovementType()).isEqualTo("IN");
    assertThat(m.getQuantity()).isEqualTo(6);
    assertThat(m.getStockBefore()).isEqualTo(4);
    assertThat(m.getStockAfter()).isEqualTo(10);
    assertThat(m.getReason()).isEqualTo(PurchaseServiceImpl.REASON_PURCHASE_RECEIVED);
    assertThat(m.getReference()).isEqualTo("PURCHASE-" + response.getId());
    assertThat(m.getJobCard()).isNull();
    });
    assertThat(movementRepository.findByPartIdOrderByCreatedAtDescIdDesc(fluid.getId())).hasSize(1);
    }

    @Test
    @DisplayName("PP7 - a purchase cannot be received twice; the second attempt is refused")
    void pp7_receiveTwice_rejected() {
    Supplier supplier = givenSupplier("Bharat Auto");
    Part part = givenPart("SKU-P7", 0);
    var response = purchaseService.create(request(supplier.getId(), line(part.getId(), 5, "300.00")));

    purchaseService.receive(response.getId());

    assertThatThrownBy(() -> purchaseService.receive(response.getId()))
    .isInstanceOf(BusinessRuleException.class)
    .hasMessageContaining("already been received");

    // Stock moved once, not twice.
    assertThat(partRepository.findById(part.getId()).orElseThrow().getStockQty()).isEqualTo(5);
    assertThat(movementRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("PP8 - receiving an unknown purchase is refused")
    void pp8_receiveUnknownPurchase_rejected() {
    assertThatThrownBy(() -> purchaseService.receive(999L))
    .isInstanceOf(ResourceNotFoundException.class)
    .hasMessageContaining("Purchase not found: 999");
    }

    @Test
    @DisplayName("PP9 - receiving replenishes a low-stock part, preserving the low-stock rule")
    void pp9_receive_clearsLowStock() {
    Supplier supplier = givenSupplier("Bharat Auto");
    Part part = givenPart("SKU-P9", 1);
    long lowBefore = partRepository.countLowStock();
    assertThat(partRepository.findLowStock()).extracting(Part::getSku).contains("SKU-P9");

    var response = purchaseService.create(request(supplier.getId(), line(part.getId(), 20, "300.00")));
    purchaseService.receive(response.getId());

    assertThat(partRepository.countLowStock()).isEqualTo(lowBefore - 1);
    assertThat(partRepository.findLowStock()).extracting(Part::getSku).doesNotContain("SKU-P9");
    }

    // ── Rollback: a rejected operation leaves nothing behind ─────────────

    /**
     * Rolled back by the service's own transaction.
     *
     * <p>{@code @DataJpaTest} wraps each test in one transaction, which the service
     * joins rather than starting its own - so a failure inside the service could
     * not be observed rolling anything back. Suspending the ambient transaction
     * with NOT_SUPPORTED lets {@code @Transactional} on the service start, and roll
     * back, a real transaction of its own.
     *
     * <p>Because the fixtures written here are committed for real, each of these
     * tests uses its own supplier name and asserts on the row counts relative to
     * what was there before, rather than on absolute totals that another test in
     * this class may have changed.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("PP10 - a purchase with an invalid line leaves no purchase and no items behind")
    void pp10_invalidLine_rollsBackPurchase() {
    Supplier supplier = givenSupplier("Rollback Supplier 10");
    Part part = givenPart("SKU-P10", 0);
    long purchasesBefore = purchaseRepository.count();
    long itemsBefore = itemRepository.count();

    assertThatThrownBy(() -> purchaseService.create(request(supplier.getId(),
    line(part.getId(), 1, "100.00"),
    line(part.getId(), 0, "100.00"))))
    .isInstanceOf(BusinessRuleException.class);

    assertThat(purchaseRepository.count()).isEqualTo(purchasesBefore);
    assertThat(itemRepository.count()).isEqualTo(itemsBefore);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("PP11 - an unknown part rolls the whole purchase back, no half-written order")
    void pp11_unknownPart_rollsBackPurchase() {
    Supplier supplier = givenSupplier("Rollback Supplier 11");
    Part known = givenPart("SKU-P11-A", 0);
    long purchasesBefore = purchaseRepository.count();
    long itemsBefore = itemRepository.count();

    assertThatThrownBy(() -> purchaseService.create(request(supplier.getId(),
    line(known.getId(), 2, "100.00"),
    line(999999L, 2, "100.00"))))
    .isInstanceOf(ResourceNotFoundException.class);

    assertThat(purchaseRepository.count()).isEqualTo(purchasesBefore);
    assertThat(itemRepository.count()).isEqualTo(itemsBefore);
    }

    /**
     * The important one: a failure on the SECOND line must undo the first line's
     * stock increase.
     *
     * <p>The stock-movement service is spied so that its first call behaves
     * normally - really increasing the first part's stock - and its second throws.
     * That is exactly the shape of a mid-receipt failure (a deleted part, a stock
     * conflict, a write error). If receiving were not one transaction, or if the
     * purchasing service had updated stock itself outside the stock-movement
     * service, the first line's increase would survive and the order would show
     * goods received that never arrived.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("PP12 - a failure part-way through a receipt rolls back every line's stock")
    void pp12_receiveFailure_rollsBackWholeReceipt() {
    Supplier supplier = givenSupplier("Rollback Supplier");
    Part first = givenPart("SKU-P12-A", 2);
    Part second = givenPart("SKU-P12-B", 5);
    var response = purchaseService.create(request(supplier.getId(),
line(first.getId(), 10, "100.00"),
line(second.getId(), 3, "100.00")));

// Real behaviour for the first line, a failure for the second.
org.mockito.Mockito.doCallRealMethod()
.doThrow(new ResourceNotFoundException("Part not found: " + second.getId()))
.when(stockMovementService)
.create(any(com.autoservicehub.dto.StockMovementRequestDTO.class));

assertThatThrownBy(() -> purchaseService.receive(response.getId()))
.isInstanceOf(ResourceNotFoundException.class);

// Neither line moved stock, and no movement row survived.
assertThat(partRepository.findById(first.getId()).orElseThrow().getStockQty()).isEqualTo(2);
assertThat(partRepository.findById(second.getId()).orElseThrow().getStockQty()).isEqualTo(5);
assertThat(movementRepository.count()).isZero();
// And the order is still PENDING, so the receipt can be retried.
assertThat(purchaseRepository.findById(response.getId()).orElseThrow().getStatus())
.isEqualTo(PurchaseServiceImpl.STATUS_PENDING);
}

private com.autoservicehub.dto.SupplierRequestDTO supplierRequest(String name) {
com.autoservicehub.dto.SupplierRequestDTO dto =
new com.autoservicehub.dto.SupplierRequestDTO();
        dto.setName(name);
        dto.setPhone("+91 98000 00000");
        dto.setEmail("sales@" + name.replace(" ", "").toLowerCase() + ".example");
        dto.setAddress("Industrial Estate");
        return dto;
    }
}
