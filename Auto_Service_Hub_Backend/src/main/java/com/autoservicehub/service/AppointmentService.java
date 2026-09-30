package com.autoservicehub.service;

import com.autoservicehub.dto.AppointmentRequestDTO;
import com.autoservicehub.dto.AppointmentResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Appointment & Booking (SRS 4.3)
 */
public interface AppointmentService {

    AppointmentResponseDTO create(AppointmentRequestDTO request);

    AppointmentResponseDTO update(Long id, AppointmentRequestDTO request);

    AppointmentResponseDTO getById(Long id);

    Page<AppointmentResponseDTO> list(Pageable pageable);

    void delete(Long id);
}
