package com.autoservicehub.service;

import com.autoservicehub.dto.InspectionItemRequestDTO;
import com.autoservicehub.dto.InspectionRequestDTO;
import com.autoservicehub.dto.InspectionResponseDTO;
import com.autoservicehub.entity.Inspection;
import com.autoservicehub.entity.InspectionItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.InspectionItemRepository;
import com.autoservicehub.repository.InspectionRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.InspectionServiceImpl;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InspectionServiceImpl} — the Inspection → Job Card
 * integration (core workflow: Vehicle Inspection → Job Card).
 * Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * T1  Create with vehicleId            → vehicle linked, status defaults to PENDING
 * T2  Create with findings             → each item persisted against the inspection
 * T4  Vehicle not found                → ResourceNotFoundException
 * T5  Job card of a different vehicle  → BusinessRuleException
 * T6  Job card already linked          → BusinessRuleException
 * T7  Re-linking the same job card     → allowed (no-op)
 * T8  Unsupported status value         → BusinessRuleException
 * T9  Blank status on update           → existing status preserved
 * T10 Update replaces findings when a list is sent
 * T11 Update leaves findings intact when items omitted
 * T12 getById returns vehicle + job card + findings
 * T13 listByVehicle returns paged history
 * T14 getByJobCardId with no link      → ResourceNotFoundException
 * T15 findByJobCardIdOrNull with no link → null, not an exception
 * T16 delete removes findings and detaches the job card
 */
@ExtendWith(MockitoExtension.class)
class InspectionServiceImplTest {

    @Mock InspectionRepository     repository;
    @Mock InspectionItemRepository itemRepository;
    @Mock VehicleRepository       vehicleRepository;
    @Mock JobCardRepository       jobCardRepository;

    @InjectMocks
    InspectionServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private Vehicle vehicle(Long id) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setRegistrationNo("MH-12-AB-1234");
        v.setModel("Swift VXI");
        return v;
    }

    private JobCard jobCard(Long id, Long vehicleId) {
        JobCard jc = new JobCard();
        jc.setId(id);
        jc.setJobCardNumber("JC-20260930120000");
        if (vehicleId != null) {
            jc.setVehicle(vehicle(vehicleId));
        }
        return jc;
    }

    private Inspection inspection(Long id, Long vehicleId) {
        Inspection i = new Inspection();
        i.setId(id);
        if (vehicleId != null) {
            i.setVehicle(vehicle(vehicleId));
        }
        return i;
    }

    /** Saves an entity the way JPA would, assigning an id on first persist. */
    private void givenSavedWithId(long assignedId) {
        when(repository.save(any(Inspection.class))).thenAnswer(inv -> {
            Inspection i = inv.getArgument(0);
            if (i.getId() == null) {
                i.setId(assignedId);
            }
            return i;
        });
    }

    private InspectionRequestDTO validRequest() {
        InspectionRequestDTO req = new InspectionRequestDTO();
        req.setVehicleId(10L);
        req.setComplaint("Brake noise and pulling to the left under braking.");
        return req;
    }

    private InspectionItemRequestDTO item(String checklist, String finding) {
        InspectionItemRequestDTO dto = new InspectionItemRequestDTO();
        dto.setChecklistItem(checklist);
        dto.setFinding(finding);
        return dto;
    }

    // ── T1: Create links vehicle, defaults status ─────────────────────────

    @Test
    @DisplayName("T1 — create links the vehicle and defaults status to PENDING")
    void t1_create_linksVehicle_andDefaultsStatus() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        givenSavedWithId(100L);
        when(itemRepository.findByInspectionIdOrderByIdAsc(100L)).thenReturn(List.of());

        InspectionResponseDTO response = service.create(validRequest());

        assertThat(response.getVehicleId()).isEqualTo(10L);
        assertThat(response.getVehicleInfo()).contains("MH-12-AB-1234");
        assertThat(response.getStatus()).isEqualTo(InspectionServiceImpl.STATUS_PENDING);
        assertThat(response.getJobCardId()).isNull();
        assertThat(response.getComplaint()).contains("Brake noise");
    }

    // ── T2: Create persists findings ─────────────────────────────────────

    @Test
    @DisplayName("T2 — create persists each checklist finding against the inspection")
    void t2_create_persistsFindings() {
        InspectionRequestDTO req = validRequest();
        req.setItems(List.of(
                item("Front brake pads", "Worn below 2mm — replacement required"),
                item("Brake fluid", "Clean")));

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        givenSavedWithId(100L);
        when(itemRepository.findByInspectionIdOrderByIdAsc(100L)).thenReturn(List.of());

        service.create(req);

        ArgumentCaptor<InspectionItem> captor = ArgumentCaptor.forClass(InspectionItem.class);
        verify(itemRepository, times(2)).save(captor.capture());
        List<InspectionItem> saved = captor.getAllValues();

        assertThat(saved).allSatisfy(i -> assertThat(i.getInspection()).isNotNull());
        assertThat(saved.get(0).getChecklistItem()).isEqualTo("Front brake pads");
        assertThat(saved.get(1).getFinding()).isEqualTo("Clean");
    }

    // ── T4: Vehicle not found ─────────────────────────────────────────────

    @Test
    @DisplayName("T4 — unknown vehicleId → ResourceNotFoundException, nothing persisted")
    void t4_vehicleNotFound_throws() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        InspectionRequestDTO req = validRequest();
        req.setVehicleId(99L);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Vehicle not found: 99");

        verify(repository, never()).save(any(Inspection.class));
    }

    // ── T5: Job card belongs to another vehicle ───────────────────────────

    @Test
    @DisplayName("T5 — job card of a different vehicle → BusinessRuleException")
    void t5_jobCardDifferentVehicle_throws() {
        InspectionRequestDTO req = validRequest();
        req.setJobCardId(55L);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L, 77L)));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different vehicle");

        verify(repository, never()).save(any(Inspection.class));
    }

    // ── T6: Job card already linked to another inspection ────────────────

    @Test
    @DisplayName("T6 — job card already linked to another inspection → BusinessRuleException")
    void t6_jobCardAlreadyLinked_throws() {
        InspectionRequestDTO req = validRequest();
        req.setJobCardId(55L);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L, 10L)));
        when(repository.findByJobCardId(55L)).thenReturn(Optional.of(inspection(42L, 10L)));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already linked to inspection 42");

        verify(repository, never()).save(any(Inspection.class));
    }

    // ── T7: Re-linking the same job card is a no-op ───────────────────────

    @Test
    @DisplayName("T7 — re-linking the same inspection to the same job card is allowed")
    void t7_relinkSameJobCard_allowed() {
        Inspection existing = inspection(42L, 10L);

        InspectionRequestDTO req = validRequest();
        req.setJobCardId(55L);

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L, 10L)));
        when(repository.findByJobCardId(55L)).thenReturn(Optional.of(existing));
        when(repository.save(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findByInspectionIdOrderByIdAsc(42L)).thenReturn(List.of());

        assertThat(service.update(42L, req).getJobCardId()).isEqualTo(55L);
    }

    // ── T8: Unsupported status ────────────────────────────────────────────

    @Test
    @DisplayName("T8 — status outside the allowed set → BusinessRuleException")
    void t8_unsupportedStatus_throws() {
        InspectionRequestDTO req = validRequest();
        req.setStatus("APPROVED");

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unsupported inspection status");
    }

    @Test
    @DisplayName("T8b — status is normalised to upper case when valid")
    void t8b_statusNormalised() {
        InspectionRequestDTO req = validRequest();
        req.setStatus("completed");

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        givenSavedWithId(100L);
        when(itemRepository.findByInspectionIdOrderByIdAsc(100L)).thenReturn(List.of());

        assertThat(service.create(req).getStatus())
                .isEqualTo(InspectionServiceImpl.STATUS_COMPLETED);
    }

    // ── T9: Blank status on update preserves the stored one ───────────────

    @Test
    @DisplayName("T9 — blank status on update keeps the existing status")
    void t9_blankStatus_preservesExisting() {
        Inspection existing = inspection(42L, 10L);
        existing.setStatus(InspectionServiceImpl.STATUS_IN_PROGRESS);

        InspectionRequestDTO req = validRequest();
        req.setStatus("   ");

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        when(repository.save(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findByInspectionIdOrderByIdAsc(42L)).thenReturn(List.of());

        assertThat(service.update(42L, req).getStatus())
                .isEqualTo(InspectionServiceImpl.STATUS_IN_PROGRESS);
    }

    // ── T10 / T11: finding replacement semantics ──────────────────────────

    @Test
    @DisplayName("T10 — update replaces stored findings when a list is supplied")
    void t10_update_replacesFindings() {
        Inspection existing = inspection(42L, 10L);

        InspectionRequestDTO req = validRequest();
        req.setItems(List.of(item("Suspension bushings", "Cracked — replace both sides")));

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        when(repository.save(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findByInspectionIdOrderByIdAsc(42L)).thenReturn(List.of());

        service.update(42L, req);

        verify(itemRepository).deleteAll(any());
        verify(itemRepository).save(any(InspectionItem.class));
    }

    @Test
    @DisplayName("T11 — update leaves findings intact when items is omitted")
    void t11_update_omittedItems_leavesFindings() {
        Inspection existing = inspection(42L, 10L);

        InspectionRequestDTO req = validRequest();
        req.setItems(null);

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        when(repository.save(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findByInspectionIdOrderByIdAsc(42L)).thenReturn(List.of());

        service.update(42L, req);

        verify(itemRepository, never()).deleteAll(any());
        verify(itemRepository, never()).save(any(InspectionItem.class));
    }

    // ── T12: getById returns the full picture ─────────────────────────────

    @Test
    @DisplayName("T12 — getById returns vehicle, job card and findings")
    void t12_getById_returnsFullPicture() {
        Inspection existing = inspection(42L, 10L);
        existing.setJobCard(jobCard(55L, 10L));

        InspectionItem item = new InspectionItem();
        item.setId(7L);
        item.setChecklistItem("Front brake pads");
        item.setFinding("Worn below 2mm");

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(itemRepository.findByInspectionIdOrderByIdAsc(42L)).thenReturn(List.of(item));

        InspectionResponseDTO response = service.getById(42L);

        assertThat(response.getVehicleId()).isEqualTo(10L);
        assertThat(response.getJobCardId()).isEqualTo(55L);
        assertThat(response.getJobCardNumber()).isEqualTo("JC-20260930120000");
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getFinding()).isEqualTo("Worn below 2mm");
    }

    // ── T13: listByVehicle ────────────────────────────────────────────────

    @Test
    @DisplayName("T13 — listByVehicle returns the vehicle's inspection history")
    void t13_listByVehicle_returnsHistory() {
        when(vehicleRepository.existsById(10L)).thenReturn(true);
        when(repository.findByVehicleIdOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of(inspection(42L, 10L)), PageRequest.of(0, 20), 1));
        when(itemRepository.findByInspectionIdOrderByIdAsc(42L)).thenReturn(List.of());

        Page<InspectionResponseDTO> page = service.listByVehicle(10L, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getVehicleId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("T13b — listByVehicle on an unknown vehicle → ResourceNotFoundException")
    void t13b_listByVehicle_unknownVehicle_throws() {
        when(vehicleRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.listByVehicle(99L, PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Vehicle not found: 99");
    }

    // ── T14 / T15: job-card lookup ────────────────────────────────────────

    @Test
    @DisplayName("T14 — getByJobCardId with no linked inspection → ResourceNotFoundException")
    void t14_getByJobCardId_noLink_throws() {
        when(repository.findByJobCardId(55L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByJobCardId(55L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No inspection is linked to JobCard: 55");
    }

    @Test
    @DisplayName("T15 — findByJobCardIdOrNull with no linked inspection returns null, not an error")
    void t15_findByJobCardIdOrNull_noLink_returnsNull() {
        when(repository.findByJobCardId(55L)).thenReturn(Optional.empty());

        assertThat(service.findByJobCardIdOrNull(55L)).isNull();
        assertThat(service.findByJobCardIdOrNull(null)).isNull();
    }

    @Test
    @DisplayName("T15b — findByJobCardIdOrNull returns the inspection when linked")
    void t15b_findByJobCardIdOrNull_linked_returnsInspection() {
        when(repository.findByJobCardId(55L)).thenReturn(Optional.of(inspection(42L, 10L)));
        when(itemRepository.findByInspectionIdOrderByIdAsc(42L)).thenReturn(List.of());

        assertThat(service.findByJobCardIdOrNull(55L).getId()).isEqualTo(42L);
    }

    // ── T16: delete cleans up ─────────────────────────────────────────────

    @Test
    @DisplayName("T16 — delete removes the inspection's findings and detaches the job card")
    void t16_delete_removesFindingsAndDetaches() {
        Inspection existing = inspection(42L, 10L);
        existing.setJobCard(jobCard(55L, 10L));

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(itemRepository.findByInspectionIdOrderByIdAsc(42L))
                .thenReturn(List.of(new InspectionItem()));

        service.delete(42L);

        verify(itemRepository).deleteAll(any());
        verify(repository).deleteById(42L);
        assertThat(existing.getJobCard()).isNull();
    }

    @Test
    @DisplayName("T16b — deleting an unknown inspection → ResourceNotFoundException")
    void t16b_delete_unknown_throws() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Inspection not found: 99");
    }

    // ── Estimated cost is optional but must survive the round trip ───────

    @Test
    @DisplayName("T17 — estimatedCost is carried through unchanged")
    void t17_estimatedCost_carriedThrough() {
        InspectionRequestDTO req = validRequest();
        req.setEstimatedCost(new BigDecimal("4250.50"));

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle(10L)));
        givenSavedWithId(100L);
        when(itemRepository.findByInspectionIdOrderByIdAsc(100L)).thenReturn(List.of());

        assertThat(service.create(req).getEstimatedCost())
                .isEqualByComparingTo(new BigDecimal("4250.50"));
    }
}
