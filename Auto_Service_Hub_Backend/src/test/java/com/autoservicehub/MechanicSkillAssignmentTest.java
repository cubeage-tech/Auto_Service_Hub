package com.autoservicehub;

import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.Role;
import com.autoservicehub.entity.User;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.AuditLogRepository;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobCardStatusHistoryRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.MechanicSkillRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import com.autoservicehub.service.impl.JobCardServiceImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MechanicSkillAssignmentTest {

    @Mock
    private JobCardRepository jobCardRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private MechanicRepository mechanicRepository;

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private MechanicSkillRepository mechanicSkillRepository;

    @Mock
    private JobCardStatusHistoryRepository statusHistoryRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private UserRepository userRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAssignmentWhenRequiredSkillIsMissing() {

        JobCardServiceImpl service = new JobCardServiceImpl(
                jobCardRepository,
                customerRepository,
                vehicleRepository,
                mechanicRepository,
                appointmentRepository,
                mechanicSkillRepository,
                statusHistoryRepository,
                auditLogRepository,
                new MechanicAccessService(userRepository),
                new ServiceAdvisorAccessService(userRepository),
                org.mockito.Mockito.mock(com.autoservicehub.service.DeliveryGateService.class)
        );

        ReflectionTestUtils.setField(
                service,
                "skillEnforcementEnabled",
                true
        );

        User manager = new User();
        manager.setUsername("manager");

        Role managerRole = new Role();
        managerRole.setName("MANAGER");
        manager.setRole(managerRole);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "manager",
                        "not-used",
                        List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
                )
        );

        Customer customer = new Customer();
        customer.setId(1L);

        Vehicle vehicle = new Vehicle();
        vehicle.setId(2L);
        vehicle.setCustomer(customer);

        Mechanic mechanic = new Mechanic();
        mechanic.setId(9L);
        mechanic.setName("No Engine Skill");
        mechanic.setStatus("ACTIVE");

        when(customerRepository.findById(1L))
                .thenReturn(Optional.of(customer));

        when(vehicleRepository.findById(2L))
                .thenReturn(Optional.of(vehicle));

        when(mechanicRepository.findById(9L))
                .thenReturn(Optional.of(mechanic));

        when(mechanicSkillRepository.findByMechanicIdOrderBySkillNameAsc(9L))
                .thenReturn(List.of());

        JobCardRequestDTO request = new JobCardRequestDTO();
        request.setCustomerId(1L);
        request.setVehicleId(2L);
        request.setMechanicId(9L);
        request.setServiceType("Engine repair");
        request.setRequiredSkills(Set.of("Engine"));

        assertThrows(
                BusinessRuleException.class,
                () -> service.create(request)
        );

        verify(jobCardRepository, never()).save(any());
    }
}