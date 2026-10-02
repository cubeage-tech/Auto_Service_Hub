package com.autoservicehub.service;

import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Part;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.StockMovementRepository;
import com.autoservicehub.service.impl.StockMovementServiceImpl;
import com.autoservicehub.service.impl.AuditServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for stock movement persistence against a real database.
 *
 * <p>These cover what a Mockito unit test cannot: that the movement and the
 * part's stock are written together, that a rejected movement leaves NO trace
 * (transaction rollback), and that the low-stock query really compares each
 * part against its own minimum.
 *
 * <p>Uses {@code @DataJpaTest}, which is the right slice here: it wires the
 * JPA repositories and {@code @Service} beans against an embedded database
 * with a schema created for this slice, and rolls each test back. The full
 * {@code @SpringBootTest} was avoided because application-test.yml points every
 * context at one shared named H2 database with {@code create-drop}, so one
 * context shutting down drops the schema out from under the next.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stock-movement-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // application.yml pins MySQLDialect for the real MySQL deployment, but
        // the MySQL DDL it generates is not executable on H2, so the schema
        // never gets created. Select the dialect from the actual JDBC metadata.
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuditServiceImpl.class, StockMovementServiceImpl.class})
class StockMovementPersistenceTest {

    @Autowired StockMovementServiceImpl service;
    @Autowired PartRepository           partRepository;
    @Autowired StockMovementRepository movementRepository;
    @Autowired JobCardRepository        jobCardRepository;

    private Part givenPart(String sku, int stock, int minStock) {
        Part p = new Part();
        p.setSku(sku);
        p.setName("Part " + sku);
        p.setUnit("PCS");
        p.setSellingPrice(new BigDecimal("500.00"));
        p.setPurchasePrice(new BigDecimal("300.00"));
        p.setStockQty(stock);
        p.setMinStock(minStock);
        return partRepository.save(p);
    }

    private JobCard givenJobCard() {
        JobCard jc = new JobCard();
        jc.setJobCardNumber("JC-TEST-1");
        jc.setServiceType("BRAKE_SERVICE");
        jc.setStatus("IN_REPAIR");
        return jobCardRepository.save(jc);
    }

    // ── Stock changes are persisted together with the ledger row ──────────

    @Test
    @DisplayName("P1 — an IN movement persists both the new stock and the ledger row")
    void p1_inMovement_persistsStockAndLedger() {
        Part p = givenPart("SKU-IN", 10, 3);

        service.create(movementRequest(p.getId(), "IN", 5, null));

        assertThat(partRepository.findById(p.getId()).orElseThrow().getStockQty()).isEqualTo(15);
        assertThat(movementRepository.findByPartIdOrderByCreatedAtDescIdDesc(p.getId()))
                .hasSize(1)
                .first()
                .satisfies(m -> {
                    assertThat(m.getMovementType()).isEqualTo("IN");
                    assertThat(m.getStockBefore()).isEqualTo(10);
                    assertThat(m.getStockAfter()).isEqualTo(15);
                    assertThat(m.getPart()).isNotNull();
                });
    }

    @Test
    @DisplayName("P2 — an OUT movement reduces persisted stock and records the movement")
    void p2_outMovement_persistsStockAndLedger() {
        Part p = givenPart("SKU-OUT", 10, 3);

        service.create(movementRequest(p.getId(), "OUT", 4, null));

        assertThat(partRepository.findById(p.getId()).orElseThrow().getStockQty()).isEqualTo(6);
        assertThat(movementRepository.findByPartIdOrderByCreatedAtDescIdDesc(p.getId()))
                .hasSize(1)
                .first()
                .satisfies(m -> assertThat(m.getStockAfter()).isEqualTo(6));
    }

    @Test
    @DisplayName("P3 — an ADJUSTMENT applies a signed delta and persists the new balance")
    void p3_adjustment_persistsSignedDelta() {
        Part p = givenPart("SKU-ADJ", 10, 3);

        service.create(movementRequest(p.getId(), "ADJUSTMENT", 4, -4));

        assertThat(partRepository.findById(p.getId()).orElseThrow().getStockQty()).isEqualTo(6);
        assertThat(movementRepository.findByPartIdOrderByCreatedAtDescIdDesc(p.getId()))
                .first()
                .satisfies(m -> {
                    assertThat(m.getAdjustmentDelta()).isEqualTo(-4);
                    assertThat(m.getStockAfter()).isEqualTo(6);
                });
    }

    // ── Rollback: a rejected movement must leave no trace ─────────────────

    @Test
    @DisplayName("P4 — a rejected OUT writes no ledger row and leaves stock untouched")
    void p4_rejectedOut_writesNothing() {
        Part p = givenPart("SKU-ROLLBACK", 2, 3);
        long movementsBefore = movementRepository.count();

        assertThatThrownBy(() -> service.create(movementRequest(p.getId(), "OUT", 5, null)))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(movementRepository.count()).isEqualTo(movementsBefore);
        assertThat(partRepository.findById(p.getId()).orElseThrow().getStockQty()).isEqualTo(2);
    }

    @Test
    @DisplayName("P5 — a rejected ADJUSTMENT below zero writes no ledger row")
    void p5_rejectedAdjustment_writesNothing() {
        Part p = givenPart("SKU-ADJ-ROLLBACK", 1, 3);
        long movementsBefore = movementRepository.count();

        assertThatThrownBy(() -> service.create(movementRequest(p.getId(), "ADJUSTMENT", 9, -9)))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(movementRepository.count()).isEqualTo(movementsBefore);
        assertThat(partRepository.findById(p.getId()).orElseThrow().getStockQty()).isEqualTo(1);
    }

    // ── Job-card consumption persists against the real job card ───────────

    @Test
    @DisplayName("P6 — consumption links the movement to the job card and reduces stock")
    void p6_consume_persistsLinkedMovement() {
        Part p = givenPart("SKU-CONSUME", 10, 3);
        JobCard jc = givenJobCard();

        var response = service.consumeForJobCard(jc.getId(), p.getId(), 3, null);

        assertThat(response.getJobCardId()).isEqualTo(jc.getId());
        assertThat(partRepository.findById(p.getId()).orElseThrow().getStockQty()).isEqualTo(7);

        assertThat(movementRepository.findByJobCardIdOrderByIdAsc(jc.getId()))
                .hasSize(1)
                .first()
                .satisfies(m -> {
                    assertThat(m.getMovementType()).isEqualTo("OUT");
                    assertThat(m.getQuantity()).isEqualTo(3);
                    assertThat(m.getReference()).isEqualTo("JC-TEST-1");
                    assertThat(m.getReason()).isEqualTo(StockMovementServiceImpl.REASON_PART_CONSUMED);
                });
    }

    @Test
    @DisplayName("P7 — consuming two parts records two movements on the same job card")
    void p7_consumeTwoParts_recordsBothMovements() {
        Part pads  = givenPart("SKU-PAD", 10, 3);
        Part fluid = givenPart("SKU-FLUID", 5, 2);
        JobCard jc = givenJobCard();

        service.consumeForJobCard(jc.getId(), pads.getId(), 2, null);
        service.consumeForJobCard(jc.getId(), fluid.getId(), 1, null);

        assertThat(movementRepository.findByJobCardIdOrderByIdAsc(jc.getId())).hasSize(2);
        assertThat(partRepository.findById(pads.getId()).orElseThrow().getStockQty()).isEqualTo(8);
        assertThat(partRepository.findById(fluid.getId()).orElseThrow().getStockQty()).isEqualTo(4);
    }

    // ── Low stock uses each part's own minimum ────────────────────────────

    @Test
    @DisplayName("P8 — low-stock count uses each part's own minStock, not a hard-coded zero")
    void p8_lowStockRespectsPerPartMinimum() {
        givenPart("SKU-OK",     50, 5);   // well above its minimum
        givenPart("SKU-AT-MIN",  5, 5);   // exactly at its minimum  → low
        givenPart("SKU-BELOW",   1, 10);  // below its minimum      → low
        givenPart("SKU-ZERO",    0, 0);   // zero stock, zero min   → low

        // 3 of the 4 seeded parts are at or below their own minimum.
        assertThat(partRepository.countLowStock()).isEqualTo(3);
        assertThat(partRepository.findLowStock())
                .extracting(Part::getSku)
                .containsExactlyInAnyOrder("SKU-AT-MIN", "SKU-BELOW", "SKU-ZERO");
    }

    @Test
    @DisplayName("P9 — a part below its minimum is no longer low once replenished")
    void p9_replenishmentClearsLowStock() {
        Part p = givenPart("SKU-REPLENISH", 1, 10);
        long lowBefore = partRepository.countLowStock();
        assertThat(lowBefore).isPositive();

        service.create(movementRequest(p.getId(), "IN", 20, null));

        assertThat(partRepository.countLowStock()).isEqualTo(lowBefore - 1);
        assertThat(partRepository.findById(p.getId()).orElseThrow().getStockQty()).isEqualTo(21);
    }

    @Test
    @DisplayName("P10 — consuming stock can push a part into low-stock status")
    void p10_consumptionCanTriggerLowStock() {
        Part p = givenPart("SKU-CONSUME-LOW", 12, 5);
        long lowBefore = partRepository.countLowStock();

        service.consumeForJobCard(givenJobCard().getId(), p.getId(), 10, null);

        assertThat(partRepository.findById(p.getId()).orElseThrow().getStockQty()).isEqualTo(2);
        assertThat(partRepository.countLowStock()).isEqualTo(lowBefore + 1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private com.autoservicehub.dto.StockMovementRequestDTO movementRequest(
            Long partId, String type, Integer quantity, Integer adjustmentDelta) {
        com.autoservicehub.dto.StockMovementRequestDTO req =
                new com.autoservicehub.dto.StockMovementRequestDTO();
        req.setPartId(partId);
        req.setMovementType(type);
        req.setQuantity(quantity);
        req.setAdjustmentDelta(adjustmentDelta);
        return req;
    }
}
