package com.autoservicehub.service;

import com.autoservicehub.dto.PackageItemRequestDTO;
import com.autoservicehub.dto.ServicePackageRequestDTO;
import com.autoservicehub.dto.ServicePackageResponseDTO;
import com.autoservicehub.entity.PackageItem;
import com.autoservicehub.entity.PackageItemType;
import com.autoservicehub.entity.ServicePackage;
import com.autoservicehub.repository.PackageItemRepository;
import com.autoservicehub.repository.ServicePackageRepository;
import com.autoservicehub.service.impl.ServicePackageServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for service package persistence against a real database.
 *
 * <p>These cover what a Mockito unit test cannot: that the V8 columns really
 * persist and round-trip, that the package_items cascade actually writes rows,
 * that replacing the item list deletes the lines that dropped out of it, and
 * that deleting a package takes its items with it.
 *
 * <p>Uses {@code @DataJpaTest} against an embedded database with a schema built
 * from the JPA model — the same shape as {@code StockMovementPersistenceTest},
 * and for the same reason: a full {@code @SpringBootTest} is avoided because
 * application-test.yml points every context at one shared named H2 database with
 * {@code create-drop}, so one context shutting down drops the schema out from
 * under the next.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:service-package-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // application.yml pins MySQLDialect for the real MySQL deployment, but
        // the MySQL DDL it generates is not executable on H2, so the schema
        // never gets created. Select the dialect from the actual JDBC metadata.
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ServicePackageServiceImpl.class)
class ServicePackagePersistenceTest {

    @Autowired ServicePackageServiceImpl service;
    @Autowired ServicePackageRepository packageRepository;
    @Autowired PackageItemRepository      itemRepository;

    private ServicePackageRequestDTO request(String name, String type) {
        ServicePackageRequestDTO dto = new ServicePackageRequestDTO();
        dto.setName(name);
        dto.setType(type);
        dto.setPrice(new BigDecimal("2499.00"));
        return dto;
    }

    private static PackageItemRequestDTO item(String name, PackageItemType type) {
        return new PackageItemRequestDTO(name, type);
    }

    @Test
    @DisplayName("SP1 — every V8 field survives a create/read round trip through the database")
    void sp1_allFieldsRoundTrip() {
        ServicePackageRequestDTO req = request("Premium Service", "PREMIUM");
        req.setDuration("6–8 hrs");
        req.setGstRate(new BigDecimal("18.00"));
        req.setDiscountPercentage(new BigDecimal("5.00"));
        req.setColor("purple");
        req.setPopular(true);

        Long id = service.create(req).getId();

        ServicePackageResponseDTO reloaded = service.getById(id);
        assertThat(reloaded.getName()).isEqualTo("Premium Service");
        assertThat(reloaded.getType()).isEqualTo("PREMIUM");
        assertThat(reloaded.getDuration()).isEqualTo("6–8 hrs");
        assertThat(reloaded.getGstRate()).isEqualByComparingTo("18.00");
        assertThat(reloaded.getDiscountPercentage()).isEqualByComparingTo("5.00");
        assertThat(reloaded.getColor()).isEqualTo("purple");
        assertThat(reloaded.getPopular()).isTrue();
        assertThat(reloaded.getActive()).isTrue();

        // Confirm the values really landed in the table, not just in the DTO.
        ServicePackage entity = packageRepository.findById(id).orElseThrow();
        assertThat(entity.getColor()).isEqualTo("purple");
        assertThat(entity.getDuration()).isEqualTo("6–8 hrs");
        assertThat(entity.getPopular()).isTrue();
    }

    @Test
    @DisplayName("SP2 — included services and parts are written as real package_items rows")
    void sp2_itemsArePersisted() {
        ServicePackageRequestDTO req = request("Full Service", "FULL");
        req.setItems(List.of(
                item("Engine oil change", PackageItemType.SERVICE),
                item("Oil filter replacement", PackageItemType.SERVICE),
                item("Engine Oil 5L", PackageItemType.PART)));

        Long id = service.create(req).getId();

        // Two rows written and linked to this package — this is the assertion
        // that proves the cascade and the new FK column actually work.
        List<PackageItem> rows = itemRepository.findAll();
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getServicePackage()).isNotNull();
            assertThat(row.getServicePackage().getId()).isEqualTo(id);
        });
        assertThat(rows).extracting(PackageItem::getItemType)
                .containsExactlyInAnyOrder("SERVICE", "SERVICE", "PART");

        ServicePackageResponseDTO reloaded = service.getById(id);
        assertThat(reloaded.getItems()).hasSize(3);
        assertThat(reloaded.getItems()).extracting(i -> i.getItemType())
                .containsExactly(PackageItemType.SERVICE, PackageItemType.SERVICE, PackageItemType.PART);
    }

    @Test
    @DisplayName("SP3 — updating the item list deletes the lines that dropped out of it")
    void sp3_updateReplacesItems() {
        ServicePackageRequestDTO create = request("Full Service", "FULL");
        create.setItems(List.of(
                item("Engine oil change", PackageItemType.SERVICE),
                item("Oil filter replacement", PackageItemType.SERVICE),
                item("Engine Oil 5L", PackageItemType.PART)));
        Long id = service.create(create).getId();
        assertThat(itemRepository.findAll()).hasSize(3);

        ServicePackageRequestDTO update = request("Full Service", "FULL");
        update.setItems(List.of(item("Only one thing now", PackageItemType.SERVICE)));
        service.update(id, update);

        // Orphan removal must have taken the two dropped rows with it, or the
        // table would slowly accumulate items no package references.
        List<PackageItem> remaining = itemRepository.findAll();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getItemName()).isEqualTo("Only one thing now");
    }

    @Test
    @DisplayName("SP4 — deleting a package deletes its items too")
    void sp4_deleteCascadesToItems() {
        ServicePackageRequestDTO create = request("AMC", "AMC");
        create.setItems(List.of(item("2x Full Service visits", PackageItemType.SERVICE)));
        Long id = service.create(create).getId();
        assertThat(itemRepository.findAll()).hasSize(1);

        service.delete(id);

        assertThat(packageRepository.findById(id)).isEmpty();
        assertThat(itemRepository.findAll())
                .as("orphan package_items must not survive their package")
                .isEmpty();
    }

    @Test
    @DisplayName("SP5 — an update that omits `active` leaves a deactivated package deactivated")
    void sp5_updatePreservesActive() {
        ServicePackageRequestDTO create = request("Retired Package", "OLD");
        create.setActive(false);
        Long id = service.create(create).getId();
        assertThat(service.getById(id).getActive()).isFalse();

        // A save that forgets `active` must not resurrect the package.
        service.update(id, request("Retired Package", "OLD"));

        assertThat(service.getById(id).getActive()).isFalse();
    }

    @Test
    @DisplayName("SP6 — a package created before the V8 columns still reads back cleanly")
    void sp6_legacyRowReadsBack() {
        // A row exactly as it would exist before V8: only name/type/price/active.
        ServicePackage legacy = new ServicePackage();
        legacy.setName("Legacy BASIC");
        legacy.setType("BASIC");
        legacy.setPrice(new BigDecimal("999.00"));
        legacy.setActive(true);
        Long id = packageRepository.save(legacy).getId();

        ServicePackageResponseDTO dto = service.getById(id);

        assertThat(dto.getName()).isEqualTo("Legacy BASIC");
        assertThat(dto.getDuration()).isNull();
        assertThat(dto.getGstRate()).isNull();
        assertThat(dto.getDiscountPercentage()).isNull();
        assertThat(dto.getColor()).isNull();
        assertThat(dto.getPopular()).isFalse();
        assertThat(dto.getItems()).isEmpty();
    }
}