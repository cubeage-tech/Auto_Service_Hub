package com.autoservicehub.service;

import com.autoservicehub.dto.PartRequestDTO;
import com.autoservicehub.dto.PartResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Spare Parts Inventory (SRS 4.7)
 */
public interface PartService {

    PartResponseDTO create(PartRequestDTO request);

    PartResponseDTO update(Long id, PartRequestDTO request);

    PartResponseDTO getById(Long id);

    Page<PartResponseDTO> list(Pageable pageable);

    void delete(Long id);

    /**
     * Parts whose stock has fallen to or below their own minimum
     * ({@code stockQty <= minStock}), worst first.
     */
    Page<PartResponseDTO> listLowStock(Pageable pageable);

    /** Number of parts at or below their own minimum. */
    long countLowStock();
}
