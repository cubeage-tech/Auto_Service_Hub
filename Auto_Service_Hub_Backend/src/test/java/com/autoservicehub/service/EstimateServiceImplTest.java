package com.autoservicehub.service;

import com.autoservicehub.dto.EstimateItemRequestDTO;
import com.autoservicehub.dto.EstimateRequestDTO;
import com.autoservicehub.dto.EstimateResponseDTO;
import com.autoservicehub.entity.Estimate;
import com.autoservicehub.entity.EstimateItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.EstimateItemRepository;
import com.autoservicehub.repository.EstimateRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.service.impl.EstimateServiceImpl;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EstimateServiceImpl} — relationships, server-side
 * calculation and the estimate workflow.
 * Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * E1  create with items        → job card linked, totals calculated server-side
 * E2  client-supplied totals   → ignored, server values win
 * E3  discount applied before tax
 * E4  unknown job card         → ResourceNotFoundException
 * E5  zero quantity            → BusinessRuleException, nothing persisted
 * E6  negative unit price      → BusinessRuleException
 * E7  discount above subtotal  → BusinessRuleException
 * E8  update replaces items and recalculates
 * E9  converted estimate cannot be modified
 * E10 getById returns items
 * E11 delete removes the estimate's items
 * E12 listByJobCard validates the job card
 * E13 no discount             → tax on the full subtotal
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EstimateServiceImplTest {

    @Mock EstimateRepository     repository;
    @Mock EstimateItemRepository itemRepository;
    @Mock JobCardRepository      jobCardRepository;
    @Mock AuditService auditService;

    /** Real calculator, so the money rules under test are the production ones. */
    @Spy
    private BillingCalculator calculator = new BillingCalculator(new BigDecimal("18"));

    @InjectMocks
    EstimateServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private JobCard jobCard(Long id) {
        JobCard jc = new JobCard();
        jc.setId(id);
        jc.setJobCardNumber("JC-20260930120000");
        return jc;
    }

    private EstimateItemRequestDTO item(String description, int qty, String unitPrice) {
        EstimateItemRequestDTO dto = new EstimateItemRequestDTO();
        dto.setDescription(description);
        dto.setQuantity(qty);
        dto.setUnitPrice(new BigDecimal(unitPrice));
        return dto;
    }

    private EstimateRequestDTO request() {
        EstimateRequestDTO req = new EstimateRequestDTO();
        req.setJobCardId(55L);
        return req;
    }

    /**
     * Gives the saved estimate an id, and makes the written lines readable back
     * so {@code recalculate} can total them the way the database would.
     */
    private void givenPersisted() {
        when(repository.save(any(Estimate.class))).thenAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(42L);
            }
            return e;
        });

        List<EstimateItem> written = new java.util.ArrayList<>();
        when(itemRepository.save(any(EstimateItem.class))).thenAnswer(inv -> {
            EstimateItem saved = inv.getArgument(0);
            saved.setId((long) (written.size() + 1));
            written.add(saved);
            return saved;
        });
        when(itemRepository.findByEstimateIdOrderByIdAsc(42L))
                .thenAnswer(inv -> new java.util.ArrayList<>(written));
    }

    // ── E1 / E2: creation and server-side calculation ─────────────────────

    @Test
    @DisplayName("E1 — create links the job card and calculates totals from the line items")
    void e1_create_linksJobCardAndCalculates() {
        EstimateRequestDTO req = request();
        req.setItems(List.of(
                item("Brake pads", 2, "500.00"),
                item("Brake fluid", 1, "300.00")));

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();

        EstimateResponseDTO response = service.create(req);

        assertThat(response.getJobCardId()).isEqualTo(55L);
        assertThat(response.getJobCardNumber()).isEqualTo("JC-20260930120000");
        // 2×500 + 1×300 = 1300
        assertThat(response.getSubtotal()).isEqualByComparingTo("1300.00");
        // no discount → tax on the full subtotal: 1300 × 18% = 234
        assertThat(response.getTax()).isEqualByComparingTo("234.00");
        assertThat(response.getTotal()).isEqualByComparingTo("1534.00");
        assertThat(response.getStatus()).isEqualTo("DRAFT");
        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getLineAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("E2 — client-supplied subtotal/tax/total are ignored and recalculated")
    void e2_clientTotalsAreIgnored() {
        EstimateRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 2, "500.00")));
        // A tampered client tries to dictate the numbers.
        req.setSubtotal(new BigDecimal("1.00"));
        req.setTax(new BigDecimal("0.01"));
        req.setTotal(new BigDecimal("1.00"));

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();

        EstimateResponseDTO response = service.create(req);

        assertThat(response.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(response.getTax()).isEqualByComparingTo("180.00");
        assertThat(response.getTotal()).isEqualByComparingTo("1180.00");
    }

    @Test
    @DisplayName("E3 — the discount is applied before tax, reducing the tax base")
    void e3_discountAppliedBeforeTax() {
        EstimateRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 1, "1000.00")));
        req.setDiscount(new BigDecimal("100.00"));

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();

        EstimateResponseDTO response = service.create(req);

        assertThat(response.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(response.getDiscount()).isEqualByComparingTo("100.00");
        assertThat(response.getTax()).isEqualByComparingTo("162.00");   // 900 × 18%
        assertThat(response.getTotal()).isEqualByComparingTo("1062.00");
    }

    @Test
    @DisplayName("E13 — with no discount, tax is charged on the whole subtotal")
    void e13_noDiscount_taxOnFullSubtotal() {
        EstimateRequestDTO req = request();
        req.setItems(List.of(item("Labour", 4, "250.00")));   // 1000

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();

        EstimateResponseDTO response = service.create(req);

        assertThat(response.getDiscount()).isEqualByComparingTo("0.00");
        assertThat(response.getTax()).isEqualByComparingTo("180.00");
        assertThat(response.getTotal()).isEqualByComparingTo("1180.00");
    }

    // ── E4: unknown job card ──────────────────────────────────────────────

    @Test
    @DisplayName("E4 — unknown jobCardId → ResourceNotFoundException, nothing persisted")
    void e4_unknownJobCard_throws() {
        EstimateRequestDTO req = request();
        req.setJobCardId(99L);
        req.setItems(List.of(item("Any", 1, "10.00")));

        when(jobCardRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobCard not found: 99");

        verify(repository, never()).save(any(Estimate.class));
    }

    // ── E5 / E6 / E7: invalid line items and discount ─────────────────────

    @Test
    @DisplayName("E5 — zero quantity → BusinessRuleException, estimate not saved")
    void e5_zeroQuantity_throws() {
        EstimateRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 0, "500.00")));

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("quantity must be greater than 0");

        verify(repository, never()).save(any(Estimate.class));
    }

    @Test
    @DisplayName("E6 — negative unit price → BusinessRuleException")
    void e6_negativeUnitPrice_throws() {
        EstimateRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 1, "-500.00")));

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("unitPrice must not be negative");
    }

    @Test
    @DisplayName("E7 — discount greater than the calculated subtotal → BusinessRuleException")
    void e7_discountAboveSubtotal_throws() {
        EstimateRequestDTO req = request();
        req.setItems(List.of(item("Brake pads", 1, "100.00")));
        req.setDiscount(new BigDecimal("500.00"));

        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot exceed the subtotal");
    }

    // ── E8: update replaces items and recalculates ────────────────────────

    @Test
    @DisplayName("E8 — update replaces the items and recalculates the totals")
    void e8_update_replacesItemsAndRecalculates() {
        Estimate existing = new Estimate();
        existing.setId(42L);
        existing.setJobCard(jobCard(55L));
        existing.setStatus("SENT");

        EstimateRequestDTO req = request();
        req.setItems(List.of(item("Additional work", 3, "200.00")));   // 600

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenPersisted();

        EstimateResponseDTO response = service.update(42L, req);

        assertThat(response.getSubtotal()).isEqualByComparingTo("600.00");
        assertThat(response.getTax()).isEqualByComparingTo("108.00");
        assertThat(response.getTotal()).isEqualByComparingTo("708.00");
        // old lines are cleared before the new ones are written
        verify(itemRepository).deleteAll(any());
    }

    // ── E9: a converted estimate is frozen ─────────────────────────────────

    @Test
    @DisplayName("E9 — a converted estimate cannot be modified → BusinessRuleException")
    void e9_convertedEstimate_cannotBeModified() {
        Estimate existing = new Estimate();
        existing.setId(42L);
        existing.setStatus("CONVERTED");

        EstimateRequestDTO req = request();
        req.setItems(List.of(item("Late change", 1, "100.00")));

        when(repository.findById(42L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(42L, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("converted to an invoice");

        verify(repository, never()).save(any(Estimate.class));
    }

    @Test
    @DisplayName("E9b — a converted estimate cannot be deleted → BusinessRuleException")
    void e9b_convertedEstimate_cannotBeDeleted() {
        Estimate existing = new Estimate();
        existing.setId(42L);
        existing.setStatus("CONVERTED");

        when(repository.findById(42L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete(42L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("converted to an invoice");

        verify(repository, never()).deleteById(42L);
    }

    // ── E10 / E11 / E12: retrieval, deletion and listing ───────────────────

    @Test
    @DisplayName("E10 — getById returns the estimate with its items")
    void e10_getById_returnsItems() {
        Estimate existing = new Estimate();
        existing.setId(42L);
        existing.setJobCard(jobCard(55L));
        existing.setTotal(new BigDecimal("1180.00"));

        EstimateItem item = new EstimateItem();
        item.setId(1L);
        item.setDescription("Brake pads");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("500.00"));
        item.setLineAmount(new BigDecimal("1000.00"));

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(itemRepository.findByEstimateIdOrderByIdAsc(42L)).thenReturn(List.of(item));

        EstimateResponseDTO response = service.getById(42L);

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getJobCardId()).isEqualTo(55L);
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getLineAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("E11 — delete removes the estimate's items with it")
    void e11_delete_removesItems() {
        Estimate existing = new Estimate();
        existing.setId(42L);

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(itemRepository.findByEstimateIdOrderByIdAsc(42L)).thenReturn(List.of(new EstimateItem()));

        service.delete(42L);

        verify(itemRepository).deleteAll(any());
        verify(repository).deleteById(42L);
    }

    @Test
    @DisplayName("E12 — listByJobCard rejects an unknown job card")
    void e12_listByJobCard_unknownJobCard_throws() {
        when(jobCardRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() ->
                service.listByJobCard(99L, org.springframework.data.domain.PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobCard not found: 99");
    }
}
