package com.autoservicehub;

import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.QualityCheck;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.AuditLogRepository;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobCardStatusHistoryRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.MechanicSkillRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.QualityCheckRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import com.autoservicehub.service.impl.DeliveryGateServiceImpl;
import com.autoservicehub.service.impl.JobCardServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobCardWorkflowTest {

    @Mock private JobCardRepository jobCardRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private MechanicRepository mechanicRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private MechanicSkillRepository mechanicSkillRepository;
    @Mock private JobCardStatusHistoryRepository statusHistoryRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private JobTaskRepository jobTaskRepository;
    @Mock private QualityCheckRepository qualityCheckRepository;

    private JobCardServiceImpl service;
    /** The REAL gate, so the BR-02 enforcement inside JobCardServiceImpl is exercised. */
    private DeliveryGateServiceImpl deliveryGateService;

    @BeforeEach
    void setUp() {
        deliveryGateService = new DeliveryGateServiceImpl(jobTaskRepository, qualityCheckRepository);
        Mechanic mechanic = new Mechanic();
        mechanic.setId(1L);
        mechanic.setStatus("ACTIVE");
        User user = new User();
        user.setUsername("mechanic-a");
        user.setMechanic(mechanic);
        // Lenient: the delivery-gate tests throw before currentUser() is ever reached,
        // so this stub is unused in most of this class.
        org.mockito.Mockito.lenient()
                .when(userRepository.findByUsernameIgnoreCase("mechanic-a"))
                .thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "mechanic-a", "not-used", List.of(new SimpleGrantedAuthority("ROLE_MECHANIC"))));
        service = new JobCardServiceImpl(jobCardRepository, customerRepository, vehicleRepository,
                mechanicRepository, appointmentRepository, mechanicSkillRepository, statusHistoryRepository,
                auditLogRepository, new MechanicAccessService(userRepository), new ServiceAdvisorAccessService(userRepository),
                deliveryGateService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsSkippingJobCardWorkflowStages() {
        Mechanic mechanic = new Mechanic();
        mechanic.setId(1L);
        JobCard jobCard = new JobCard();
        jobCard.setId(55L);
        jobCard.setStatus("RECEIVED");
        jobCard.setMechanic(mechanic);
        jobCard.setAssignedMechanics(Set.of(mechanic));
        when(jobCardRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(jobCard));

        JobCardRequestDTO request = new JobCardRequestDTO();
        request.setStatus("DELIVERED");

        assertThrows(BusinessRuleException.class, () -> service.update(55L, request));
        verify(jobCardRepository, never()).save(org.mockito.ArgumentMatchers.any(JobCard.class));
    }

    @Test
    void acceptsSrsRepairStartedAliasWithoutRewritingItsPersistedLabel() {
        Mechanic mechanic = new Mechanic();
        mechanic.setId(1L);
        JobCard jobCard = new JobCard();
        jobCard.setId(56L);
        jobCard.setStatus("INSPECTION");
        jobCard.setMechanic(mechanic);
        jobCard.setAssignedMechanics(Set.of(mechanic));
        when(jobCardRepository.findByIdForUpdate(56L)).thenReturn(Optional.of(jobCard));
        when(jobCardRepository.save(org.mockito.ArgumentMatchers.any(JobCard.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        JobCardRequestDTO request = new JobCardRequestDTO();
        request.setStatus("REPAIR_STARTED");

        var response = service.update(56L, request);

        org.junit.jupiter.api.Assertions.assertEquals("REPAIR_STARTED", response.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(response.getStartedDate());
    }

    // ── SRS 12 BR-02 delivery gate, enforced inside JobCardServiceImpl ─────────

    /** A job card at QUALITY_CHECK, i.e. one legal step away from DELIVERED. */
    private JobCard atQualityCheck(long id) {
        Mechanic mechanic = new Mechanic();
        mechanic.setId(1L);
        // ACTIVE matters: the management update path runs validateAssignments before
        // changeStatus, and that rejects an inactive mechanic. Without this the test
        // would pass on the wrong exception and never reach the gate.
        mechanic.setStatus("ACTIVE");
        JobCard card = new JobCard();
        card.setId(id);
        card.setJobCardNumber("JC-TEST-" + id);
        card.setStatus("QUALITY_CHECK");
        card.setMechanic(mechanic);
        card.setAssignedMechanics(new java.util.LinkedHashSet<>(Set.of(mechanic)));
        when(jobCardRepository.findByIdForUpdate(id)).thenReturn(Optional.of(card));
        // Lenient: blocked-delivery tests never reach save(), so this stub goes unused.
        org.mockito.Mockito.lenient()
                .when(jobCardRepository.save(org.mockito.ArgumentMatchers.any(JobCard.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return card;
    }

    private JobCardRequestDTO deliver() {
        JobCardRequestDTO request = new JobCardRequestDTO();
        request.setStatus("DELIVERED");
        return request;
    }

    private QualityCheck qc(String result, int attemptNo) {
        QualityCheck check = new QualityCheck();
        check.setId((long) attemptNo);
        check.setResult(result);
        check.setAttemptNo(attemptNo);
        check.setCheckedAt(java.time.LocalDateTime.now());
        return check;
    }

    @Test
    @DisplayName("GATE-a - DELIVERED is rejected when repair tasks are still incomplete")
    void deliveryBlockedWhenTasksIncomplete() {
        atQualityCheck(61L);
        when(jobTaskRepository.countIncompleteByJobCardId(61L)).thenReturn(2L);
        when(jobTaskRepository.findDistinctStatusesByJobCardId(61L))
                .thenReturn(List.of("IN_PROGRESS", "PENDING"));
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(61L))
                .thenReturn(Optional.of(qc("PASS", 1)));

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.update(61L, deliver()));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("COMPLETED"), ex.getMessage());
        verify(jobCardRepository, never()).save(org.mockito.ArgumentMatchers.any(JobCard.class));
    }

    @Test
    @DisplayName("GATE-b - DELIVERED is rejected when no quality check exists at all")
    void deliveryBlockedWhenNoQc() {
        atQualityCheck(62L);
        when(jobTaskRepository.countIncompleteByJobCardId(62L)).thenReturn(0L);
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(62L))
                .thenReturn(Optional.empty());

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.update(62L, deliver()));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("no quality check exists"), ex.getMessage());
        verify(jobCardRepository, never()).save(org.mockito.ArgumentMatchers.any(JobCard.class));
    }

    @Test
    @DisplayName("GATE-c - a later FAIL overrides an earlier PASS (latest attempt governs)")
    void deliveryBlockedWhenLatestQcFailsDespiteEarlierPass() {
        atQualityCheck(63L);
        when(jobTaskRepository.countIncompleteByJobCardId(63L)).thenReturn(0L);
        // The repository returns the newest attempt; attempt 2 is FAIL.
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(63L))
                .thenReturn(Optional.of(qc("FAIL", 2)));

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.update(63L, deliver()));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("FAIL"), ex.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("attempt 2"), ex.getMessage());
        verify(jobCardRepository, never()).save(org.mockito.ArgumentMatchers.any(JobCard.class));
    }

    @Test
    @DisplayName("GATE-d - DELIVERED succeeds when all tasks are terminal and latest QC passes")
    void deliveryAllowedWhenAllConditionsSatisfied() {
        JobCard card = atQualityCheck(64L);
        when(jobTaskRepository.countIncompleteByJobCardId(64L)).thenReturn(0L);
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(64L))
                .thenReturn(Optional.of(qc("PASS", 2)));

        var response = service.update(64L, deliver());

        org.junit.jupiter.api.Assertions.assertEquals("DELIVERED", response.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("DELIVERED", card.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(card.getCompletedDate());
    }

    @Test
    @DisplayName("GATE-h - a job card with ZERO tasks and a passing QC may be delivered")
    void deliveryAllowedWithZeroTasksAndPassingQc() {
        // No tasks at all: the task rule produces no reason, so the passing QC alone
        // satisfies the gate.
        JobCard card = atQualityCheck(68L);
        when(jobTaskRepository.countIncompleteByJobCardId(68L)).thenReturn(0L);
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(68L))
                .thenReturn(Optional.of(qc("PASS", 1)));

        var response = service.update(68L, deliver());

        org.junit.jupiter.api.Assertions.assertEquals("DELIVERED", response.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("DELIVERED", card.getStatus());
        // Both gate inputs must actually be consulted, so this test also fails if the
        // task rule or the QC rule were removed outright.
        verify(jobTaskRepository).countIncompleteByJobCardId(68L);
        verify(qualityCheckRepository).findFirstByJobCardIdOrderByAttemptNoDesc(68L);
    }

    @Test
    @DisplayName("GATE-i - legacy DONE tasks count as terminal, so delivery is allowed")
    void deliveryAllowedWithOnlyLegacyDoneTasks() {
        // Tasks all carrying the legacy 'DONE' value must not block delivery, which
        // matches how countPendingByMechanicId already treats 'DONE'.
        JobCard card = atQualityCheck(69L);
        when(jobTaskRepository.countIncompleteByJobCardId(69L)).thenReturn(0L);
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(69L))
                .thenReturn(Optional.of(qc("PASS", 1)));

        var response = service.update(69L, deliver());

        org.junit.jupiter.api.Assertions.assertEquals("DELIVERED", response.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("DELIVERED", card.getStatus());
    }

    @Test
    @DisplayName("GATE-e - an invalid transition still reports the transition, not a QC problem")
    void invalidTransitionStillWinsOverGate() {
        // RECEIVED -> DELIVERED skips two stages, so the transition check must fire
        // first and the gate must never be consulted.
        Mechanic mechanic = new Mechanic();
        mechanic.setId(1L);
        JobCard card = new JobCard();
        card.setId(65L);
        card.setStatus("RECEIVED");
        card.setMechanic(mechanic);
        card.setAssignedMechanics(Set.of(mechanic));
        when(jobCardRepository.findByIdForUpdate(65L)).thenReturn(Optional.of(card));

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.update(65L, deliver()));

        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("Invalid job status transition"), ex.getMessage());
        // Proves the gate is not consulted when the transition itself is invalid.
        verify(jobTaskRepository, never()).countIncompleteByJobCardId(any());
        verify(qualityCheckRepository, never())
                .findFirstByJobCardIdOrderByAttemptNoDesc(any());
    }

    @Test
    @DisplayName("GATE-f - a MECHANIC cannot bypass the gate on the mechanic update path")
    void mechanicPathCannotBypassGate() {
        // setUp() authenticates as mechanic-a with ROLE_MECHANIC, which takes the
        // early-return mechanic branch in update() before changeStatus.
        JobCard card = atQualityCheck(66L);
        when(jobTaskRepository.countIncompleteByJobCardId(66L)).thenReturn(1L);
        when(jobTaskRepository.findDistinctStatusesByJobCardId(66L)).thenReturn(List.of("PENDING"));
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(66L))
                .thenReturn(Optional.of(qc("PASS", 1)));

        assertThrows(BusinessRuleException.class, () -> service.update(66L, deliver()));

        org.junit.jupiter.api.Assertions.assertEquals("QUALITY_CHECK", card.getStatus());
        verify(jobCardRepository, never()).save(org.mockito.ArgumentMatchers.any(JobCard.class));
    }

    @Test
    @DisplayName("GATE-g - the management update path is guarded by the same gate")
    void managementPathIsAlsoGuarded() {
        // Same gate, driven through the non-mechanic (management) branch of update().
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "manager-a", "not-used", List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
        User manager = new User();
        manager.setUsername("manager-a");
        org.mockito.Mockito.lenient()
                .when(userRepository.findByUsernameIgnoreCase("manager-a")).thenReturn(Optional.of(manager));

        JobCard card = atQualityCheck(67L);

        // The management branch runs mapToEntity (which resolves customer/vehicle/
        // mechanic) and validateAssignments BEFORE changeStatus. All three must be
        // supplied and unchanged, otherwise that pre-gate validation is what throws
        // and the gate is never reached. This keeps the assertion focused on the gate.
        Customer customer = new Customer();
        customer.setId(1L);
        com.autoservicehub.entity.Vehicle vehicle = new com.autoservicehub.entity.Vehicle();
        vehicle.setId(2L);
        vehicle.setCustomer(customer);
        card.setCustomer(customer);
        card.setVehicle(vehicle);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(vehicleRepository.findById(2L)).thenReturn(Optional.of(vehicle));
        when(mechanicRepository.findById(1L)).thenReturn(Optional.of(card.getMechanic()));

        JobCardRequestDTO request = new JobCardRequestDTO();
        request.setStatus("DELIVERED");
        request.setMechanicId(1L);
        request.setCustomerId(1L);
        request.setVehicleId(2L);

        // Not lenient: these stubs must all be consumed for this test to be meaningful,
        // because reaching the gate requires the pre-gate mapToEntity/assignment
        // validation to have succeeded first.
        when(jobTaskRepository.countIncompleteByJobCardId(67L)).thenReturn(0L);
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(67L))
                .thenReturn(Optional.of(qc("FAIL", 1)));

        // Assert the specific gate failure, not merely "some BusinessRuleException":
        // the latest attempt is a FAIL and the message must name it.
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.update(67L, request));
        assertTrue(ex.getMessage().contains("FAIL"),
                "expected the latest-QC FAIL reason, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("attempt 1"),
                "expected the governing attempt number, got: " + ex.getMessage());

        org.junit.jupiter.api.Assertions.assertEquals("QUALITY_CHECK", card.getStatus());
        verify(jobCardRepository, never()).save(org.mockito.ArgumentMatchers.any(JobCard.class));
    }

    // ── Concurrency wiring: the delivery path must take the same row lock as QC ──

    @Test
    @DisplayName("LOCK-1 - update() loads the job card with the QC pessimistic lock")
    void deliveryPathAcquiresTheSameLockAsQcRecording() {
        atQualityCheck(70L);
        when(jobTaskRepository.countIncompleteByJobCardId(70L)).thenReturn(0L);
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(70L))
                .thenReturn(Optional.of(qc("PASS", 1)));

        service.update(70L, deliver());

        // The lock must be the SAME method QC recording uses. If this regressed to a
        // plain findById, delivery would no longer serialise against a concurrent QC
        // write and the BR-02 race would reopen.
        verify(jobCardRepository).findByIdForUpdate(70L);
        verify(jobCardRepository, never())
                .findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("LOCK-2 - the lock is taken BEFORE the gate reads, even when delivery is blocked")
    void lockIsTakenBeforeTheGateEvaluates() {
        atQualityCheck(71L);
        when(jobTaskRepository.countIncompleteByJobCardId(71L)).thenReturn(1L);
        when(jobTaskRepository.findDistinctStatusesByJobCardId(71L)).thenReturn(List.of("PENDING"));
        when(qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(71L))
                .thenReturn(Optional.of(qc("PASS", 1)));

        assertThrows(BusinessRuleException.class, () -> service.update(71L, deliver()));

        // Ordering matters: acquiring the lock after the gate would still race. This
        // asserts the lock is in place even on the rejected path.
        verify(jobCardRepository).findByIdForUpdate(71L);
    }
}