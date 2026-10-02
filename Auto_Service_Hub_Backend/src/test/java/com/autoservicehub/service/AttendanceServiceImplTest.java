package com.autoservicehub.service;

import com.autoservicehub.dto.AttendanceResponseDTO;
import com.autoservicehub.entity.Attendance;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.AttendanceRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.service.impl.AttendanceServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * Unit tests for {@link AttendanceServiceImpl} (FR-MECH-2).
 *
 * <p>Pure Mockito — no Spring context. What is pinned is the three rules that stop
 * attendance recording something that did not happen: one check-in per day, a
 * check-out only against a real check-in, and both times stamped server-side
 * rather than accepted from the request.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceImplTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    @Mock AttendanceRepository attendanceRepository;
    @Mock MechanicRepository    mechanicRepository;

    @InjectMocks AttendanceServiceImpl service;

    private Attendance attendance(Long id, LocalDate date, LocalDateTime in, LocalDateTime out) {
        Mechanic mechanic = new Mechanic();
        mechanic.setId(7L);
        mechanic.setName("Anil");
        Attendance attendance = new Attendance();
        attendance.setId(id);
        attendance.setMechanic(mechanic);
        attendance.setAttendanceDate(date);
        attendance.setCheckIn(in);
        attendance.setCheckOut(out);
        return attendance;
    }

    @Test
    @DisplayName("AT1 a check-in creates the row and stamps the time server-side")
    void checkInCreatesRow() {
        when(mechanicRepository.existsById(7L)).thenReturn(true);
        when(mechanicRepository.getReferenceById(7L)).thenReturn(new Mechanic());
        when(attendanceRepository.findByMechanicIdAndAttendanceDate(7L, DAY)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> {
            Attendance saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        AttendanceResponseDTO response = service.checkIn(7L, DAY);

        assertThat(response.getAttendanceDate()).isEqualTo(DAY);
        // The request carries no time, so a non-null checkIn proves it was stamped here.
        assertThat(response.getCheckIn()).isNotNull();
        assertThat(response.getCheckOut()).isNull();
        assertThat(response.isOpen()).isTrue();
        verify(attendanceRepository).save(any(Attendance.class));
    }

    @Test
    @DisplayName("AT2 a check-in with no date defaults to today")
    void checkInDefaultsToToday() {
        when(mechanicRepository.existsById(7L)).thenReturn(true);
        when(mechanicRepository.getReferenceById(7L)).thenReturn(new Mechanic());
        when(attendanceRepository.findByMechanicIdAndAttendanceDate(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> {
            Attendance saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        assertThat(service.checkIn(7L, null).getAttendanceDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("AT3 a second check-in on the same mechanic and day is refused")
    void duplicateCheckInIsRefused() {
        when(mechanicRepository.existsById(7L)).thenReturn(true);
        when(attendanceRepository.findByMechanicIdAndAttendanceDate(7L, DAY))
                .thenReturn(Optional.of(attendance(1L, DAY, LocalDateTime.now(), null)));

        assertThatThrownBy(() -> service.checkIn(7L, DAY))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already checked in");

        verify(attendanceRepository, never()).save(any(Attendance.class));
    }
    @Test
    @DisplayName("AT4 a check-out stamps the time against the existing row")
    void checkOutStampsTime() {
        when(mechanicRepository.existsById(7L)).thenReturn(true);
        Attendance open = attendance(1L, DAY, LocalDateTime.now().minusHours(3), null);
        when(attendanceRepository.findByMechanicIdAndAttendanceDate(7L, DAY)).thenReturn(Optional.of(open));
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        AttendanceResponseDTO response = service.checkOut(7L, DAY);

        assertThat(response.getCheckOut()).isNotNull();
        assertThat(response.isOpen()).isFalse();
    }

    @Test
    @DisplayName("AT5 a check-out with no check-in is refused")
    void checkOutWithoutCheckInIsRefused() {
        when(mechanicRepository.existsById(7L)).thenReturn(true);
        when(attendanceRepository.findByMechanicIdAndAttendanceDate(7L, DAY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkOut(7L, DAY))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no check-in");

        verify(attendanceRepository, never()).save(any(Attendance.class));
    }

    @Test
    @DisplayName("AT6 a second check-out on the same day is refused")
    void duplicateCheckOutIsRefused() {
        when(mechanicRepository.existsById(7L)).thenReturn(true);
        when(attendanceRepository.findByMechanicIdAndAttendanceDate(7L, DAY))
                .thenReturn(Optional.of(attendance(1L, DAY,
                        LocalDateTime.now().minusHours(4), LocalDateTime.now().minusHours(1))));

        assertThatThrownBy(() -> service.checkOut(7L, DAY))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already checked out");
    }

    @Test
    @DisplayName("AT7 a check-in for an unknown mechanic is a 404")
    void unknownMechanicIsNotFound() {
        when(mechanicRepository.existsById(404L)).thenReturn(false);

        assertThatThrownBy(() -> service.checkIn(404L, DAY))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Mechanic not found: 404");
    }

    @Test
    @DisplayName("AT8 hours worked is null while the day is still open")
    void hoursAreNullWhileOpen() {
        when(mechanicRepository.existsById(7L)).thenReturn(true);
        when(attendanceRepository.findByMechanicIdAndAttendanceDate(7L, DAY)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> {
            Attendance saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        // A half-finished shift has no duration; zero would read as "worked nothing".
        assertThat(service.checkIn(7L, DAY).getHoursWorked()).isNull();
    }

    @Test
    @DisplayName("AT9 hours worked is derived from the two stamped times")
    void hoursAreDerived() {
        when(mechanicRepository.existsById(7L)).thenReturn(true);
        LocalDateTime start = LocalDateTime.now().withSecond(0).withNano(0).minusMinutes(90);
        when(attendanceRepository.findByMechanicIdAndAttendanceDate(7L, DAY))
                .thenReturn(Optional.of(attendance(1L, DAY, start, null)));
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.checkOut(7L, DAY).getHoursWorked())
                .isCloseTo(1.5d, org.assertj.core.data.Offset.offset(0.05d));
    }

    @Test
    @DisplayName("AT10 listing returns attendance for the requested mechanic")
    void listingReturnsRows() {
        when(attendanceRepository
                        .findByAttendanceDateBetweenAndMechanicIdInOrderByAttendanceDateDescIdDesc(
                                any(), any(), any()))
                .thenReturn(List.of(attendance(1L, DAY, LocalDateTime.now(), null)));

        List<AttendanceResponseDTO> rows = service.list(7L, DAY, DAY);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getMechanicId()).isEqualTo(7L);
        assertThat(rows.get(0).getMechanicName()).isEqualTo("Anil");
    }

    @Test
    @DisplayName("AT11 omitting the mechanic lists every attendance row in the window")
    void listingAllMechanics() {
        when(attendanceRepository.findByAttendanceDateBetweenOrderByAttendanceDateDescIdDesc(any(), any()))
                .thenReturn(List.of(attendance(1L, DAY, LocalDateTime.now(), null)));

        assertThat(service.list(null, DAY, DAY)).hasSize(1);
    }

    @Test
    @DisplayName("AT12 an unserviced range returns an empty list, not an error")
    void emptyRangeReturnsEmpty() {
        when(attendanceRepository
                        .findByAttendanceDateBetweenAndMechanicIdInOrderByAttendanceDateDescIdDesc(
                                any(), any(), any()))
                .thenReturn(List.of());

        assertThat(service.list(7L, DAY, DAY)).isNotNull().isEmpty();
    }
}