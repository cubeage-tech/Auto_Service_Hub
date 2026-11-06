package com.autoservicehub.service;

import com.autoservicehub.dto.PackageItemRequestDTO;
import com.autoservicehub.dto.ServicePackageRequestDTO;
import com.autoservicehub.dto.ServicePackageResponseDTO;
import com.autoservicehub.entity.PackageItem;
import com.autoservicehub.entity.PackageItemType;
import com.autoservicehub.entity.ServicePackage;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.ServicePackageRepository;
import com.autoservicehub.service.impl.ServicePackageServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ServicePackageServiceImpl} — package CRUD plus the
 * fields added in V8: duration, GST rate, discount, colour, popular badge and
 * the bundled items. Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * PK1  Create            -> every field persisted on the entity
 * PK2  Create            -> items saved and linked to the package
 * PK3  Create            -> active defaults to true, popular to false
 * PK4  Create            -> active:false is honoured
 * PK5  Create, no items  -> empty item list
 * PK6  Create            -> colour is lower-cased
 * PK7  Create            -> unknown colour rejected, nothing saved
 * PK8  Create            -> blank duration stored as null
 * PK9  Get by id         -> all fields and items returned
 * PK10 Update            -> fields changed
 * PK11 Update            -> items replaced wholesale (dropped lines removed)
 * PK12 Update            -> omitting `active` preserves the stored value
 * PK13 Update            -> sending active:false deactivates
 * PK14 Update            -> omitting `items` leaves existing items untouched
 * PK15 Update unknown id -> ResourceNotFoundException
 * PK16 Delete            -> removed
 * PK17 Delete unknown id -> ResourceNotFoundException
 */
@ExtendWith(MockitoExtension.class)
class ServicePackageServiceImplTest {

    @Mock ServicePackageRepository repository;

    @InjectMocks
    ServicePackageServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private ServicePackageRequestDTO request() {
        ServicePackageRequestDTO dto = new ServicePackageRequestDTO();
        dto.setName("Full Service");
        dto.setType("BASIC");
        dto.setPrice(new BigDecimal("2499.00"));
        return dto;
    }

    private PackageItemRequestDTO item(String name, PackageItemType type) {
        return new PackageItemRequestDTO(name, type);
    }

    private ServicePackage existing(Long id) {
        ServicePackage p = new ServicePackage();
        p.setId(id);
        p.setName("Old Name");
        p.setType("OLD");
        p.setPrice(new BigDecimal("100.00"));
        p.setActive(true);
        p.setItems(new ArrayList<>());
        return p;
    }

    /** Stubs the repository to echo back whatever it is asked to save. */
    private void givenSaved(Long id) {
        when(repository.save(any(ServicePackage.class))).thenAnswer(invocation -> {
            ServicePackage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(id);
            }
            return saved;
        });
    }

    // ── PK1 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK1 — creating persists duration, GST rate, discount, colour and the popular badge")
    void pk1_create_persistsAllFields() {
        givenSaved(1L);
        ServicePackageRequestDTO req = request();
        req.setDuration("  4–5 hrs  ");
        req.setGstRate(new BigDecimal("18.00"));
        req.setDiscountPercentage(new BigDecimal("10.00"));
        req.setColor("lime");
        req.setPopular(true);

        service.create(req);

        ArgumentCaptor<ServicePackage> captor = ArgumentCaptor.forClass(ServicePackage.class);
        verify(repository).save(captor.capture());
        ServicePackage saved = captor.getValue();
        assertThat(saved.getDuration()).isEqualTo("4–5 hrs");
        assertThat(saved.getGstRate()).isEqualByComparingTo("18.00");
        assertThat(saved.getDiscountPercentage()).isEqualByComparingTo("10.00");
        assertThat(saved.getColor()).isEqualTo("lime");
        assertThat(saved.getPopular()).isTrue();
    }

    // ── PK2 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK2 — included services and parts are saved and linked to the package")
    void pk2_create_savesItems() {
        givenSaved(1L);
        ServicePackageRequestDTO req = request();
        req.setItems(List.of(
                item("Engine oil change", PackageItemType.SERVICE),
                item("Engine Oil 5L", PackageItemType.PART)));

        ServicePackageResponseDTO response = service.create(req);

        ArgumentCaptor<ServicePackage> captor = ArgumentCaptor.forClass(ServicePackage.class);
        verify(repository).save(captor.capture());
        ServicePackage saved = captor.getValue();
        assertThat(saved.getItems()).hasSize(2);
        // Every item must point back at its package or the link is unqueryable.
        assertThat(saved.getItems()).allSatisfy(i -> assertThat(i.getServicePackage()).isSameAs(saved));
        assertThat(saved.getItems()).extracting(PackageItem::getItemName)
                .containsExactly("Engine oil change", "Engine Oil 5L");
        assertThat(saved.getItems()).extracting(PackageItem::getItemType)
                .containsExactly("SERVICE", "PART");

        assertThat(response.getItems()).extracting(i -> i.getItemType())
                .containsExactly(PackageItemType.SERVICE, PackageItemType.PART);
    }

    // ── PK3 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK3 — a new package defaults to active and not popular")
    void pk3_create_defaultsActiveAndPopular() {
        givenSaved(1L);

        ServicePackageResponseDTO response = service.create(request());

        assertThat(response.getActive()).isTrue();
        assertThat(response.getPopular()).isFalse();
    }

    // ── PK4 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK4 — a package can be created already inactive")
    void pk4_createWithActiveFalse_honoured() {
        givenSaved(1L);
        ServicePackageRequestDTO req = request();
        req.setActive(false);

        assertThat(service.create(req).getActive()).isFalse();
    }

    // ── PK5 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK5 — a package created without items reports an empty list, never null")
    void pk5_createWithoutItems_emptyList() {
        givenSaved(1L);

        assertThat(service.create(request()).getItems()).isEmpty();
    }

    // ── PK6 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK6 — the colour token is lower-cased so it matches the card stylesheet")
    void pk6_create_lowercasesColour() {
        givenSaved(1L);
        ServicePackageRequestDTO req = request();
        req.setColor("  LIME  ");

        assertThat(service.create(req).getColor()).isEqualTo("lime");
    }

    // ── PK7 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK7 — an unsupported colour is rejected and nothing is saved")
    void pk7_createWithUnknownColour_throws() {
        ServicePackageRequestDTO req = request();
        req.setColor("chartreuse");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unsupported package colour")
                .hasMessageContaining("lime");

        verify(repository, never()).save(any(ServicePackage.class));
    }

    // ── PK8 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK8 — a blank duration is stored as null rather than an empty string")
    void pk8_blankDurationStoredAsNull() {
        givenSaved(1L);
        ServicePackageRequestDTO req = request();
        req.setDuration("   ");

        assertThat(service.create(req).getDuration()).isNull();
    }

    // ── PK9 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK9 — reading a package returns every field and its items")
    void pk9_getById_returnsAllFields() {
        ServicePackage pkg = existing(5L);
        pkg.setName("Premium Service");
        pkg.setType("PREMIUM");
        pkg.setPrice(new BigDecimal("10999.00"));
        pkg.setDuration("6–8 hrs");
        pkg.setGstRate(new BigDecimal("18.00"));
        pkg.setDiscountPercentage(new BigDecimal("5.00"));
        pkg.setColor("purple");
        pkg.setPopular(true);
        pkg.getItems().add(item("Spark plug replacement", PackageItemType.SERVICE, pkg));
        pkg.getItems().add(item("Coolant 1L", PackageItemType.PART, pkg));
        when(repository.findById(5L)).thenReturn(Optional.of(pkg));

        ServicePackageResponseDTO response = service.getById(5L);

        assertThat(response.getName()).isEqualTo("Premium Service");
        assertThat(response.getType()).isEqualTo("PREMIUM");
        assertThat(response.getDuration()).isEqualTo("6–8 hrs");
        assertThat(response.getGstRate()).isEqualByComparingTo("18.00");
        assertThat(response.getDiscountPercentage()).isEqualByComparingTo("5.00");
        assertThat(response.getColor()).isEqualTo("purple");
        assertThat(response.getPopular()).isTrue();
        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems()).extracting(i -> i.getItemName())
                .containsExactly("Spark plug replacement", "Coolant 1L");
    }

    @Test
    @DisplayName("PK9b — a package whose stored item type is unrecognised still reads back")
    void pk9b_unknownStoredItemType_readsAsNull() {
        ServicePackage pkg = existing(5L);
        PackageItem orphan = new PackageItem();
        orphan.setItemName("Mystery item");
        orphan.setItemType("GADGET"); // not a valid PackageItemType
        orphan.setServicePackage(pkg);
        pkg.getItems().add(orphan);
        when(repository.findById(5L)).thenReturn(Optional.of(pkg));

        // A bad value in the database must not turn the read into a 500.
        assertThat(service.getById(5L).getItems().get(0).getItemType()).isNull();
    }

    // ── PK10 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK10 — updating changes the stored fields")
    void pk10_update_appliesChanges() {
        when(repository.findById(5L)).thenReturn(Optional.of(existing(5L)));
        givenSaved(5L);
        ServicePackageRequestDTO req = request();
        req.setName("Full Service Plus");
        req.setDuration("5 hrs");
        req.setDiscountPercentage(new BigDecimal("12.00"));
        req.setColor("blue");

        ServicePackageResponseDTO response = service.update(5L, req);

        assertThat(response.getId()).isEqualTo(5L);
        assertThat(response.getName()).isEqualTo("Full Service Plus");
        assertThat(response.getDuration()).isEqualTo("5 hrs");
        assertThat(response.getDiscountPercentage()).isEqualByComparingTo("12.00");
        assertThat(response.getColor()).isEqualTo("blue");
    }

    // ── PK11 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK11 — updating replaces the item list wholesale")
    void pk11_update_replacesItems() {
        ServicePackage pkg = existing(5L);
        pkg.getItems().add(item("Old service", PackageItemType.SERVICE, pkg));
        when(repository.findById(5L)).thenReturn(Optional.of(pkg));
        givenSaved(5L);

        ServicePackageRequestDTO req = request();
        req.setItems(List.of(item("New service", PackageItemType.SERVICE)));

        ServicePackageResponseDTO response = service.update(5L, req);

        // The dropped line must be gone, not merged alongside the new one.
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getItemName()).isEqualTo("New service");
    }

    // ── PK12 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK12 — an update that omits `active` does not silently reactivate the package")
    void pk12_updateWithoutActive_preservesIt() {
        ServicePackage pkg = existing(5L);
        pkg.setActive(false);
        when(repository.findById(5L)).thenReturn(Optional.of(pkg));
        givenSaved(5L);

        // `active` deliberately absent from the request.
        assertThat(service.update(5L, request()).getActive()).isFalse();
    }

    // ── PK13 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK13 — sending active:false deactivates the package")
    void pk13_updateWithActiveFalse_deactivates() {
        when(repository.findById(5L)).thenReturn(Optional.of(existing(5L)));
        givenSaved(5L);
        ServicePackageRequestDTO req = request();
        req.setActive(false);

        assertThat(service.update(5L, req).getActive()).isFalse();
    }

    // ── PK14 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK14 — an update that omits `items` leaves the existing contents alone")
    void pk14_updateWithoutItems_keepsThem() {
        ServicePackage pkg = existing(5L);
        pkg.getItems().add(item("Keep me", PackageItemType.SERVICE, pkg));
        when(repository.findById(5L)).thenReturn(Optional.of(pkg));
        givenSaved(5L);

        // `items` deliberately absent — an older client that only knows
        // name/type/price must not wipe a package's contents.
        ServicePackageResponseDTO response = service.update(5L, request());

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getItemName()).isEqualTo("Keep me");
    }

    // ── PK15 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK15 — updating an unknown package → ResourceNotFoundException")
    void pk15_updateUnknown_throws() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, request()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ServicePackage not found: 99");

        verify(repository, never()).save(any(ServicePackage.class));
    }

    // ── PK16 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK16 — deleting a package removes it")
    void pk16_delete_removes() {
        when(repository.existsById(5L)).thenReturn(true);

        service.delete(5L);

        verify(repository).deleteById(5L);
    }

    // ── PK17 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PK17 — deleting an unknown package → ResourceNotFoundException")
    void pk17_deleteUnknown_throws() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).deleteById(any(Long.class));
    }

    /** Attaches an item to a package, as the service does when persisting. */
    private PackageItem item(String name, PackageItemType type, ServicePackage owner) {
        PackageItem i = new PackageItem();
        i.setItemName(name);
        i.setItemType(type.name());
        i.setServicePackage(owner);
        return i;
    }
}