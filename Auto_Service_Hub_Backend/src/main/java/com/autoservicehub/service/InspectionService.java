package com.autoservicehub.service;

import com.autoservicehub.dto.InspectionRequestDTO;
import com.autoservicehub.dto.InspectionResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Vehicle Inspection (SRS 4.4)
 */
public interface InspectionService {

    InspectionResponseDTO create(InspectionRequestDTO request);

    InspectionResponseDTO update(Long id, InspectionRequestDTO request);

    InspectionResponseDTO getById(Long id);

    Page<InspectionResponseDTO> list(Pageable pageable);

    void delete(Long id);

    /**
     * Inspections carried out on a given vehicle, newest first.
     */
    Page<InspectionResponseDTO> listByVehicle(Long vehicleId, Pageable pageable);

    /**
     * The inspection that produced the given job card.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException
     *         if the job card does not exist, or no inspection is linked to it
     */
    InspectionResponseDTO getByJobCardId(Long jobCardId);

    /**
     * The inspection linked to a job card, or {@code null} when the job card
     * was raised without one. Used when building the job-card view so an
     * un-inspected job card is not an error.
     */
    InspectionResponseDTO findByJobCardIdOrNull(Long jobCardId);
}
