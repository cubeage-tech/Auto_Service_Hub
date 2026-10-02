package com.autoservicehub.service;

import com.autoservicehub.dto.StockMovementRequestDTO;
import com.autoservicehub.dto.StockMovementResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Part;
import com.autoservicehub.entity.StockMovement;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.StockMovementRepository;
import com.autoservicehub.service.impl.StockMovementServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StockMovementServiceImpl} — stock calculation and
 * job-card part consumption.
 * Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * S1  IN movement            → stock increases
 * S2  OUT movement           → stock decreases
 * S3  ADJUSTMENT positive    → stock increases by the delta
 * S4  ADJUSTMENT negative    → stock decreases by the delta
 * S5  ADJUSTMENT no delta    → BusinessRuleException
 * S6  ADJUSTMENT below zero  → BusinessRuleException, nothing saved
 * S7  OUT insufficient stock → BusinessRuleException, nothing saved
 * S8  OUT zero quantity      → BusinessRuleException
 * S9  OUT negative quantity  → BusinessRuleException
 * S10 Unknown part           → ResourceNotFoundException
 * S11 Unknown job card       → ResourceNotFoundException
 * S12 Unknown movement type  → BusinessRuleException
 * S13 Movement type is case-insensitive
 * S14 Movement records stockBefore / stockAfter
 * S15 Job-card consumption   → OUT movement linked to the job card, stock reduced
 * S16 Consumption insufficient stock → BusinessRuleException, nothing saved
 * S17 Consumption unknown part → ResourceNotFoundException
 * S18 Consumption zero quantity → BusinessRuleException
 * S19 Consumption defaults reason to PART_CONSUMED
 * S20 Ledger row and part are both written (single unit of work)
 * S21 Null stock is treated as zero
 */
@ExtendWith(MockitoExtension.class)
class StockMovementServiceImplTest {

    @Mock StockMovementRepository repository;
    @Mock PartRepository           partRepository;
    @Mock JobCardRepository        jobCardRepository;
    @Mock AuditService auditService;

    @InjectMocks
    StockMovementServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private Part part(Long id, Integer stockQty, Integer minStock) {
        Part p = new Part();
        p.setId(id);
        p.setSku("BRK-PAD-01");
        p.setName("Front Brake Pad Set");
        p.setStockQty(stockQty);
        p.setMinStock(minStock);
        return p;
    }

    private JobCard jobCard(Long id) {
        JobCard jc = new JobCard();
        jc.setId(id);
        jc.setJobCardNumber("JC-20260930120000");
        jc.setStatus("IN_REPAIR");
        return jc;
    }

    private StockMovementRequestDTO request(String type, Integer quantity) {
        StockMovementRequestDTO req = new StockMovementRequestDTO();
        req.setPartId(1L);
        req.setMovementType(type);
        req.setQuantity(quantity);
        return req;
    }

    /** Captures the movement handed to the repository and assigns it an id. */
    private void givenMovementSaved() {
        when(repository.save(any(StockMovement.class))).thenAnswer(inv -> {
            StockMovement m = inv.getArgument(0);
            m.setId(500L);
            return m;
        });
    }

    // ── S1: IN increases stock ────────────────────────────────────────────

    @Test
    @DisplayName("S1 — IN movement increases the part's stock")
    void s1_inMovement_increasesStock() {
        Part p = part(1L, 10, 3);
        when(partRepository.findById(1L)).thenReturn(Optional.of(p));
        givenMovementSaved();

        StockMovementResponseDTO response = service.create(request("IN", 5));

        assertThat(p.getStockQty()).isEqualTo(15);
        assertThat(response.getStockBefore()).isEqualTo(10);
        assertThat(response.getStockAfter()).isEqualTo(15);
        assertThat(response.getMovementType()).isEqualTo(StockMovementServiceImpl.TYPE_IN);
        assertThat(response.getQuantity()).isEqualTo(5);
    }

    // ── S2: OUT decreases stock ───────────────────────────────────────────

    @Test
    @DisplayName("S2 — OUT movement decreases the part's stock")
    void s2_outMovement_decreasesStock() {
        Part p = part(1L, 10, 3);
        when(partRepository.findById(1L)).thenReturn(Optional.of(p));
        givenMovementSaved();

        StockMovementResponseDTO response = service.create(request("OUT", 4));

        assertThat(p.getStockQty()).isEqualTo(6);
        assertThat(response.getStockAfter()).isEqualTo(6);
    }

    // ── S3 / S4: ADJUSTMENT ───────────────────────────────────────────────

    @Test
    @DisplayName("S3 — ADJUSTMENT with a positive delta increases stock")
    void s3_adjustmentPositive_increasesStock() {
        Part p = part(1L, 10, 3);
        when(partRepository.findById(1L)).thenReturn(Optional.of(p));
        givenMovementSaved();

        StockMovementRequestDTO req = request("ADJUSTMENT", 3);
        req.setAdjustmentDelta(3);

        StockMovementResponseDTO response = service.create(req);

        assertThat(p.getStockQty()).isEqualTo(13);
        assertThat(response.getAdjustmentDelta()).isEqualTo(3);
    }

    @Test
    @DisplayName("S4 — ADJUSTMENT with a negative delta decreases stock")
    void s4_adjustmentNegative_decreasesStock() {
        Part p = part(1L, 10, 3);
        when(partRepository.findById(1L)).thenReturn(Optional.of(p));
        givenMovementSaved();

        StockMovementRequestDTO req = request("ADJUSTMENT", 2);
        req.setAdjustmentDelta(-2);

        StockMovementResponseDTO response = service.create(req);

        assertThat(p.getStockQty()).isEqualTo(8);
        assertThat(response.getStockAfter()).isEqualTo(8);
        // quantity stays a positive magnitude; direction lives in the delta.
        assertThat(response.getQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("S5 — ADJUSTMENT without a delta → BusinessRuleException")
    void s5_adjustmentWithoutDelta_throws() {
        when(partRepository.findById(1L)).thenReturn(Optional.of(part(1L, 10, 3)));

        assertThatThrownBy(() -> service.create(request("ADJUSTMENT", 3)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("adjustmentDelta is required");

        verify(repository, never()).save(any(StockMovement.class));
    }

    @Test
    @DisplayName("S5b — ADJUSTMENT with a zero delta → BusinessRuleException")
    void s5b_adjustmentZeroDelta_throws() {
        when(partRepository.findById(1L)).thenReturn(Optional.of(part(1L, 10, 3)));

        StockMovementRequestDTO req = request("ADJUSTMENT", 3);
        req.setAdjustmentDelta(0);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must not be zero");
    }

    @Test
    @DisplayName("S5c — ADJUSTMENT magnitude must match quantity")
    void s5c_adjustmentMagnitudeMismatch_throws() {
        when(partRepository.findById(1L)).thenReturn(Optional.of(part(1L, 10, 3)));

        StockMovementRequestDTO req = request("ADJUSTMENT", 3);
        req.setAdjustmentDelta(-5);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("magnitude of adjustmentDelta");
    }

    @Test
    @DisplayName("S6 — ADJUSTMENT that would go below zero → BusinessRuleException, nothing saved")
    void s6_adjustmentBelowZero_throws() {
        Part p = part(1L, 2, 3);
        when(partRepository.findById(1L)).thenReturn(Optional.of(p));

        StockMovementRequestDTO req = request("ADJUSTMENT", 5);
        req.setAdjustmentDelta(-5);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("below zero");

        assertThat(p.getStockQty()).isEqualTo(2);            // unchanged
        verify(repository, never()).save(any(StockMovement.class));
        verify(partRepository, never()).save(any(Part.class));
    }

    // ── S7: Insufficient stock ────────────────────────────────────────────

    @Test
    @DisplayName("S7 — OUT beyond available stock → BusinessRuleException, nothing saved")
    void s7_outInsufficientStock_throws() {
        Part p = part(1L, 2, 3);
        when(partRepository.findById(1L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.create(request("OUT", 5)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient stock");

        assertThat(p.getStockQty()).isEqualTo(2);
        verify(repository, never()).save(any(StockMovement.class));
        verify(partRepository, never()).save(any(Part.class));
    }

    // ── S8 / S9: invalid quantity ────────────────────────────────────────

    @Test
    @DisplayName("S8 — zero quantity → BusinessRuleException")
    void s8_zeroQuantity_throws() {
        assertThatThrownBy(() -> service.create(request("OUT", 0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("quantity must be greater than 0");

        verify(repository, never()).save(any(StockMovement.class));
    }

    @Test
    @DisplayName("S9 — negative quantity → BusinessRuleException")
    void s9_negativeQuantity_throws() {
        assertThatThrownBy(() -> service.create(request("IN", -3)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("quantity must be greater than 0");
    }

    // ── S10 / S11: unknown references ─────────────────────────────────────

    @Test
    @DisplayName("S10 — unknown part → ResourceNotFoundException")
    void s10_unknownPart_throws() {
        when(partRepository.findById(99L)).thenReturn(Optional.empty());

        StockMovementRequestDTO req = request("IN", 1);
        req.setPartId(99L);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Part not found: 99");
    }

    @Test
    @DisplayName("S11 — unknown job card → ResourceNotFoundException")
    void s11_unknownJobCard_throws() {
        when(partRepository.findById(1L)).thenReturn(Optional.of(part(1L, 10, 3)));
        when(jobCardRepository.findById(99L)).thenReturn(Optional.empty());

        StockMovementRequestDTO req = request("OUT", 1);
        req.setJobCardId(99L);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobCard not found: 99");
    }

    // ── S12 / S13: movement type handling ─────────────────────────────────

    @Test
    @DisplayName("S12 — unsupported movement type → BusinessRuleException")
    void s12_unsupportedType_throws() {
        assertThatThrownBy(() -> service.create(request("TRANSFER", 1)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unsupported movementType");
    }

    @Test
    @DisplayName("S13 — movement type is trimmed and upper-cased")
    void s13_typeIsNormalised() {
        when(partRepository.findById(1L)).thenReturn(Optional.of(part(1L, 10, 3)));
        givenMovementSaved();

        assertThat(service.create(request(" in ", 2)).getMovementType()).isEqualTo("IN");
    }

    // ── S14: ledger captures the resulting balance ─────────────────────────

    @Test
    @DisplayName("S14 — the movement row records stockBefore, stockAfter and the part")
    void s14_movementRecordsBalance() {
        when(partRepository.findById(1L)).thenReturn(Optional.of(part(1L, 10, 3)));
        givenMovementSaved();

        service.create(request("OUT", 3));

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(repository).save(captor.capture());
        StockMovement m = captor.getValue();

        assertThat(m.getStockBefore()).isEqualTo(10);
        assertThat(m.getStockAfter()).isEqualTo(7);
        assertThat(m.getPart()).isNotNull();
        assertThat(m.getPart().getId()).isEqualTo(1L);
    }

    // ── S15: Job-card part consumption ────────────────────────────────────

    @Test
    @DisplayName("S15 — consuming a part for a job card records an OUT movement and reduces stock")
    void s15_consumeForJobCard_recordsOutAndReducesStock() {
        Part p = part(1L, 10, 3);
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        when(partRepository.findById(1L)).thenReturn(Optional.of(p));
        givenMovementSaved();

        StockMovementResponseDTO response = service.consumeForJobCard(55L, 1L, 4, null);

        assertThat(p.getStockQty()).isEqualTo(6);
        assertThat(response.getMovementType()).isEqualTo(StockMovementServiceImpl.TYPE_OUT);
        assertThat(response.getJobCardId()).isEqualTo(55L);
        assertThat(response.getJobCardNumber()).isEqualTo("JC-20260930120000");
        assertThat(response.getPartId()).isEqualTo(1L);
        assertThat(response.getStockBefore()).isEqualTo(10);
        assertThat(response.getStockAfter()).isEqualTo(6);
    }

    @Test
    @DisplayName("S16 — consumption beyond available stock → BusinessRuleException, nothing saved")
    void s16_consumeInsufficientStock_throws() {
        Part p = part(1L, 1, 3);
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        when(partRepository.findById(1L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.consumeForJobCard(55L, 1L, 3, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient stock");

        assertThat(p.getStockQty()).isEqualTo(1);
        verify(repository, never()).save(any(StockMovement.class));
        verify(partRepository, never()).save(any(Part.class));
    }

    @Test
    @DisplayName("S17 — consumption of an unknown part → ResourceNotFoundException")
    void s17_consumeUnknownPart_throws() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        when(partRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consumeForJobCard(55L, 99L, 1, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Part not found: 99");

        verify(repository, never()).save(any(StockMovement.class));
    }

    @Test
    @DisplayName("S17b — consumption against an unknown job card → ResourceNotFoundException")
    void s17b_consumeUnknownJobCard_throws() {
        when(jobCardRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consumeForJobCard(99L, 1L, 1, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobCard not found: 99");
    }

    @Test
    @DisplayName("S18 — consumption with a zero quantity → BusinessRuleException")
    void s18_consumeZeroQuantity_throws() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        when(partRepository.findById(1L)).thenReturn(Optional.of(part(1L, 10, 3)));

        assertThatThrownBy(() -> service.consumeForJobCard(55L, 1L, 0, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("quantity must be greater than 0");

        verify(repository, never()).save(any(StockMovement.class));
    }

    @Test
    @DisplayName("S19 — consumption defaults the reason to PART_CONSUMED and keeps a supplied reason")
    void s19_consumeReasonHandling() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        when(partRepository.findById(1L)).thenReturn(Optional.of(part(1L, 10, 3)));
        givenMovementSaved();

        assertThat(service.consumeForJobCard(55L, 1L, 1, null).getReason())
                .isEqualTo(StockMovementServiceImpl.REASON_PART_CONSUMED);

        assertThat(service.consumeForJobCard(55L, 1L, 1, "Replaced front pads").getReason())
                .isEqualTo("Replaced front pads");
    }

    // ── S20 / S21 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("S20 — a consumption writes both the ledger row and the part in one unit of work")
    void s20_consumeWritesLedgerAndPart() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        when(partRepository.findById(1L)).thenReturn(Optional.of(part(1L, 10, 3)));
        givenMovementSaved();

        service.consumeForJobCard(55L, 1L, 2, null);

        verify(repository).save(any(StockMovement.class));
        verify(partRepository).save(any(Part.class));
    }

    @Test
    @DisplayName("S21 — a part with null stock is treated as having zero on hand")
    void s21_nullStockTreatedAsZero() {
        // Null stock must behave as zero, so a consumption cannot proceed and an
        // IN movement starts from zero rather than throwing.
        Part p = part(1L, null, 3);
        when(partRepository.findById(1L)).thenReturn(Optional.of(p));
        givenMovementSaved();

        StockMovementResponseDTO response = service.create(request("IN", 4));

        assertThat(response.getStockBefore()).isZero();
        assertThat(p.getStockQty()).isEqualTo(4);
    }

    @Test
    @DisplayName("S21b — consuming from a null-stock part is rejected as insufficient")
    void s21b_nullStockCannotBeConsumed() {
        Part p = part(1L, null, 3);
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        when(partRepository.findById(1L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.consumeForJobCard(55L, 1L, 1, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("only 0 on hand");
    }
}
