package com.autoservicehub.repository;

import com.autoservicehub.entity.Vehicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for Vehicle.
 * Extends JpaSpecificationExecutor for dynamic filters (SRS 9, 17).
 */
@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long>, JpaSpecificationExecutor<Vehicle> {

    /**
     * FR-CRM-2: All vehicles belonging to one customer, newest first.
     */
    List<Vehicle> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /**
     * FR-CRM-6: Search by registration number (exact, case-insensitive) —
     * used for vehicle-registration filter in customer search.
     */
    Optional<Vehicle> findByRegistrationNoIgnoreCase(String registrationNo);

    @Query("SELECT v FROM Vehicle v WHERE " +
            "(:fuelType IS NULL OR UPPER(v.fuelType) = UPPER(:fuelType)) AND " +
            "(:status IS NULL OR UPPER(v.status) = UPPER(:status))")
    Page<Vehicle> findFiltered(
            @Param("fuelType") String fuelType,
            @Param("status") String status,
            Pageable pageable);
}
