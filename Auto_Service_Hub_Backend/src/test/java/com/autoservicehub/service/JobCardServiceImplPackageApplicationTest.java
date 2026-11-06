package com.autoservicehub.service;

import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.dto.JobCardResponseDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.PackageApplication;
import com.autoservicehub.entity.PackageItem;
import com.autoservicehub.entity.ServicePackage;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.InspectionItemRepository;
import com.autoservicehub.repository.InspectionRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.PackageApplicationRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.JobCardServiceImpl;
import com.autoservicehub.util.BillingCalculator;
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
 * Unit tests for linking an applied service package to a job card —
 * {@link JobCardServiceImpl}'s handling of
 * {@code JobCardRequestDTO.packageApplicationId} and the package details it
 * surfaces on the job-card response.
 *
 * <p>Pure Mockito — no Spring context, no database.
 *
 * <p>The rule under test throughout is that a package application may only be
 * attached when it names the <em>same</em> customer and the <em>same</em> vehicle
 * as the job card. Anything else would let a package sold to one customer be
 * read as covering work on somebody else's car.
 *
 * Test cases
 * ----------
 * JP1  Create with a matching application   → application attached
 * JP2  Create, application on other vehicle → BusinessRuleException, nothing saved against it
 * JP3  Create, application for other customer → BusinessRuleException
 * JP4  Create with unknown application id  → ResourceNotFoundException
 * JP5  Create with no application id       → no link, response fields null (existing behaviour)
 * JP6  Response                            → snapshot terms and SERVICE items surfaced
 * JP7  Response                            → PART items are excluded from the service list
 * JP8  Item with an unreadable type        → skipped, not fatal
 * JP9  Job card with a linked package whose catalogue entry is gone → empty service list
 * JP10 Update with a matching application  → link set
 */
@ExtendWith(MockitoExtension.class)
class JobCardServiceImplPackageApplicationTest {

    @Mock JobCardRepository          repository;
    @Mock CustomerRepository         customerRepository;
    @Mock VehicleRepository          vehicleRepository;
    @Mock MechanicRepository         mechanicRepository;
    @Mock AppointmentRepository      appointmentRepository;
    @Mock InspectionRepository       inspectionRepository;
    @Mock InspectionItemRepository   inspectionItemRepository;
    @Mock PackageApplicationRepository packageApplicationRepository;
    @Mock JobTaskRepository          jobTaskRepository;
    @Mock AuditService               auditService;

    private final BillingCalculator calculator = new BillingCalculator(new BigDecimal("18"));

    @InjectMocks
    JobCardServiceImpl service;

    // ── Helpers ───────────────────────────────────────────────────────────

    private Customer customer(Long id) {
        Customer c = new Customer();
        c.setId(id);
        c.setName("Priya Sharma");
        return c;
    }

    private Vehicle vehicle(Long id) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setRegistrationNo("MH-12-AB-1234");
        v.setModel("Swift VXI");
        return v;
    }

    private PackageItem item(Long id, String name, String type) {
        PackageItem i = new PackageItem();
        i.setId(id);
        i.setItemName(name);
        i.setItemType(type);
        return i;
    }

    private ServicePackage servicePackage(Long id, String name, List<PackageItem> items) {
        ServicePackage p = new ServicePackage();
        p.setId(id);
        p.setName(name);
        p.setItems(new ArrayList<>(items));
        return p;
    }

    private PackageApplication application(Long id, Long customerId, Long vehicleId,
                                           ServicePackage servicePackage) {
        PackageApplication a = new PackageApplication();
        a.setId(id);
        a.setCustomer(customer(customerId));
        a.setVehicle(vehicle(vehicleId));
        a.setServicePackage(servicePackage);
        a.setPackageName("Basic Service");
        a.setPackageType("BASIC");
        a.setPackagePrice(new BigDecimal("2499.00"));
        a.setPackageGstRate(new BigDecimal("18.00"));
        a.setPackageDuration("2-3 hours");
        return a;
    }

    private JobCardRequestDTO validRequest() {
        JobCardRequestDTO req = new JobCardRequestDTO();
        req.setCustomerId(1L);
        req.setVehicleId(10L);
        req.setServiceType("BRAKE_SERVICE");
        return req;
    }

    private void givenCoreLookupsSucceed() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        when(repository.save(any(JobCard.class))).thenAnswer(inv -> {
            JobCard jc = inv.getArgument(0);
            if (jc.getId() == null) {
                jc.setId(55L);
            }
            return jc;
        });
    }

    // ── JP1: A matching application is attached ───────────────────────────

    @Test
    @DisplayName("JP1 — create with an application for the same customer and vehicle attaches it")
    void jp1_matchingApplicationIsAttached() {
        JobCardRequestDTO req = validRequest();
        req.setPackageApplicationId(7L);

        ServicePackage pkg = servicePackage(3L, "Basic Service", List.of());
        PackageApplication app = application(7L, 1L, 10L, pkg);

        givenCoreLookupsSucceed();
        when(packageApplicationRepository.findById(7L)).thenReturn(Optional.of(app));
        when(jobTaskRepository.countByJobCardId(55L)).thenReturn(0L);
        when(jobTaskRepository.sumLabourCostByJobCardId(55L)).thenReturn(BigDecimal.ZERO);

        service.create(req);

        ArgumentCaptor<JobCard> captor = ArgumentCaptor.forClass(JobCard.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());

        JobCard last = captor.getValue();
        assertThat(last.getPackageApplication()).isNotNull();
        assertThat(last.getPackageApplication().getId()).isEqualTo(7L);
    }

    // ── JP2: Application belongs to another vehicle ───────────────────────

    @Test
    @DisplayName("JP2 — application applied to a different vehicle is rejected")
    void jp2_otherVehicleRejected() {
        JobCardRequestDTO req = validRequest();
        req.setPackageApplicationId(7L);

        PackageApplication app = application(7L, 1L, 99L,
                servicePackage(3L, "Basic Service", List.of()));

        givenCoreLookupsSucceed();
        when(packageApplicationRepository.findById(7L)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different vehicle");
    }

    // ── JP3: Application belongs to another customer ──────────────────────

    @Test
    @DisplayName("JP3 — application sold to a different customer is rejected")
    void jp3_otherCustomerRejected() {
        JobCardRequestDTO req = validRequest();
        req.setPackageApplicationId(7L);

        // Right vehicle, wrong customer.
        PackageApplication app = application(7L, 2L, 10L,
                servicePackage(3L, "Basic Service", List.of()));

        givenCoreLookupsSucceed();
        when(packageApplicationRepository.findById(7L)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different customer");
    }

    // ── JP4: Unknown application ──────────────────────────────────────────

    @Test
    @DisplayName("JP4 — unknown packageApplicationId is a 404")
    void jp4_unknownApplicationNotFound() {
        JobCardRequestDTO req = validRequest();
        req.setPackageApplicationId(404L);

        givenCoreLookupsSucceed();
        when(packageApplicationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("PackageApplication not found");
    }

    // ── JP5: No application at all — existing behaviour preserved ─────────

    @Test
    @DisplayName("JP5 — create without a package id leaves the job card unlinked")
    void jp5_noApplicationLeavesJobCardUnlinked() {
        givenCoreLookupsSucceed();
        when(jobTaskRepository.countByJobCardId(55L)).thenReturn(0L);
        when(jobTaskRepository.sumLabourCostByJobCardId(55L)).thenReturn(BigDecimal.ZERO);

        JobCardResponseDTO response = service.create(validRequest());

        // The repository is never asked for an application at all.
        verify(packageApplicationRepository, never()).findById(any());

        ArgumentCaptor<JobCard> captor = ArgumentCaptor.forClass(JobCard.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPackageApplication()).isNull();

        assertThat(response.getPackageApplicationId()).isNull();
        assertThat(response.getPackageName()).isNull();
        // Empty rather than null, so a client can iterate it unconditionally.
        assertThat(response.getPackageServices()).isEmpty();
    }

    // ── JP6: Snapshot terms and SERVICE items are surfaced ────────────────

    @Test
    @DisplayName("JP6 — the response carries the package snapshot and its SERVICE items")
    void jp6_responseCarriesSnapshotAndServices() {
        JobCardRequestDTO req = validRequest();
        req.setPackageApplicationId(7L);

        ServicePackage pkg = servicePackage(3L, "Basic Service", List.of(
                item(1L, "Engine oil change (3L)", "SERVICE"),
                item(2L, "Oil filter replacement", "SERVICE")));
        PackageApplication app = application(7L, 1L, 10L, pkg);

        givenCoreLookupsSucceed();
        when(packageApplicationRepository.findById(7L)).thenReturn(Optional.of(app));
        when(jobTaskRepository.countByJobCardId(55L)).thenReturn(0L);
        when(jobTaskRepository.sumLabourCostByJobCardId(55L)).thenReturn(BigDecimal.ZERO);

        JobCardResponseDTO response = service.create(req);

        assertThat(response.getPackageApplicationId()).isEqualTo(7L);
        assertThat(response.getPackageName()).isEqualTo("Basic Service");
        assertThat(response.getPackageType()).isEqualTo("BASIC");
        assertThat(response.getPackageDuration()).isEqualTo("2-3 hours");
        assertThat(response.getPackagePrice()).isEqualByComparingTo("2499.00");
        assertThat(response.getPackageGstRate()).isEqualByComparingTo("18.00");
        assertThat(response.getPackageServices())
                .containsExactly("Engine oil change (3L)", "Oil filter replacement");
    }

    // ── JP7: PART items are not service tasks ─────────────────────────────

    @Test
    @DisplayName("JP7 — PART items are excluded from the service task list")
    void jp7_partItemsExcluded() {
        JobCardRequestDTO req = validRequest();
        req.setPackageApplicationId(7L);

        ServicePackage pkg = servicePackage(3L, "Basic Service", List.of(
                item(1L, "Engine oil change (3L)", "SERVICE"),
                item(2L, "Engine Oil 3L", "PART"),
                item(3L, "Oil Filter", "PART")));
        PackageApplication app = application(7L, 1L, 10L, pkg);

        givenCoreLookupsSucceed();
        when(packageApplicationRepository.findById(7L)).thenReturn(Optional.of(app));
        when(jobTaskRepository.countByJobCardId(55L)).thenReturn(0L);
        when(jobTaskRepository.sumLabourCostByJobCardId(55L)).thenReturn(BigDecimal.ZERO);

        JobCardResponseDTO response = service.create(req);

        assertThat(response.getPackageServices()).containsExactly("Engine oil change (3L)");
    }

    // ── JP8: An item type this build cannot read is skipped ───────────────

    @Test
    @DisplayName("JP8 — an item with an unreadable type is skipped, not fatal")
    void jp8_unknownItemTypeSkipped() {
        JobCardRequestDTO req = validRequest();
        req.setPackageApplicationId(7L);

        ServicePackage pkg = servicePackage(3L, "Basic Service", List.of(
                item(1L, "Engine oil change (3L)", "SERVICE"),
                item(2L, "Mystery item", "SOMETHING_ELSE")));
        PackageApplication app = application(7L, 1L, 10L, pkg);

        givenCoreLookupsSucceed();
        when(packageApplicationRepository.findById(7L)).thenReturn(Optional.of(app));
        when(jobTaskRepository.countByJobCardId(55L)).thenReturn(0L);
        when(jobTaskRepository.sumLabourCostByJobCardId(55L)).thenReturn(BigDecimal.ZERO);

        JobCardResponseDTO response = service.create(req);

        assertThat(response.getPackageServices()).containsExactly("Engine oil change (3L)");
    }

    // ── JP9: Application whose catalogue entry has gone ───────────────────

    @Test
    @DisplayName("JP9 — an application with no loaded package reports an empty service list")
    void jp9_missingServicePackageHandled() {
        JobCardRequestDTO req = validRequest();
        req.setPackageApplicationId(7L);

        PackageApplication app = application(7L, 1L, 10L, null);

        givenCoreLookupsSucceed();
        when(packageApplicationRepository.findById(7L)).thenReturn(Optional.of(app));
        when(jobTaskRepository.countByJobCardId(55L)).thenReturn(0L);
        when(jobTaskRepository.sumLabourCostByJobCardId(55L)).thenReturn(BigDecimal.ZERO);

        JobCardResponseDTO response = service.create(req);

        assertThat(response.getPackageApplicationId()).isEqualTo(7L);
        assertThat(response.getPackageServices()).isEmpty();
    }

    // ── JP10: Update can set the link too ─────────────────────────────────

    @Test
    @DisplayName("JP10 — update with a matching application sets the link")
    void jp10_updateAttachesApplication() {
        JobCard existing = new JobCard();
        existing.setId(55L);
        existing.setStatus("RECEIVED");
        existing.setCustomer(customer(1L));
        existing.setVehicle(vehicle(10L));

        JobCardRequestDTO req = validRequest();
        req.setPackageApplicationId(7L);

        ServicePackage pkg = servicePackage(3L, "Basic Service", List.of(
                item(1L, "Engine oil change (3L)", "SERVICE")));
        PackageApplication app = application(7L, 1L, 10L, pkg);

        when(repository.findById(55L)).thenReturn(Optional.of(existing));
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        when(repository.save(any(JobCard.class))).thenAnswer(inv -> inv.getArgument(0));
        when(packageApplicationRepository.findById(7L)).thenReturn(Optional.of(app));
        when(jobTaskRepository.countByJobCardId(55L)).thenReturn(0L);
        when(jobTaskRepository.sumLabourCostByJobCardId(55L)).thenReturn(BigDecimal.ZERO);

        service.update(55L, req);

        ArgumentCaptor<JobCard> captor = ArgumentCaptor.forClass(JobCard.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getPackageApplication()).isNotNull();
        assertThat(captor.getValue().getPackageApplication().getId()).isEqualTo(7L);
    }
}