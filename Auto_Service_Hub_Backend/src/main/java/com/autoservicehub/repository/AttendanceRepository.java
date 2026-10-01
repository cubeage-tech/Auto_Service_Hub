package com.autoservicehub.repository;

import com.autoservicehub.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Spring Data JPA repository for Attendance. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long>, JpaSpecificationExecutor<Attendance> {
	boolean existsByMechanicIdAndCheckOutIsNull(Long mechanicId);
	Attendance findFirstByMechanicIdAndCheckOutIsNullOrderByCheckInDesc(Long mechanicId);
	List<Attendance> findByMechanicIdOrderByAttendanceDateDescCheckInDesc(Long mechanicId);
}
