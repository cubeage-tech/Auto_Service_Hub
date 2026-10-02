package com.autoservicehub.service;

import com.autoservicehub.dto.JobTaskRequestDTO;
import com.autoservicehub.dto.JobTaskStatusRequestDTO;
import com.autoservicehub.dto.JobTaskWorkNotesRequestDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.service.impl.JobTaskServiceImpl;
import com.autoservicehub.util.BillingCalculator;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JobTaskServiceImpl} — CRUD, mechanic assignment, status
 * changes, work notes and labour (FR-JOB-3 / FR-JOB-5).
 * Pure Mockito — no Spring context, no database.
 *
 * <p>{@link BillingCalculator} is the real one, not a mock: it owns the money
 * rules (scale, rounding) that this service delegates to, and mocking it would
 * hide whether labour is actually normalised.
 *
 * Test cases
 * ----------
 * T1  Create               → task attached to the job card, PENDING
 * T2  Create with mechanic → mechanic attached
 * T3  Create unknown job card  → ResourceNotFoundException
 * T4  Create unknown mechanic  → ResourceNotFoundException
 * T5  Create blank description → BusinessRuleException
 * T6  Create null description  → BusinessRuleException
 * T7  Create negative labour   → BusinessRuleException, nothing saved
 * T8  Create sub-paise labour  → BusinessRuleException
 * T9  Create null labour       → zero, not null
 * T10 Create invalid status    → BusinessRuleException
 * T11 Status is case-insensitive and trimmed
 * T12 Get by id             → returned
 * T13 Get unknown task      → ResourceNotFoundException
 * T14 List by job card      → page of that card's tasks
 * T15 List unknown job card → ResourceNotFoundException
 * T16 Update                → fields changed, status/mechanic untouched when omitted
 * T17 Update reassigns mechanic when given
 * T18 Update unknown task   → ResourceNotFoundException
 * T19 Update invalid status → BusinessRuleException
 * T20 Delete                → removed
 * T21 Delete unknown task   → ResourceNotFoundException
 * T22 Status change         → status updated, nothing else touched
 * T23 Status invalid        → BusinessRuleException
 * T24 Work notes           → notes stored, cost and status untouched
 * T25 Work notes cleared   → blank becomes null
 * T26 Assign mechanic      → attached
 * T27 Un-assign mechanic   → cleared
 * T28 Assign unknown mechanic → ResourceNotFoundException
 * T29 Labour total is the stored sum, computed server-side
 * T30 Labour total unknown job card → ResourceNotFoundException
 * T31 Update cannot move a task to another job card
 * T32 DELIVERED job card refuses task creation
 * T33 DELIVERED job card refuses task update
 * T34 DELIVERED job card refuses task deletion
 * T35 DELIVERED job card refuses work notes and assignment
 * T36 DELIVERED job card is still readable
 * T37 Every earlier job state still accepts work
 * T38 Forward transitions PENDING to IN_PROGRESS to COMPLETED
 * T39 PENDING and IN_PROGRESS may be cancelled
 * T40 PENDING cannot jump to COMPLETED
 * T41 COMPLETED and CANCELLED are final
 * T42 Re-submitting the same status is accepted
 * T43 Update cannot bypass the transition rule
 */
@ExtendWith(MockitoExtension.class)
class JobTaskServiceImplTest {

    @Mock JobTaskRepository repository;
    @Mock JobCardRepository jobCardRepository;
    @Mock MechanicRepository mechanicRepository;
    @Mock AuditService       auditService;

    JobTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        // The real calculator: labour normalisation is part of what is tested.
        service = new JobTaskServiceImpl(
                repository, jobCardRepository, mechanicRepository, auditService,
                new BillingCalculator(new BigDecimal("18")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private JobCard jobCard(Long id) {
        JobCard jc = new JobCard();
        jc.setId(id);
        jc.setJobCardNumber("JC-20260115100000");
        jc.setServiceType("BRAKE_SERVICE");
        jc.setStatus("IN_REPAIR");
        return jc;
    }

    private Mechanic mechanic(Long id, String name) {
        Mechanic m = new Mechanic();
        m.setId(id);
        m.setName(name);
        m.setEmployeeCode("MECH-" + id);
        return m;
    }

    private JobTask task(Long id, JobCard jc, String status) {
        JobTask t = new JobTask();
        t.setId(id);
        t.setJobCard(jc);
        t.setDescription("Replace front brake pads");
        t.setStatus(status);
        t.setLabourCost(new BigDecimal("500.00"));
        return t;
    }

    private JobTaskRequestDTO request(String description) {
        JobTaskRequestDTO dto = new JobTaskRequestDTO();
        dto.setDescription(description);
        dto.setLabourCost(new BigDecimal("500.00"));
        return dto;
    }

    private void givenSaved() {
        when(repository.save(any(JobTask.class))).thenAnswer(invocation -> {
            JobTask saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(100L);
            }
            return saved;
        });
    }

    // ── T1..T2: create ───────────────────────────────────────────────────

    @Test
    @DisplayName("T1 — a created task belongs to the job card and starts PENDING")
    void t1_create_attachesToJobCard() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenSaved();

        var response = service.create(55L, request("Replace front brake pads"));

        assertThat(response.getJobCardId()).isEqualTo(55L);
        assertThat(response.getJobCardNumber()).isEqualTo("JC-20260115100000");
        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getDescription()).isEqualTo("Replace front brake pads");

        ArgumentCaptor<JobTask> captor = ArgumentCaptor.forClass(JobTask.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getJobCard().getId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("T2 — a task can be created already assigned to a mechanic")
    void t2_create_withMechanic() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        when(mechanicRepository.findById(7L)).thenReturn(Optional.of(mechanic(7L, "Anil")));
        givenSaved();

        JobTaskRequestDTO req = request("Replace front brake pads");
        req.setMechanicId(7L);

        var response = service.create(55L, req);

        assertThat(response.getMechanicId()).isEqualTo(7L);
        assertThat(response.getMechanicName()).isEqualTo("Anil");
    }

    // ── T3..T4: references must exist ────────────────────────────────────

    @Test
    @DisplayName("T3 — an unknown job card is rejected and nothing is saved")
    void t3_unknownJobCard_throws() {
        when(jobCardRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(999L, request("Replace front brake pads")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobCard not found: 999");

        verify(repository, never()).save(any(JobTask.class));
    }

    @Test
    @DisplayName("T4 — an unknown mechanic is rejected and nothing is saved")
    void t4_unknownMechanic_throws() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        when(mechanicRepository.findById(404L)).thenReturn(Optional.empty());

        JobTaskRequestDTO req = request("Replace front brake pads");
        req.setMechanicId(404L);

        assertThatThrownBy(() -> service.create(55L, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Mechanic not found: 404");

        verify(repository, never()).save(any(JobTask.class));
    }

    // ── T5..T10: validation ─────────────────────────────────────────────

    @Test
    @DisplayName("T5 — a blank description is rejected and nothing is saved")
    void t5_blankDescription_throws() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));

        assertThatThrownBy(() -> service.create(55L, request("   ")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("description is required");

        verify(repository, never()).save(any(JobTask.class));
    }

    @Test
    @DisplayName("T6 — a null description is rejected and nothing is saved")
    void t6_nullDescription_throws() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));

        assertThatThrownBy(() -> service.create(55L, request(null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("description is required");

        verify(repository, never()).save(any(JobTask.class));
    }

    @Test
    @DisplayName("T7 — a negative labour cost is rejected and nothing is saved")
    void t7_negativeLabour_throws() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));

        JobTaskRequestDTO req = request("Replace front brake pads");
        req.setLabourCost(new BigDecimal("-250.00"));

        assertThatThrownBy(() -> service.create(55L, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("labourCost must not be negative");

        verify(repository, never()).save(any(JobTask.class));
    }

    @Test
    @DisplayName("T8 — sub-paise labour is rejected, matching the billing money rule")
    void t8_subPaiseLabour_throws() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));

        JobTaskRequestDTO req = request("Replace front brake pads");
        req.setLabourCost(new BigDecimal("250.005"));

        assertThatThrownBy(() -> service.create(55L, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("decimal places");

        verify(repository, never()).save(any(JobTask.class));
    }

    @Test
    @DisplayName("T9 — an omitted labour cost is stored as zero, not null")
    void t9_nullLabour_becomesZero() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenSaved();

        JobTaskRequestDTO req = request("Diagnose noise");
        req.setLabourCost(null);

        var response = service.create(55L, req);

        assertThat(response.getLabourCost()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("T10 — an unsupported status is rejected and nothing is saved")
    void t10_invalidStatus_throws() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));

        JobTaskRequestDTO req = request("Replace front brake pads");
        req.setStatus("ALMOST_DONE");

        assertThatThrownBy(() -> service.create(55L, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unsupported task status");

        verify(repository, never()).save(any(JobTask.class));
    }

    // ── T11..T15: read ──────────────────────────────────────────────────

    @Test
    @DisplayName("T11 — status is trimmed and normalised to upper case")
    void t11_status_isNormalised() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard(55L)));
        givenSaved();

        JobTaskRequestDTO req = request("Replace front brake pads");
        req.setStatus("  in_progress  ");

        assertThat(service.create(55L, req).getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("T12 — a task is returned by id")
    void t12_getById_returnsTask() {
        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "PENDING")));

        var response = service.getById(100L);

        assertThat(response.getId()).isEqualTo(100L);
        assertThat(response.getJobCardId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("T13 — an unknown task id → ResourceNotFoundException")
    void t13_getUnknown_throws() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobTask not found: 999");
    }

    @Test
    @DisplayName("T14 — a job card's tasks are listed in recorded order")
    void t14_listByJobCard_returnsItsTasks() {
        when(jobCardRepository.existsById(55L)).thenReturn(true);
        when(repository.findByJobCardIdOrderByIdAsc(anyLong(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(
                        task(1L, jobCard(55L), "COMPLETED"),
                        task(2L, jobCard(55L), "PENDING"))));

        var page = service.listByJobCard(55L, org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(com.autoservicehub.dto.JobTaskResponseDTO::getId)
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("T15 — listing an unknown job card → ResourceNotFoundException")
    void t15_listUnknownJobCard_throws() {
        when(jobCardRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.listByJobCard(999L,
                        org.springframework.data.domain.PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobCard not found: 999");
    }

    // ── T16..T19: update ─────────────────────────────────────────────────

    @Test
    @DisplayName("T16 — update changes the fields but leaves an omitted status and mechanic alone")
    void t16_update_leavesStatusAndMechanicAlone() {
        JobTask existing = task(100L, jobCard(55L), "IN_PROGRESS");
        existing.setMechanic(mechanic(7L, "Anil"));
        when(repository.findById(100L)).thenReturn(Optional.of(existing));
        givenSaved();

        JobTaskRequestDTO req = request("Replace front brake pads and discs");
        req.setLabourCost(new BigDecimal("750.00"));
        // status and mechanicId deliberately omitted.

        var response = service.update(100L, req);

        assertThat(response.getDescription()).isEqualTo("Replace front brake pads and discs");
        assertThat(response.getLabourCost()).isEqualByComparingTo("750.00");
        assertThat(response.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(response.getMechanicId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("T17 — update reassigns the mechanic when one is named")
    void t17_update_reassignsMechanic() {
        JobTask existing = task(100L, jobCard(55L), "PENDING");
        existing.setMechanic(mechanic(7L, "Anil"));
        when(repository.findById(100L)).thenReturn(Optional.of(existing));
        when(mechanicRepository.findById(9L)).thenReturn(Optional.of(mechanic(9L, "Sur")));
        givenSaved();

        JobTaskRequestDTO req = request("Replace front brake pads");
        req.setMechanicId(9L);

        assertThat(service.update(100L, req).getMechanicName()).isEqualTo("Sur");
    }

    @Test
    @DisplayName("T18 — updating an unknown task → ResourceNotFoundException")
    void t18_updateUnknown_throws() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(999L, request("Replace front brake pads")))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any(JobTask.class));
    }

    @Test
    @DisplayName("T19 — update with an unsupported status is rejected and nothing is saved")
    void t19_updateInvalidStatus_throws() {
        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "PENDING")));

        JobTaskRequestDTO req = request("Replace front brake pads");
        req.setStatus("HALF_DONE");

        assertThatThrownBy(() -> service.update(100L, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unsupported task status");

        verify(repository, never()).save(any(JobTask.class));
    }

    // ── T20..T21: delete ────────────────────────────────────────────────

    @Test
    @DisplayName("T20 — a task is removed")
    void t20_delete_removesTask() {
        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "PENDING")));

        service.delete(100L);

        verify(repository).delete(any(JobTask.class));
    }

    @Test
    @DisplayName("T21 — deleting an unknown task → ResourceNotFoundException")
    void t21_deleteUnknown_throws() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(999L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).delete(any(JobTask.class));
    }

    // ── T22..T23: status change ──────────────────────────────────────────

    @Test
    @DisplayName("T22 — a status change touches nothing but the status")
    void t22_updateStatus_onlyChangesStatus() {
        JobTask existing = task(100L, jobCard(55L), "IN_PROGRESS");
        existing.setMechanic(mechanic(7L, "Anil"));
        existing.setWorkNotes("Front axle lifted");
        when(repository.findById(100L)).thenReturn(Optional.of(existing));
        givenSaved();

        JobTaskStatusRequestDTO req = new JobTaskStatusRequestDTO();
        req.setStatus("COMPLETED");

        var response = service.updateStatus(100L, req);

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getLabourCost()).isEqualByComparingTo("500.00");
        assertThat(response.getWorkNotes()).isEqualTo("Front axle lifted");
        assertThat(response.getMechanicId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("T23 — an unsupported status is rejected and nothing is saved")
    void t23_updateStatusInvalid_throws() {
        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "PENDING")));

        JobTaskStatusRequestDTO req = new JobTaskStatusRequestDTO();
        req.setStatus("DONE_SORT_OF");

        assertThatThrownBy(() -> service.updateStatus(100L, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unsupported task status");

        verify(repository, never()).save(any(JobTask.class));
    }

    // ── T24..T25: work notes ─────────────────────────────────────────────

    @Test
    @DisplayName("T24 — recording work notes leaves the cost and status untouched")
    void t24_workNotes_onlyChangesNotes() {
        JobTask existing = task(100L, jobCard(55L), "IN_PROGRESS");
        when(repository.findById(100L)).thenReturn(Optional.of(existing));
        givenSaved();

        JobTaskWorkNotesRequestDTO req = new JobTaskWorkNotesRequestDTO();
        req.setWorkNotes("Rear pads also worn, advised customer");

        var response = service.updateWorkNotes(100L, req);

        assertThat(response.getWorkNotes()).isEqualTo("Rear pads also worn, advised customer");
        assertThat(response.getLabourCost()).isEqualByComparingTo("500.00");
        assertThat(response.getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("T25 — blank work notes are cleared to null, not stored as an empty string")
    void t25_blankWorkNotes_becomeNull() {
        JobTask existing = task(100L, jobCard(55L), "IN_PROGRESS");
        existing.setWorkNotes("Old note");
        when(repository.findById(100L)).thenReturn(Optional.of(existing));
        givenSaved();

        JobTaskWorkNotesRequestDTO req = new JobTaskWorkNotesRequestDTO();
        req.setWorkNotes("   ");

        assertThat(service.updateWorkNotes(100L, req).getWorkNotes()).isNull();
    }

    // ── T26..T28: mechanic assignment ───────────────────────────────────

    @Test
    @DisplayName("T26 — a mechanic can be allocated to a task")
    void t26_assignMechanic_attaches() {
        JobTask existing = task(100L, jobCard(55L), "PENDING");
        when(repository.findById(100L)).thenReturn(Optional.of(existing));
        when(mechanicRepository.findById(7L)).thenReturn(Optional.of(mechanic(7L, "Anil")));
        givenSaved();

        var response = service.assignMechanic(100L, 7L);

        assertThat(response.getMechanicId()).isEqualTo(7L);
        assertThat(response.getMechanicName()).isEqualTo("Anil");
        // Allocation must not disturb the money or the status.
        assertThat(response.getLabourCost()).isEqualByComparingTo("500.00");
        assertThat(response.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("T27 — a null mechanic id clears the allocation")
    void t27_assignMechanic_null_unassigns() {
        JobTask existing = task(100L, jobCard(55L), "PENDING");
        existing.setMechanic(mechanic(7L, "Anil"));
        when(repository.findById(100L)).thenReturn(Optional.of(existing));
        givenSaved();

        assertThat(service.assignMechanic(100L, null).getMechanicId()).isNull();
    }

    @Test
    @DisplayName("T28 — assigning an unknown mechanic → ResourceNotFoundException, nothing saved")
    void t28_assignUnknownMechanic_throws() {
        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "PENDING")));
        when(mechanicRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignMechanic(100L, 404L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Mechanic not found: 404");

        verify(repository, never()).save(any(JobTask.class));
    }

    // ── T29..T30: labour total ───────────────────────────────────────────

    @Test
    @DisplayName("T29 — the labour total is the stored sum, computed server-side")
    void t29_labourTotal_isServerComputed() {
        when(jobCardRepository.existsById(55L)).thenReturn(true);
        when(repository.sumLabourCostByJobCardId(55L)).thenReturn(new BigDecimal("1250.00"));

        assertThat(service.totalLabourCost(55L)).isEqualByComparingTo("1250.00");
    }

    @Test
    @DisplayName("T30 — the labour total of an unknown job card → ResourceNotFoundException")
    void t30_labourTotalUnknownJobCard_throws() {
        when(jobCardRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.totalLabourCost(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── T31: the parent cannot be reassigned ─────────────────────────────

    @Test
    @DisplayName("T31 — a task cannot be moved to another job card by an update")
    void t31_update_cannotMoveTaskToAnotherJobCard() {
        JobCard original = jobCard(55L);
        JobTask existing = task(100L, original, "PENDING");
        when(repository.findById(100L)).thenReturn(Optional.of(existing));
        givenSaved();

        // The request payload has no job card field at all, and the service
        // never reads one, so the task stays on its original job. This asserts
        // the request DTO cannot even carry a competing parent.
        assertThat(com.autoservicehub.dto.JobTaskRequestDTO.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("jobCardId");

        var response = service.update(100L, request("Replace front brake pads"));

        assertThat(response.getJobCardId()).isEqualTo(55L);
        assertThat(existing.getJobCard().getId()).isEqualTo(55L);
    }

    // ── T32..T37: a DELIVERED job card is closed to task work ─────────────

    /** The job card as it looks once the vehicle has been handed back. */
    private JobCard deliveredJobCard() {
        JobCard jc = jobCard(55L);
        jc.setStatus("DELIVERED");
        return jc;
    }

    private JobTaskStatusRequestDTO statusRequest(String status) {
        JobTaskStatusRequestDTO dto = new JobTaskStatusRequestDTO();
        dto.setStatus(status);
        return dto;
    }

    @Test
    @DisplayName("T32 — a task cannot be added to a DELIVERED job card, nothing saved")
    void t32_createOnDeliveredJobCard_throws() {
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(deliveredJobCard()));

        assertThatThrownBy(() -> service.create(55L, request("Replace front brake pads")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("is DELIVERED");

        verify(repository, never()).save(any(JobTask.class));
    }

    @Test
    @DisplayName("T33 — a task on a DELIVERED job card cannot be updated, nothing saved")
    void t33_updateOnDeliveredJobCard_throws() {
        when(repository.findById(100L))
                .thenReturn(Optional.of(task(100L, deliveredJobCard(), "COMPLETED")));

        assertThatThrownBy(() -> service.update(100L, request("Replace front brake pads")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("is DELIVERED");

        verify(repository, never()).save(any(JobTask.class));
    }

    @Test
    @DisplayName("T34 — a task on a DELIVERED job card cannot be deleted")
    void t34_deleteOnDeliveredJobCard_throws() {
        when(repository.findById(100L))
                .thenReturn(Optional.of(task(100L, deliveredJobCard(), "COMPLETED")));

        assertThatThrownBy(() -> service.delete(100L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("is DELIVERED");

        verify(repository, never()).delete(any(JobTask.class));
    }

    @Test
    @DisplayName("T35 — work notes and mechanic assignment are refused once delivered")
    void t35_workNotesAndAssignmentOnDeliveredJobCard_throw() {
        JobTask existing = task(100L, deliveredJobCard(), "COMPLETED");
        when(repository.findById(100L)).thenReturn(Optional.of(existing));

        JobTaskWorkNotesRequestDTO notes = new JobTaskWorkNotesRequestDTO();
        notes.setWorkNotes("Late customer call");

        assertThatThrownBy(() -> service.updateWorkNotes(100L, notes))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("is DELIVERED");
        assertThatThrownBy(() -> service.assignMechanic(100L, 7L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("is DELIVERED");

        // No mechanic repository stub on purpose: the guard has to reject the
        // assignment before the mechanic is even looked up.
        verify(repository, never()).save(any(JobTask.class));
        verify(mechanicRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("T36 — reading a DELIVERED job card's tasks still works")
    void t36_readsOnDeliveredJobCard_stillWork() {
        // The point of keeping a delivered job is to read what was done on it,
        // so the guard must stop writes only.
        when(jobCardRepository.existsById(55L)).thenReturn(true);
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(repository.findByJobCardIdOrderByIdAsc(55L, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(task(1L, deliveredJobCard(), "COMPLETED"))));
        when(repository.sumLabourCostByJobCardId(55L)).thenReturn(new BigDecimal("500.00"));

        assertThat(service.listByJobCard(55L, pageable).getTotalElements()).isEqualTo(1);
        assertThat(service.totalLabourCost(55L)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("T37 — only DELIVERED is blocked; every earlier job state still accepts work")
    void t37_nonDeliveredJobStates_allowWork() {
        for (String open : new String[]{"RECEIVED", "INSPECTION", "IN_REPAIR", "QUALITY_CHECK"}) {
            JobCard jc = jobCard(55L);
            jc.setStatus(open);
            when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jc));
            givenSaved();

            assertThat(service.create(55L, request("Replace front brake pads")).getJobCardId())
                    .as("task creation on a %s job card", open)
                    .isEqualTo(55L);
        }
    }

    // ── T38..T43: task status may only move forwards ──────────────────────

    @Test
    @DisplayName("T38 — PENDING → IN_PROGRESS and IN_PROGRESS → COMPLETED are allowed")
    void t38_forwardTransitions_allowed() {
        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "PENDING")));
        givenSaved();

        assertThat(service.updateStatus(100L, statusRequest("IN_PROGRESS")).getStatus())
                .isEqualTo("IN_PROGRESS");

        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "IN_PROGRESS")));
        assertThat(service.updateStatus(100L, statusRequest("COMPLETED")).getStatus())
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("T39 — PENDING and IN_PROGRESS may both be cancelled")
    void t39_cancelTransitions_allowed() {
        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "PENDING")));
        givenSaved();
        assertThat(service.updateStatus(100L, statusRequest("CANCELLED")).getStatus())
                .isEqualTo("CANCELLED");

        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "IN_PROGRESS")));
        assertThat(service.updateStatus(100L, statusRequest("CANCELLED")).getStatus())
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("T40 — PENDING cannot jump straight to COMPLETED")
    void t40_skippingInProgress_throws() {
        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "PENDING")));

        assertThatThrownBy(() -> service.updateStatus(100L, statusRequest("COMPLETED")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cannot change task status from PENDING to COMPLETED");

        verify(repository, never()).save(any(JobTask.class));
    }

    @Test
    @DisplayName("T41 — COMPLETED and CANCELLED are final and cannot move again")
    void t41_terminalTransitions_throw() {
        for (String terminal : new String[]{"COMPLETED", "CANCELLED"}) {
            // Staying put stays legal, so only the three genuinely different
            // states are exercised here; T42 covers the no-op case.
            for (String target : new String[]{"PENDING", "IN_PROGRESS", "COMPLETED", "CANCELLED"}) {
                if (target.equals(terminal)) {
                    continue;
                }
                when(repository.findById(100L))
                        .thenReturn(Optional.of(task(100L, jobCard(55L), terminal)));

                assertThatThrownBy(() -> service.updateStatus(100L, statusRequest(target)))
                        .as("%s → %s", terminal, target)
                        .isInstanceOf(BusinessRuleException.class)
                        .hasMessageContaining("Cannot change task status from " + terminal);
            }
        }
        verify(repository, never()).save(any(JobTask.class));
    }

    @Test
    @DisplayName("T42 — re-submitting the current status is accepted, not an error")
    void t42_sameStatus_isIdempotent() {
        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "IN_PROGRESS")));
        givenSaved();

        assertThat(service.updateStatus(100L, statusRequest("IN_PROGRESS")).getStatus())
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("T43 — an update that carries an illegal status change is refused too")
    void t43_update_cannotBypassTransitions() {
        // The general update endpoint sets status as well, so it needs the same
        // transition rule — otherwise the dedicated status endpoint's guard could
        // simply be stepped around.
        when(repository.findById(100L)).thenReturn(Optional.of(task(100L, jobCard(55L), "COMPLETED")));

        JobTaskRequestDTO req = request("Replace front brake pads");
        req.setStatus("PENDING");

        assertThatThrownBy(() -> service.update(100L, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cannot change task status from COMPLETED to PENDING");

        verify(repository, never()).save(any(JobTask.class));
    }
}