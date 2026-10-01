package com.autoservicehub;

import com.autoservicehub.dto.QualityCheckRequestDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.QualityCheck;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.QualityCheckRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import com.autoservicehub.service.impl.DeliveryGateServiceImpl;
import com.autoservicehub.service.impl.QualityCheckServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SRS 4.5 FR-JOB-6 and SRS 12 BR-02.
 *
 * <p>Covers QC recording (PASS/FAIL), append-only history with latest-result
 * semantics, separation of duties (the repairing mechanic cannot sign off), role
 * authorization, and the delivery-gate evaluator.
 */
@ExtendWith(MockitoExtension.class)
class QualityCheckAndDeliveryGateTest {

    @Mock private QualityCheckRepository qualityCheckRepository;
    @Mock private JobCardRepository        jobCardRepository;
    @Mock private JobTaskRepository         jobTaskRepository;
    @Mock private UserRepository            userRepository;

    private QualityCheckServiceImpl qcService;
    private DeliveryGateServiceImpl gate;
    private MechanicAccessService   accessService;

    private JobCard  jobCard;
    private Mechanic repairingMechanic;
    private User     manager;

    @BeforeEach
    void setUp() {
        repairingMechanic = new Mechanic();
        repairingMechanic.setId(7L);
        repairingMechanic.setName("Repairing Mechanic");
        repairingMechanic.setStatus("ACTIVE");

        manager = new User();
        manager.setId(100L);
        manager.setUsername("manager-a");
        manager.setFullName("Manager A");

        jobCard = new JobCard();
        jobCard.setId(55L);
        jobCard.setJobCardNumber("JC-20260101120000");
        jobCard.setStatus("QUALITY_CHECK");
        jobCard.setAssignedMechanics(new LinkedHashSet<>(Set.of(repairingMechanic)));
        jobCard.setRequiredSkills(new LinkedHashSet<>());

        accessService = new MechanicAccessService(userRepository);
        qcService = new QualityCheckServiceImpl(qualityCheckRepository, jobCardRepository,
                accessService, new ServiceAdvisorAccessService(userRepository));
        gate = new DeliveryGateServiceImpl(jobTaskRepository, qualityCheckRepository);

        authenticate("manager-a", "ROLE_MANAGER");
    }

    /**
     * Stubs the collaborators the QualityCheckService path needs. Kept out of setUp
     * because the delivery-gate tests exercise a different collaborator entirely.
     * The stubs are lenient because which of them are consumed depends on the path
     * under test: a rejected result never reaches save(), and the authorization
     * tests re-stub the user lookup with their own username.
     */
    private void stubQualityCheckDependencies() {
        lenient().when(userRepository.findByUsernameIgnoreCase("manager-a")).thenReturn(Optional.of(manager));
        // record() takes the pessimistic job-card lock; the read paths use findById.
        lenient().when(jobCardRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(jobCard));
        lenient().when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard));
        lenient().when(qualityCheckRepository.save(any(QualityCheck.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "not-used",
                        List.of(new SimpleGrantedAuthority(role))));
    }

    private QualityCheckRequestDTO request(String result) {
        QualityCheckRequestDTO dto = new QualityCheckRequestDTO();
        dto.setResult(result);
        dto.setRemarks("inspection notes");
        return dto;
    }

    private QualityCheck stored(int attemptNo, String result) {
        QualityCheck qc = new QualityCheck();
        qc.setId((long) attemptNo);
        qc.setJobCard(jobCard);
        qc.setResult(result);
        qc.setAttemptNo(attemptNo);
        qc.setCheckedAt(LocalDateTime.now());
        qc.setCheckedBy(manager);
        return qc;
    }
    // ── QC recording ──────────────────────────────────────────────────────

    @Test
    @DisplayName("QC1 - manager records a PASS as attempt 1")
    void managerRecordsPass() {
        stubQualityCheckDependencies();
        when(qualityCheckRepository.findMaxAttemptNo(55L)).thenReturn(0);
        var response = qcService.record(55L, request("PASS"));
        assertEquals("PASS", response.getResult());
        assertEquals(1, response.getAttemptNo());
        assertEquals(100L, response.getCheckedByUserId());
        assertNotNull(response.getCheckedAt());
    }

    @Test
    @DisplayName("QC2 - a second check is appended as a new attempt, not an overwrite")
    void managerRecordsFailAsSecondAttempt() {
        stubQualityCheckDependencies();
        when(qualityCheckRepository.findMaxAttemptNo(55L)).thenReturn(1);
        var response = qcService.record(55L, request("FAIL"));
        assertEquals("FAIL", response.getResult());
        assertEquals(2, response.getAttemptNo());
        verify(qualityCheckRepository).save(any(QualityCheck.class));
    }

    @Test
    @DisplayName("QC3 - result is normalised and consecutive attempts get consecutive numbers")
    void resultIsCaseInsensitiveAndAttemptsIncrement() {
        stubQualityCheckDependencies();
        // Two consecutive calls must read an increasing max, so the attempt numbers
        // are 1 then 2. Stubbing a constant 0 here would hide the real behaviour.
        when(qualityCheckRepository.findMaxAttemptNo(55L)).thenReturn(0, 1);

        var first = qcService.record(55L, request("pass"));
        var second = qcService.record(55L, request(" fail "));

        assertEquals("PASS", first.getResult());
        assertEquals(1, first.getAttemptNo());
        assertEquals("FAIL", second.getResult());
        assertEquals(2, second.getAttemptNo());
    }

    @Test
    @DisplayName("QC4 - an unsupported result is rejected as a business rule violation")
    void unsupportedResultRejected() {
        stubQualityCheckDependencies();
        // The result is validated before an attempt number is allocated, so this
        // stub is deliberately absent: findMaxAttemptNo must never be reached.
        assertThrows(BusinessRuleException.class, () -> qcService.record(55L, request("MAYBE")));
        verify(qualityCheckRepository, never()).save(any());
        verify(qualityCheckRepository, never()).findMaxAttemptNo(any());
    }

    @Test
    @DisplayName("QC5 - the checker is derived from the security context, not the payload")
    void checkerComesFromSecurityContext() {
        stubQualityCheckDependencies();
        when(qualityCheckRepository.findMaxAttemptNo(55L)).thenReturn(0);
        var response = qcService.record(55L, request("PASS"));
        // The DTO has no user field at all; the recorded user is the authenticated one.
        assertEquals(100L, response.getCheckedByUserId());
        assertEquals("Manager A", response.getCheckedByName());
    }

    // ── Authorization ──────────────────────────────────────────────────────

    @Test
    @DisplayName("QC6 - a plain MECHANIC cannot record a quality check")
    void mechanicCannotRecordQc() {
        User mechanicUser = new User();
        mechanicUser.setId(7L);
        mechanicUser.setUsername("mechanic-a");
        stubQualityCheckDependencies();
        when(userRepository.findByUsernameIgnoreCase("mechanic-a")).thenReturn(Optional.of(mechanicUser));
        authenticate("mechanic-a", "ROLE_MECHANIC");

        assertThrows(AccessDeniedException.class, () -> qcService.record(55L, request("PASS")));
        verify(qualityCheckRepository, never()).save(any());
    }

    @Test
    @DisplayName("QC7 - a supervisor who is also the assigned mechanic cannot sign off their own job")
    void repairingMechanicCannotSignOffOwnJob() {
        User supervisor = new User();
        supervisor.setId(7L);
        supervisor.setUsername("mechanic-a");
        supervisor.setMechanic(repairingMechanic);
        stubQualityCheckDependencies();
        when(userRepository.findByUsernameIgnoreCase("mechanic-a")).thenReturn(Optional.of(supervisor));
        authenticate("mechanic-a", "ROLE_MANAGER");

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> qcService.record(55L, request("PASS")));
        assertTrue(ex.getMessage().contains("cannot record its quality check"), ex.getMessage());
        verify(qualityCheckRepository, never()).save(any());
    }

    @Test
    @DisplayName("QC8 - history is returned newest attempt first")
    void historyIsNewestFirst() {
        stubQualityCheckDependencies();
        when(qualityCheckRepository.findByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(List.of(stored(2, "FAIL"), stored(1, "PASS")));
        var history = qcService.listForJobCard(55L);
        assertEquals(2, history.size());
        assertEquals(2, history.get(0).getAttemptNo());
        assertEquals("FAIL", history.get(0).getResult());
    }
    // ── Delivery gate (evaluator only; NOT yet wired into status changes) ──

    @Test
    @DisplayName("DG1 - incomplete tasks block delivery")
    void incompleteTasksBlockDelivery() {
        when(jobTaskRepository.countIncompleteByJobCardId(55L)).thenReturn(2L);
        when(jobTaskRepository.findDistinctStatusesByJobCardId(55L)).thenReturn(List.of("COMPLETED", "IN_PROGRESS"));
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(Optional.of(stored(1, "PASS")));

        var reasons = gate.blockingReasons(jobCard);

        assertFalse(reasons.isEmpty());
        assertTrue(reasons.get(0).contains("COMPLETED"), reasons.get(0));
        assertThrows(BusinessRuleException.class, () -> gate.assertCanDeliver(jobCard));
    }

    @Test
    @DisplayName("DG2 - a missing quality check blocks delivery")
    void missingQcBlocksDelivery() {
        when(jobTaskRepository.countIncompleteByJobCardId(55L)).thenReturn(0L);
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(Optional.empty());

        var reasons = gate.blockingReasons(jobCard);

        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("no quality check exists"), reasons.get(0));
        assertFalse(gate.isDeliverable(jobCard));
    }

    @Test
    @DisplayName("DG3 - the LATEST QC governs: a stale PASS cannot satisfy the gate after a FAIL")
    void latestQcGoverns() {
        when(jobTaskRepository.countIncompleteByJobCardId(55L)).thenReturn(0L);
        // Newest attempt is a FAIL even though an older PASS exists.
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(Optional.of(stored(2, "FAIL")));

        var reasons = gate.blockingReasons(jobCard);

        assertFalse(gate.isDeliverable(jobCard));
        assertTrue(reasons.get(0).contains("FAIL"), reasons.get(0));
        assertTrue(reasons.get(0).contains("attempt 2"), reasons.get(0));
    }

    @Test
    @DisplayName("DG4 - delivery allowed only when tasks complete AND latest QC passes")
    void deliverableWhenAllConditionsMet() {
        when(jobTaskRepository.countIncompleteByJobCardId(55L)).thenReturn(0L);
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(Optional.of(stored(2, "PASS")));

        assertTrue(gate.isDeliverable(jobCard));
        assertTrue(gate.blockingReasons(jobCard).isEmpty());
        assertDoesNotThrow(() -> gate.assertCanDeliver(jobCard));
    }

    @Test
    @DisplayName("DG5 - both failure reasons are reported together")
    void bothFailuresReported() {
        when(jobTaskRepository.countIncompleteByJobCardId(55L)).thenReturn(1L);
        when(jobTaskRepository.findDistinctStatusesByJobCardId(55L)).thenReturn(List.of("PENDING"));
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(Optional.empty());

        assertEquals(2, gate.blockingReasons(jobCard).size());
    }

    // ── Task status semantics (COMPLETED and legacy DONE are both terminal) ──

    @Test
    @DisplayName("DG6 - a job card with zero tasks is not blocked by the task rule")
    void zeroTasksIsNotIncomplete() {
        when(jobTaskRepository.countIncompleteByJobCardId(55L)).thenReturn(0L);
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(Optional.of(stored(1, "PASS")));

        // The repository query returns 0, so no task reason is produced and the
        // card is deliverable on a passing QC alone.
        assertTrue(gate.blockingReasons(jobCard).isEmpty());
        assertTrue(gate.isDeliverable(jobCard));
    }

    @Test
    @DisplayName("DG7 - all tasks COMPLETED plus a passing QC is deliverable")
    void allCompletedAndPassIsDeliverable() {
        when(jobTaskRepository.countIncompleteByJobCardId(55L)).thenReturn(0L);
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(Optional.of(stored(1, "PASS")));

        assertTrue(gate.isDeliverable(jobCard));
    }

    @Test
    @DisplayName("DG8 - the message lists outstanding statuses deterministically")
    void outstandingStatusesAreListedInOrder() {
        // The repository query orders by status; the gate must not re-order or dedupe.
        when(jobTaskRepository.countIncompleteByJobCardId(55L)).thenReturn(3L);
        when(jobTaskRepository.findDistinctStatusesByJobCardId(55L))
                .thenReturn(List.of("IN_PROGRESS", "PENDING"));
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(Optional.of(stored(1, "PASS")));

        String reason = gate.blockingReasons(jobCard).get(0);

        assertTrue(reason.contains("3 task(s)"), reason);
        assertTrue(reason.indexOf("IN_PROGRESS") < reason.indexOf("PENDING"),
                "statuses must appear in the order the repository returned: " + reason);
    }

    // ── Read visibility: latest(), 404 and advisor scoping ────────────────

    @Test
    @DisplayName("QC9 - latest() returns a DTO, not an entity, and maps the fields")
    void latestReturnsDto() {
        stubQualityCheckDependencies();
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(Optional.of(stored(2, "FAIL")));

        var latest = qcService.latest(55L);

        assertNotNull(latest);
        assertEquals("FAIL", latest.getResult());
        assertEquals(2, latest.getAttemptNo());
        assertEquals(55L, latest.getJobCardId());
        // The DTO is detached, so these are plain values with no lazy association to
        // touch once the transaction has closed.
        assertEquals(100L, latest.getCheckedByUserId());
    }

    @Test
    @DisplayName("QC10 - latest() returns null when no check has been recorded")
    void latestReturnsNullWhenNoneRecorded() {
        stubQualityCheckDependencies();
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertNull(qcService.latest(55L));
    }

    @Test
    @DisplayName("QC11 - the ASSIGNED mechanic may read QC for their own job card")
    void assignedMechanicMayReadQc() {
        // The fixture assigns mechanic id 7 to job card 55.
        User assignedUser = new User();
        assignedUser.setId(7L);
        assignedUser.setUsername("mechanic-a");
        assignedUser.setMechanic(repairingMechanic);
        when(userRepository.findByUsernameIgnoreCase("mechanic-a")).thenReturn(Optional.of(assignedUser));
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard));
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(55L))
                .thenReturn(Optional.of(stored(1, "PASS")));
        authenticate("mechanic-a", "ROLE_MECHANIC");

        assertDoesNotThrow(() -> qcService.latest(55L));
    }

    @Test
    @DisplayName("QC12 - a mechanic assigned to a DIFFERENT job is refused by the scope check")
    void nonAssignedMechanicIsRefused() {
        Mechanic stranger = new Mechanic();
        stranger.setId(99L);
        stranger.setName("Someone Else");
        stranger.setStatus("ACTIVE");

        User strangerUser = new User();
        strangerUser.setId(99L);
        strangerUser.setUsername("mechanic-b");
        strangerUser.setMechanic(stranger);
        when(userRepository.findByUsernameIgnoreCase("mechanic-b")).thenReturn(Optional.of(strangerUser));
        when(jobCardRepository.findById(55L)).thenReturn(Optional.of(jobCard));
        authenticate("mechanic-b", "ROLE_MECHANIC");

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> qcService.latest(55L));
        assertTrue(ex.getMessage().contains("assigned job cards"), ex.getMessage());
        // The QC row must never be read for a card the caller cannot see.
        verify(qualityCheckRepository, never())
                .findFirstByJobCardIdOrderByAttemptNoDesc(any());
    }

    @Test
    @DisplayName("QC13 - an unknown job card yields 404, not a null result")
    void unknownJobCardYieldsNotFound() {
        lenient().when(userRepository.findByUsernameIgnoreCase("manager-a")).thenReturn(Optional.of(manager));
        when(jobCardRepository.findById(999L)).thenReturn(Optional.empty());
        when(jobCardRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> qcService.record(999L, request("PASS")));
        assertThrows(ResourceNotFoundException.class, () -> qcService.latest(999L));
        assertThrows(ResourceNotFoundException.class, () -> qcService.listForJobCard(999L));
    }
}
