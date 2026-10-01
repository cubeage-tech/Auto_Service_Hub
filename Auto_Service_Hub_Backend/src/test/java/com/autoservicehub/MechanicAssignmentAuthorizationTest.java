package com.autoservicehub;

import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.dto.JobTaskRequestDTO;
import com.autoservicehub.dto.JobTaskResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.User;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.AuditLogRepository;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobCardStatusHistoryRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.MechanicSkillRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.DeliveryGateService;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import com.autoservicehub.service.impl.JobCardServiceImpl;
import com.autoservicehub.service.impl.JobTaskServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MechanicAssignmentAuthorizationTest {

    @Mock private JobCardRepository jobCardRepository;
    @Mock private JobTaskRepository jobTaskRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private MechanicRepository mechanicRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private MechanicSkillRepository mechanicSkillRepository;
    @Mock private JobCardStatusHistoryRepository statusHistoryRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private UserRepository userRepository;

    private JobCardServiceImpl jobCardService;
    private JobTaskServiceImpl jobTaskService;
    private Mechanic mechanicA;
    private Mechanic mechanicB;

    @BeforeEach
    void setUp() {
        MechanicAccessService accessService = new MechanicAccessService(userRepository);
        jobCardService = new JobCardServiceImpl(jobCardRepository, customerRepository, vehicleRepository,
                mechanicRepository, appointmentRepository, mechanicSkillRepository, statusHistoryRepository,
                auditLogRepository, accessService, new ServiceAdvisorAccessService(userRepository),
                mock(DeliveryGateService.class));
        jobTaskService = new JobTaskServiceImpl(jobTaskRepository, jobCardRepository, mechanicRepository,
            accessService, new ServiceAdvisorAccessService(userRepository));

        mechanicA = mechanic(1L, "Mechanic A");
        mechanicB = mechanic(2L, "Mechanic B");
        User userA = new User();
        userA.setId(101L);
        userA.setUsername("mechanic-a");
        userA.setMechanic(mechanicA);
        when(userRepository.findByUsernameIgnoreCase("mechanic-a")).thenReturn(Optional.of(userA));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "mechanic-a", "not-used", List.of(new SimpleGrantedAuthority("ROLE_MECHANIC"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mechanicCannotReadAnotherMechanicsJobCard() {
        JobCard jobCard = assignedCard(40L, mechanicB);
        when(jobCardRepository.findById(40L)).thenReturn(Optional.of(jobCard));

        assertThrows(AccessDeniedException.class, () -> jobCardService.getById(40L));
    }

    @Test
    void mechanicCannotModifyAnotherMechanicsJobCard() {
        JobCard jobCard = assignedCard(40L, mechanicB);
        // update() loads the job card with the pessimistic lock, so the stub must
        // match the production call, otherwise the test would fail for the wrong
        // reason (ResourceNotFound instead of AccessDenied).
        when(jobCardRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(jobCard));

        assertThrows(AccessDeniedException.class, () -> jobCardService.update(40L, new JobCardRequestDTO()));
        verify(jobCardRepository, never()).save(any(JobCard.class));
    }

    @Test
    void mechanicCannotModifyAnotherMechanicsTask() {
        JobCard jobCard = assignedCard(40L, mechanicB);
        JobTask task = new JobTask();
        task.setId(70L);
        task.setJobCard(jobCard);
        task.setMechanic(mechanicB);
        task.setStatus("PENDING");
        when(jobTaskRepository.findById(70L)).thenReturn(Optional.of(task));
        JobTaskRequestDTO request = new JobTaskRequestDTO();
        request.setStatus("COMPLETED");

        assertThrows(AccessDeniedException.class, () -> jobTaskService.update(70L, request));
        verify(jobTaskRepository, never()).save(any(JobTask.class));
    }

    @Test
    void mechanicCannotModifyCoworkersTaskOnSharedJobCard() {
        JobCard sharedCard = new JobCard();
        sharedCard.setId(41L);
        sharedCard.setAssignedMechanics(new LinkedHashSet<>(Set.of(mechanicA, mechanicB)));
        JobTask coworkersTask = new JobTask();
        coworkersTask.setId(71L);
        coworkersTask.setJobCard(sharedCard);
        coworkersTask.setMechanic(mechanicB);
        coworkersTask.setStatus("PENDING");
        when(jobTaskRepository.findById(71L)).thenReturn(Optional.of(coworkersTask));
        JobTaskRequestDTO request = new JobTaskRequestDTO();
        request.setStatus("COMPLETED");

        assertThrows(AccessDeniedException.class, () -> jobTaskService.update(71L, request));
        verify(jobTaskRepository, never()).save(any(JobTask.class));
    }

    @Test
    void mechanicCanCompleteOwnTaskWithTimestampAndUserAttribution() {
        JobCard jobCard = assignedCard(42L, mechanicA);
        JobTask task = new JobTask();
        task.setId(72L);
        task.setJobCard(jobCard);
        task.setMechanic(mechanicA);
        task.setStatus("IN_PROGRESS");
        when(jobTaskRepository.findById(72L)).thenReturn(Optional.of(task));
        when(jobTaskRepository.save(any(JobTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        JobTaskRequestDTO request = new JobTaskRequestDTO();
        request.setStatus("COMPLETED");

        JobTaskResponseDTO response = jobTaskService.update(72L, request);

        assertEquals("COMPLETED", response.getStatus());
        assertNotNull(response.getCompletedAt());
        assertEquals(Long.valueOf(101L), response.getCompletedByUserId());
        verify(jobTaskRepository).save(task);
    }

    private Mechanic mechanic(Long id, String name) {
        Mechanic mechanic = new Mechanic();
        mechanic.setId(id);
        mechanic.setName(name);
        mechanic.setStatus("ACTIVE");
        return mechanic;
    }

    private JobCard assignedCard(Long id, Mechanic mechanic) {
        JobCard jobCard = new JobCard();
        jobCard.setId(id);
        jobCard.setStatus("RECEIVED");
        jobCard.setMechanic(mechanic);
        jobCard.setAssignedMechanics(new LinkedHashSet<>(Set.of(mechanic)));
        return jobCard;
    }
}