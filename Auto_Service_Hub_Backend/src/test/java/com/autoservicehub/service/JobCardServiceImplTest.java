package com.autoservicehub.service;

import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.dto.JobCardResponseDTO;
import com.autoservicehub.entity.Appointment;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Inspection;
import com.autoservicehub.entity.InspectionItem;
import com.autoservicehub.entity.JobCard;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Inspection → Job Card half of
 * {@link JobCardServiceImpl} — raising a job card from a vehicle inspection
 * and surfacing the inspection's findings on the job card.
 * Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * J1  Create with inspectionId, same vehicle  → inspection attached to the job card
 * J2  Create with inspectionId, other vehicle → BusinessRuleException
 * J3  Create with unknown inspectionId       → ResourceNotFoundException
 * J4  Job card already linked to another inspection → BusinessRuleException
 * J5  Create without inspectionId           → no inspection attached (unchanged behaviour)
 * J6  Findings surfaced on the job-card response
 * J7  Job card with no inspection           → inspection fields left null
 */
@ExtendWith(MockitoExtension.class)
class JobCardServiceImplTest {

    @Mock JobCardRepository        repository;
    @Mock CustomerRepository       customerRepository;
    @Mock VehicleRepository        vehicleRepository;
    @Mock MechanicRepository       mechanicRepository;
    @Mock AppointmentRepository    appointmentRepository;
    @Mock InspectionRepository     inspectionRepository;
    @Mock InspectionItemRepository inspectionItemRepository;
    @Mock JobTaskRepository        jobTaskRepository;
    @Mock AuditService auditService;

    /**
     * The real calculator, not a mock: {@code JobCardServiceImpl} uses it to
     * normalise the task labour total it reports, and mocking it would hide
     * whether that total is money-scale.
     */
    private final BillingCalculator calculator = new BillingCalculator(new BigDecimal("18"));

    @InjectMocks
    JobCardServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

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

    // ── J1: Create from an inspection of the same vehicle ─────────────────

    @Test
    @DisplayName("J1 — create with inspectionId for the same vehicle attaches the inspection")
    void j1_createWithInspection_attachesIt() {
        JobCardRequestDTO req = validRequest();
        req.setInspectionId(42L);

        Inspection inspection = new Inspection();
        inspection.setId(42L);
        inspection.setVehicle(vehicle(10L));
        inspection.setStatus("COMPLETED");

        givenCoreLookupsSucceed();
        when(inspectionRepository.findById(42L)).thenReturn(Optional.of(inspection));
        when(inspectionRepository.save(any(Inspection.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(inspectionRepository.findByJobCardId(55L))
                .thenReturn(Optional.of(inspection));
        when(inspectionItemRepository.findByInspectionIdOrderByIdAsc(42L))
                .thenReturn(List.of());

        service.create(req);

        ArgumentCaptor<Inspection> captor = ArgumentCaptor.forClass(Inspection.class);
        verify(inspectionRepository).save(captor.capture());

        // The inspection now points at the persisted job card.
        assertThat(captor.getValue().getJobCard()).isNotNull();
        assertThat(captor.getValue().getJobCard().getId()).isEqualTo(55L);
    }

    // ── J2: Inspection belongs to another vehicle ─────────────────────────

    @Test
    @DisplayName("J2 — create with an inspection of a different vehicle → BusinessRuleException")
    void j2_inspectionDifferentVehicle_throws() {
        JobCardRequestDTO req = validRequest();
        req.setInspectionId(42L);

        Inspection inspection = new Inspection();
        inspection.setId(42L);
        inspection.setVehicle(vehicle(77L));

        givenCoreLookupsSucceed();
        when(inspectionRepository.findById(42L)).thenReturn(Optional.of(inspection));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different vehicle");
    }

    // ── J3: Unknown inspection ────────────────────────────────────────────

    @Test
    @DisplayName("J3 — create with an unknown inspectionId → ResourceNotFoundException")
    void j3_unknownInspection_throws() {
        JobCardRequestDTO req = validRequest();
        req.setInspectionId(99L);

        givenCoreLookupsSucceed();
        when(inspectionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Inspection not found: 99");
    }

    // ── J4: Job card already linked to another inspection ─────────────────

    @Test
    @DisplayName("J4 — job card already linked to a different inspection → BusinessRuleException")
    void j4_jobCardAlreadyLinked_throws() {
        JobCardRequestDTO req = validRequest();
        req.setInspectionId(42L);

        Inspection requested = new Inspection();
        requested.setId(42L);
        requested.setVehicle(vehicle(10L));

        Inspection existing = new Inspection();
        existing.setId(43L);
        existing.setVehicle(vehicle(10L));

        givenCoreLookupsSucceed();
        when(inspectionRepository.findById(42L)).thenReturn(Optional.of(requested));
        when(inspectionRepository.findByJobCardId(55L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already linked to inspection 43");
    }

    // ── J5: No inspection supplied — existing behaviour unchanged ─────────

    @Test
    @DisplayName("J5 — create without inspectionId attaches nothing (existing behaviour preserved)")
    void j5_createWithoutInspection_attachesNothing() {
        givenCoreLookupsSucceed();
        when(inspectionRepository.findByJobCardId(55L)).thenReturn(Optional.empty());

        JobCardResponseDTO response = service.create(validRequest());

        assertThat(response.getId()).isEqualTo(55L);
        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        assertThat(response.getInspectionId()).isNull();
        verify(inspectionRepository, never()).save(any(Inspection.class));
    }

    // ── J6: Findings surfaced on the job-card response ────────────────────

    @Test
    @DisplayName("J6 — job-card response carries the originating inspection's findings")
    void j6_findingsSurfacedOnResponse() {
        Inspection inspection = new Inspection();
        inspection.setId(42L);
        inspection.setVehicle(vehicle(10L));
        inspection.setStatus("COMPLETED");

        InspectionItem item = new InspectionItem();
        item.setId(7L);
        item.setChecklistItem("Front brake pads");
        item.setFinding("Worn below 2mm — replacement required");

        JobCard existing = new JobCard();
        existing.setId(55L);
        existing.setVehicle(vehicle(10L));
        existing.setStatus("IN_REPAIR");

        when(repository.findById(55L)).thenReturn(Optional.of(existing));
        when(inspectionRepository.findByJobCardId(55L)).thenReturn(Optional.of(inspection));
        when(inspectionItemRepository.findByInspectionIdOrderByIdAsc(42L))
                .thenReturn(List.of(item));

        JobCardResponseDTO response = service.getById(55L);

        assertThat(response.getInspectionId()).isEqualTo(42L);
        assertThat(response.getInspectionStatus()).isEqualTo("COMPLETED");
        assertThat(response.getInspectionItems()).hasSize(1);
        assertThat(response.getInspectionItems().get(0).getFinding())
                .isEqualTo("Worn below 2mm — replacement required");
    }

    // ── J7: Job card with no inspection → fields left null ────────────────

    @Test
    @DisplayName("J7 — job card with no linked inspection leaves inspection fields null")
    void j7_noInspection_leavesFieldsNull() {
        JobCard existing = new JobCard();
        existing.setId(55L);
        existing.setVehicle(vehicle(10L));
        existing.setStatus("RECEIVED");

        when(repository.findById(55L)).thenReturn(Optional.of(existing));
        when(inspectionRepository.findByJobCardId(55L)).thenReturn(Optional.empty());

        JobCardResponseDTO response = service.getById(55L);

        assertThat(response.getInspectionId()).isNull();
        assertThat(response.getInspectionItems()).isNull();
    }
}
