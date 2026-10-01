package com.autoservicehub.repository;

import com.autoservicehub.entity.Appointment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AppointmentRepository
        extends JpaRepository<Appointment, Long>,
                JpaSpecificationExecutor<Appointment> {

    // Existing dashboard query
    long countByAppointmentAtBetween(
            LocalDateTime from,
            LocalDateTime to);

    // Customer appointments, newest first
    List<Appointment> findByCustomerIdOrderByAppointmentAtDesc(
            Long customerId);

    // AI maintenance prediction: appointments for a vehicle
    List<Appointment> findByVehicleIdOrderByAppointmentAtDesc(
            Long vehicleId);

    // Appointments visible to an advisor, including unassigned appointments
    @Query("select a from Appointment a left join a.assignedAdvisor advisor " +
            "where advisor.id = :advisorId or advisor is null")
    Page<Appointment> findVisibleToAdvisor(
            @Param("advisorId") Long advisorId,
            Pageable pageable);

    // Count appointments assigned to an advisor within a time range
    @Query("select count(a) from Appointment a where a.assignedAdvisor.id = :advisorId " +
            "and a.appointmentAt >= :from and a.appointmentAt < :to")
    long countForAdvisorBetween(
            @Param("advisorId") Long advisorId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // Check whether a bay is already booked at the specified appointment time
    @Query("select case when count(a) > 0 then true else false end from Appointment a " +
            "where a.appointmentAt = :appointmentAt and lower(a.bay) = lower(:bay) " +
            "and upper(coalesce(a.status, '')) not in ('CANCELLED', 'CANCELED') " +
            "and (:excludedId is null or a.id <> :excludedId)")
    boolean existsBayConflict(
            @Param("appointmentAt") LocalDateTime appointmentAt,
            @Param("bay") String bay,
            @Param("excludedId") Long excludedId);
}