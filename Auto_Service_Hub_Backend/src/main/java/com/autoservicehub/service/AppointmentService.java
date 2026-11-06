package com.autoservicehub.service;

import com.autoservicehub.dto.AdvisorResponseDTO;
import com.autoservicehub.dto.AppointmentRequestDTO;
import com.autoservicehub.dto.AppointmentResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Appointment & Booking (SRS 4.3)
 */
public interface AppointmentService {

    AppointmentResponseDTO create(AppointmentRequestDTO request);

    AppointmentResponseDTO update(Long id, AppointmentRequestDTO request);

    AppointmentResponseDTO getById(Long id);

    Page<AppointmentResponseDTO> list(Pageable pageable);

    void delete(Long id);

    /** Bays this garage can book an appointment into. */
    List<String> availableBaySlots();

    /** Booking types an appointment may be created with. */
    List<String> supportedAppointmentTypes();

    /** Active users eligible to be assigned as the appointment's advisor. */
    List<AdvisorResponseDTO> listAdvisors();
}
