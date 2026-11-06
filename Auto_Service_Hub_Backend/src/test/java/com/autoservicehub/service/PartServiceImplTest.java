package com.autoservicehub.service;

import com.autoservicehub.dto.PartRequestDTO;
import com.autoservicehub.dto.PartResponseDTO;
import com.autoservicehub.entity.Part;
import com.autoservicehub.entity.PartCategory;
import com.autoservicehub.entity.Supplier;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.SupplierRepository;
import com.autoservicehub.service.impl.PartServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PartServiceImpl} — the spare-part fields added in V7:
 * category, supplier, storage location and HSN code.
 * Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * PA1  Create with all four fields -> all persisted on the entity
 * PA2  Create with a valid supplierId -> resolved and attached, response carries id + name
 * PA3  Create with an unknown supplierId -> ResourceNotFoundException, nothing saved
 * PA4  Create with no supplierId -> supplier left null
 * PA5  Blank optional text fields -> stored as null, not empty strings
 * PA6  Get by id -> category / supplier / location / HSN all returned
 * PA7  Get a legacy part with the new columns null -> returned without error
 * PA8  Update changes all four fields
 * PA9  Update clears the supplier when supplierId is omitted
 * PA10 Update with an unknown supplierId -> 404 rule, nothing saved
 * PA11 Update unknown part -> ResourceNotFoundException
 */
@ExtendWith(MockitoExtension.class)
class PartServiceImplTest {

    @Mock PartRepository repository;
    @Mock SupplierRepository supplierRepository;

    @InjectMocks
    PartServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private PartRequestDTO request() {
        PartRequestDTO dto = new PartRequestDTO();
        dto.setSku("ENO-7742");
        dto.setName("Engine Oil 5W-30 (1L)");
        dto.setUnit("Litre");
        dto.setSellingPrice(new BigDecimal("380.00"));
        dto.setPurchasePrice(new BigDecimal("320.00"));
        dto.setStockQty(48);
        dto.setMinStock(20);
        return dto;
    }

    private Supplier supplier(Long id, String name) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setName(name);
        return s;
    }

    /** Stubs the repository to echo back whatever it is asked to save. */
    private void givenSaved(Long id) {
        when(repository.save(any(Part.class))).thenAnswer(invocation -> {
            Part saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(id);
            }
            return saved;
        });
    }

    // ── PA1 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PA1 — category, storage location and HSN code are persisted on create")
    void pa1_create_persistsNewFields() {
        givenSaved(1L);
        PartRequestDTO req = request();
        req.setCategory(PartCategory.LUBRICANTS);
        req.setStorageLocation("  Rack A1, Shelf 2  ");
        req.setHsnCode("  27101990  ");

        service.create(req);

        ArgumentCaptor<Part> captor = ArgumentCaptor.forClass(Part.class);
        verify(repository).save(captor.capture());
        Part saved = captor.getValue();
        assertThat(saved.getCategory()).isEqualTo(PartCategory.LUBRICANTS);
        // Surrounding whitespace is trimmed on the way in.
        assertThat(saved.getStorageLocation()).isEqualTo("Rack A1, Shelf 2");
        assertThat(saved.getHsnCode()).isEqualTo("27101990");
    }

    // ── PA2 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PA2 — a valid supplierId is resolved, attached, and echoed back with its name")
    void pa2_create_withValidSupplier() {
        givenSaved(1L);
        when(supplierRepository.findById(3L)).thenReturn(Optional.of(supplier(3L, "Castrol India")));

        PartRequestDTO req = request();
        req.setCategory(PartCategory.FILTERS);
        req.setSupplierId(3L);
        req.setStorageLocation("Rack C2");
        req.setHsnCode("842123");

        PartResponseDTO response = service.create(req);

        ArgumentCaptor<Part> captor = ArgumentCaptor.forClass(Part.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSupplier().getId()).isEqualTo(3L);

        assertThat(response.getCategory()).isEqualTo(PartCategory.FILTERS);
        assertThat(response.getSupplierId()).isEqualTo(3L);
        assertThat(response.getSupplierName()).isEqualTo("Castrol India");
        assertThat(response.getStorageLocation()).isEqualTo("Rack C2");
        assertThat(response.getHsnCode()).isEqualTo("842123");
    }

    // ── PA3 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PA3 — an unknown supplierId is a 404, and nothing is saved")
    void pa3_createWithUnknownSupplier_throws() {
        when(supplierRepository.findById(99L)).thenReturn(Optional.empty());
        PartRequestDTO req = request();
        req.setSupplierId(99L);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Supplier not found: 99");

        verify(repository, never()).save(any(Part.class));
    }

    // ── PA4 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PA4 — omitting supplierId leaves the part unassigned and never queries suppliers")
    void pa4_createWithoutSupplier_leavesNull() {
        givenSaved(1L);

        PartResponseDTO response = service.create(request());

        assertThat(response.getSupplierId()).isNull();
        assertThat(response.getSupplierName()).isNull();
        verifyNoInteractions(supplierRepository);
    }

    // ── PA5 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PA5 — blank optional text fields are stored as null rather than empty strings")
    void pa5_blankOptionalFieldsStoredAsNull() {
        givenSaved(1L);
        PartRequestDTO req = request();
        req.setStorageLocation("   ");
        req.setHsnCode("");

        service.create(req);

        ArgumentCaptor<Part> captor = ArgumentCaptor.forClass(Part.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStorageLocation()).isNull();
        assertThat(captor.getValue().getHsnCode()).isNull();
    }

    // ── PA6 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PA6 — reading a part returns all four fields")
    void pa6_getById_returnsNewFields() {
        Part part = new Part();
        part.setId(5L);
        part.setSku("BRP-2211");
        part.setName("Brake Pads — Front (Pair)");
        part.setStockQty(12);
        part.setMinStock(8);
        part.setCategory(PartCategory.BRAKES);
        part.setStorageLocation("Rack B3");
        part.setHsnCode("87083010");
        part.setSupplier(supplier(3L, "Bosch India"));
        when(repository.findById(5L)).thenReturn(Optional.of(part));

        PartResponseDTO response = service.getById(5L);

        assertThat(response.getCategory()).isEqualTo(PartCategory.BRAKES);
        assertThat(response.getSupplierId()).isEqualTo(3L);
        assertThat(response.getSupplierName()).isEqualTo("Bosch India");
        assertThat(response.getStorageLocation()).isEqualTo("Rack B3");
        assertThat(response.getHsnCode()).isEqualTo("87083010");
    }

    // ── PA7 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PA7 — a part predating the new columns reads back uncategorised rather than failing")
    void pa7_getLegacyPart_nullNewFields() {
        Part part = new Part();
        part.setId(2L);
        part.setSku("OIF-3301");
        part.setName("Oil Filter");
        part.setStockQty(2);
        part.setMinStock(10);
        // category / storageLocation / hsnCode / supplier all left null,
        // exactly as a row written before V7 would be.
        when(repository.findById(2L)).thenReturn(Optional.of(part));

        PartResponseDTO response = service.getById(2L);

        assertThat(response.getCategory()).isNull();
        assertThat(response.getSupplierId()).isNull();
        assertThat(response.getSupplierName()).isNull();
        assertThat(response.getStorageLocation()).isNull();
        assertThat(response.getHsnCode()).isNull();
        // The pre-existing low-stock rule is unaffected.
        assertThat(response.isLowStock()).isTrue();
    }

    // ── PA8 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PA8 — updating changes category, supplier, storage location and HSN code")
    void pa8_update_appliesNewFields() {
        Part existing = new Part();
        existing.setId(5L);
        existing.setSku("ACF-5503");
        existing.setName("AC Filter");
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(supplierRepository.findById(7L)).thenReturn(Optional.of(supplier(7L, "Mahle Filters")));
        givenSaved(5L);

        PartRequestDTO req = request();
        req.setSku("ACF-5503");
        req.setCategory(PartCategory.FILTERS);
        req.setSupplierId(7L);
        req.setStorageLocation("Rack C2");
        req.setHsnCode("842123");

        PartResponseDTO response = service.update(5L, req);

        assertThat(response.getId()).isEqualTo(5L);
        assertThat(response.getCategory()).isEqualTo(PartCategory.FILTERS);
        assertThat(response.getSupplierId()).isEqualTo(7L);
        assertThat(response.getSupplierName()).isEqualTo("Mahle Filters");
        assertThat(response.getStorageLocation()).isEqualTo("Rack C2");
        assertThat(response.getHsnCode()).isEqualTo("842123");
    }

    // ── PA9 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PA9 — an update that omits supplierId detaches the part")
    void pa9_updateWithoutSupplier_clearsIt() {
        Part existing = new Part();
        existing.setId(5L);
        existing.setSupplier(supplier(3L, "Castrol India"));
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        givenSaved(5L);

        PartRequestDTO req = request();
        req.setSku("ENO-7742");
        // supplierId deliberately absent

        PartResponseDTO response = service.update(5L, req);

        assertThat(response.getSupplierId()).isNull();
        assertThat(response.getSupplierName()).isNull();
    }

    // ── PA10 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PA10 — updating onto an unknown supplierId is rejected and nothing is saved")
    void pa10_updateWithUnknownSupplier_throws() {
        Part existing = new Part();
        existing.setId(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(supplierRepository.findById(404L)).thenReturn(Optional.empty());

        PartRequestDTO req = request();
        req.setSupplierId(404L);

        assertThatThrownBy(() -> service.update(5L, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Supplier not found: 404");

        verify(repository, never()).save(any(Part.class));
    }

    // ── PA11 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PA11 — updating an unknown part → ResourceNotFoundException")
    void pa11_updateUnknownPart_throws() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, request()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Part not found: 99");

        verify(repository, never()).save(any(Part.class));
    }
}