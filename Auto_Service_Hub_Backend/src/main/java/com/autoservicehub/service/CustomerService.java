package com.autoservicehub.service;

import com.autoservicehub.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Customer CRM (SRS 4.1, FR-CRM-1 through FR-CRM-7).
 */
public interface CustomerService {

    // ── FR-CRM-1: Basic CRUD ──────────────────────────────────────────────
    CustomerResponseDTO create(CustomerRequestDTO request);

    CustomerResponseDTO update(Long id, CustomerRequestDTO request);

    CustomerResponseDTO getById(Long id);

    Page<CustomerResponseDTO> list(Pageable pageable);

    /**
     * FR-CRM-1: Soft-deactivate — sets status to INACTIVE.
     * Does NOT delete the record; preserves all historical data.
     */
    void deactivate(Long id);

    /**
     * Hard delete — retained for admin use only; deactivate() is the
     * preferred operation for normal CRM flows.
     */
    void delete(Long id);

    // ── FR-CRM-6: Server-side search / filter / sort ──────────────────────
    /**
     * Search by name, phone, or email (partial, case-insensitive).
     * Optionally filter by status (ACTIVE / INACTIVE).
     * Optionally filter by vehicle registration number.
     * Sorting is handled via Pageable (e.g. sort=name,asc).
     */
    Page<CustomerResponseDTO> search(String q, String status, String vehicleReg, Pageable pageable);

    // ── FR-CRM-2: Vehicles per customer ───────────────────────────────────
    List<VehicleResponseDTO> getVehiclesByCustomer(Long customerId);

    // ── FR-CRM-3: Service history ─────────────────────────────────────────
    List<ServiceHistoryDTO> getServiceHistoryByCustomer(Long customerId);

    List<ServiceHistoryDTO> getServiceHistoryByVehicle(Long vehicleId);

    // ── FR-CRM-7: Customer 360 composite view ─────────────────────────────
    Customer360DTO getCustomer360(Long customerId);
}
