package com.autoservicehub.service;

import com.autoservicehub.dto.SupplierRequestDTO;
import com.autoservicehub.dto.SupplierResponseDTO;
import com.autoservicehub.entity.Supplier;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.PurchaseRepository;
import com.autoservicehub.repository.SupplierRepository;
import com.autoservicehub.service.impl.SupplierServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SupplierServiceImpl} — supplier CRUD and the rules that
 * protect the supplier list (FR-INV-2).
 * Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * SU1  Create               → supplier persisted, name trimmed
 * SU2  Create blank name    → BusinessRuleException, nothing saved
 * SU3  Create duplicate     → BusinessRuleException (case/whitespace-insensitive)
 * SU4  Get by id            → supplier returned
 * SU5  Get unknown id       → ResourceNotFoundException
 * SU6  List                 → page of suppliers
 * SU7  Update               → fields changed
 * SU8  Update unknown id    → ResourceNotFoundException
 * SU9  Update onto another supplier's name → BusinessRuleException
 * SU10 Update excluding itself → allowed (no false duplicate)
 * SU11 Delete, no purchases → removed
 * SU12 Delete unknown id    → ResourceNotFoundException
 * SU13 Delete with purchases → BusinessRuleException, not removed
 * SU14 Blank optional fields are stored as null, not empty strings
 */
@ExtendWith(MockitoExtension.class)
class SupplierServiceImplTest {

    @Mock SupplierRepository repository;
    @Mock PurchaseRepository purchaseRepository;
    @Mock PurchaseService   purchaseService;
    @Mock AuditService     auditService;

    @InjectMocks
    SupplierServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private SupplierRequestDTO request(String name) {
        SupplierRequestDTO dto = new SupplierRequestDTO();
        dto.setName(name);
        dto.setPhone("+91 98000 00000");
        dto.setEmail("sales@example.com");
        dto.setAddress("Industrial Estate");
        return dto;
    }

    private Supplier supplier(Long id, String name) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setName(name);
        s.setPhone("+91 98000 00000");
        s.setEmail("sales@example.com");
        return s;
    }

    /** Stubs the repository to echo back whatever it is asked to save. */
    private void givenSaved(Long id) {
        when(repository.save(any(Supplier.class))).thenAnswer(invocation -> {
            Supplier saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(id);
            }
            return saved;
        });
    }

    // ── SU1..SU3: create ─────────────────────────────────────────────────

    @Test
    @DisplayName("SU1 — creating a supplier persists it with the name trimmed")
    void su1_create_persistsTrimmedName() {
        givenSaved(1L);
        when(repository.countByNameIgnoreCaseAndIdNot(anyString(), anyLong())).thenReturn(0L);

        SupplierResponseDTO response = service.create(request("  Bharat Auto  "));

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Bharat Auto");
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("Bharat Auto");
    }

    @Test
    @DisplayName("SU2 — a blank name is rejected and nothing is saved")
    void su2_createBlankName_throws() {
        assertThatThrownBy(() -> service.create(request("   ")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("name is required");

        verify(repository, never()).save(any(Supplier.class));
    }

    @Test
    @DisplayName("SU3 — a name already held by another supplier is rejected")
    void su3_duplicateName_throws() {
        when(repository.countByNameIgnoreCaseAndIdNot(eq("Bharat Auto"), eq(-1L))).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request("Bharat Auto")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already exists");

        verify(repository, never()).save(any(Supplier.class));
    }

        // ── SU4..SU6: read ───────────────────────────────────────────────────

    @Test
    @DisplayName("SU4 — a supplier is returned by id")
    void su4_getById_returnsSupplier() {
        when(repository.findById(7L)).thenReturn(Optional.of(supplier(7L, "Bharat Auto")));

        assertThat(service.getById(7L).getName()).isEqualTo("Bharat Auto");
    }

    @Test
    @DisplayName("SU5 — an unknown supplier id → ResourceNotFoundException")
    void su5_getUnknown_throws() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Supplier not found: 99");
    }

    @Test
    @DisplayName("SU6 — listing returns a page of suppliers")
    void su6_list_returnsPage() {
        when(repository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(supplier(1L, "A"), supplier(2L, "B"))));

        Page<SupplierResponseDTO> page = service.list(PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(SupplierResponseDTO::getName)
                                        .containsExactly("A", "B");
    }

    // ── SU7..SU10: update ────────────────────────────────────────────────

    @Test
    @DisplayName("SU7 — updating changes the stored fields")
    void su7_update_appliesChanges() {
        when(repository.findById(7L)).thenReturn(Optional.of(supplier(7L, "Old Name")));
        when(repository.countByNameIgnoreCaseAndIdNot(eq("New Name"), eq(7L))).thenReturn(0L);
        givenSaved(7L);

        service.update(7L, request("New Name"));

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(7L);
        assertThat(captor.getValue().getName()).isEqualTo("New Name");
    }

    @Test
    @DisplayName("SU8 — updating an unknown supplier → ResourceNotFoundException")
    void su8_updateUnknown_throws() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, request("Bharat Auto")))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any(Supplier.class));
    }

    @Test
    @DisplayName("SU9 — renaming a supplier onto another supplier's name is rejected")
    void su9_updateOntoDuplicateName_throws() {
        when(repository.findById(7L)).thenReturn(Optional.of(supplier(7L, "Old Name")));
        when(repository.countByNameIgnoreCaseAndIdNot(eq("Bharat Auto"), eq(7L))).thenReturn(1L);

        assertThatThrownBy(() -> service.update(7L, request("Bharat Auto")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already exists");

        verify(repository, never()).save(any(Supplier.class));
    }

    @Test
    @DisplayName("SU10 — a supplier is not a duplicate of itself, so a no-op save is allowed")
    void su10_updateExcludingItself_isAllowed() {
        when(repository.findById(7L)).thenReturn(Optional.of(supplier(7L, "Bharat Auto")));
        // Only the supplier's own row matches the name.
        when(repository.countByNameIgnoreCaseAndIdNot(eq("Bharat Auto"), eq(7L))).thenReturn(0L);
        givenSaved(7L);

        assertThat(service.update(7L, request("Bharat Auto")).getName()).isEqualTo("Bharat Auto");

        verify(repository).save(any(Supplier.class));
    }

    // ── SU11..SU13: delete ───────────────────────────────────────────────

    @Test
    @DisplayName("SU11 — a supplier with no purchase history can be deleted")
    void su11_deleteWithoutPurchases_removes() {
        when(repository.findById(7L)).thenReturn(Optional.of(supplier(7L, "Bharat Auto")));
        when(purchaseRepository.existsBySupplierId(7L)).thenReturn(false);

        service.delete(7L);

        verify(repository).delete(any(Supplier.class));
    }

    @Test
    @DisplayName("SU12 — deleting an unknown supplier → ResourceNotFoundException")
    void su12_deleteUnknown_throws() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).delete(any(Supplier.class));
    }

    @Test
    @DisplayName("SU13 — a supplier with purchase history cannot be deleted")
    void su13_deleteWithPurchases_throws() {
        when(repository.findById(7L)).thenReturn(Optional.of(supplier(7L, "Bharat Auto")));
        when(purchaseRepository.existsBySupplierId(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("purchase history");

        verify(repository, never()).delete(any(Supplier.class));
    }

    // ── SU14 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SU14 — blank optional fields are stored as null rather than empty strings")
    void su14_blankOptionalFieldsStoredAsNull() {
        SupplierRequestDTO req = request("Bharat Auto");
        req.setPhone("   ");
        req.setEmail("");
        req.setAddress(null);
        givenSaved(1L);
        when(repository.countByNameIgnoreCaseAndIdNot(anyString(), anyLong())).thenReturn(0L);

        service.create(req);

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPhone()).isNull();
        assertThat(captor.getValue().getEmail()).isNull();
        assertThat(captor.getValue().getAddress()).isNull();
    }

    // ── SU22..SU25: search and purchase history ─────────────────────────

    @Test
    @DisplayName("SU22 — search delegates to the repository with the trimmed term")
    void su22_search_trimsAndDelegates() {
        when(repository.search(eq("bharat"), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(supplier(1L, "Bharat Auto"))));

        var page = service.search("  bharat  ", PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(SupplierResponseDTO::getName)
                .containsExactly("Bharat Auto");
        verify(repository).search(eq("bharat"), any());
    }

    @Test
    @DisplayName("SU23 — a blank search term falls back to the full list")
    void su23_blankSearch_fallsBackToList() {
        when(repository.findAll(any(PageRequest.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(supplier(1L, "Bharat Auto"), supplier(2L, "Metro Spares"))));

        assertThat(service.search("   ", PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
        assertThat(service.search(null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);

        // A blank term must never reach the search query.
        verify(repository, never()).search(anyString(), any());
    }

    @Test
    @DisplayName("SU24 — the supplier response reports how many purchases it has")
    void su24_purchaseCount_reported() {
        when(repository.save(any(Supplier.class))).thenAnswer(invocation -> {
            Supplier s = invocation.getArgument(0);
            s.setId(1L);
            return s;
        });
        when(repository.countByNameIgnoreCaseAndIdNot(anyString(), anyLong())).thenReturn(0L);
        when(purchaseRepository.countPurchasesBySupplierId(1L)).thenReturn(4L);

        assertThat(service.create(request("Bharat Auto")).getPurchaseCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("SU25 — purchase history delegates to PurchaseService rather than re-querying")
    void su25_purchaseHistory_delegates() {
        when(purchaseService.listBySupplier(eq(1L), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        var page = service.purchaseHistory(1L, PageRequest.of(0, 20));

        assertThat(page).isEmpty();
        verify(purchaseService).listBySupplier(eq(1L), any());
    }
}
