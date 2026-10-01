package com.autoservicehub;

import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.Attendance;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import org.springframework.security.access.AccessDeniedException;
import com.autoservicehub.repository.AttendanceRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.impl.AttendanceServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MechanicAttendanceTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private MechanicRepository mechanicRepository;
    @Mock private UserRepository userRepository;

    private AttendanceServiceImpl attendanceService;

    @BeforeEach
    void setUp() {
        Mechanic mechanic = new Mechanic();
        mechanic.setId(1L);
        mechanic.setStatus("ACTIVE");
        User user = new User();
        user.setUsername("mechanic-a");
        user.setMechanic(mechanic);
        when(userRepository.findByUsernameIgnoreCase("mechanic-a")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "mechanic-a", "not-used", List.of(new SimpleGrantedAuthority("ROLE_MECHANIC"))));
        attendanceService = new AttendanceServiceImpl(attendanceRepository, mechanicRepository,
                new MechanicAccessService(userRepository));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void preventsDuplicateOpenCheckIn() {
        Mechanic mechanic = new Mechanic();
        mechanic.setId(1L);
        mechanic.setStatus("ACTIVE");
        when(mechanicRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(mechanic));
        when(attendanceRepository.existsByMechanicIdAndCheckOutIsNull(1L)).thenReturn(true);

        assertThrows(BusinessRuleException.class, () -> attendanceService.checkIn(1L));
    }

    @Test
    void mechanicCannotCheckInForAnotherMechanic() {
        Mechanic anotherMechanic = new Mechanic();
        anotherMechanic.setId(2L);
        anotherMechanic.setStatus("ACTIVE");
        when(mechanicRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(anotherMechanic));

        assertThrows(AccessDeniedException.class, () -> attendanceService.checkIn(2L));
    }

    @Test
    void checksOutTheAuthenticatedMechanicsOpenAttendance() {
        Mechanic mechanic = new Mechanic();
        mechanic.setId(1L);
        mechanic.setStatus("ACTIVE");
        Attendance openAttendance = new Attendance();
        openAttendance.setMechanic(mechanic);
        openAttendance.setCheckIn(LocalDateTime.now().minusHours(2));
        when(mechanicRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(mechanic));
        when(attendanceRepository.findFirstByMechanicIdAndCheckOutIsNullOrderByCheckInDesc(1L)).thenReturn(openAttendance);
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = attendanceService.checkOut(1L);

        assertNotNull(response.getCheckOut());
        verify(attendanceRepository).save(openAttendance);
    }
}