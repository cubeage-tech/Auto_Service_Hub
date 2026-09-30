package com.autoservicehub.service.impl;

import com.autoservicehub.dto.PartRequestDTO;
import com.autoservicehub.dto.PartResponseDTO;
import com.autoservicehub.entity.Part;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.service.PartService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class PartServiceImpl implements PartService {

    private final PartRepository repository;

    @Override
    public PartResponseDTO create(PartRequestDTO request) {
        Part entity = new Part();
        mapToEntity(request, entity);
        return toResponse(repository.save(entity));
    }

    @Override
    public PartResponseDTO update(Long id, PartRequestDTO request) {
        Part existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Part not found: " + id));
        mapToEntity(request, existing);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public PartResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Part not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PartResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) throw new ResourceNotFoundException("Part not found: " + id);
        repository.deleteById(id);
    }

    /**
     * Parts at or below their own reorder level.
     *
     * <p>Delegates to {@link PartRepository#findLowStock(Pageable)} so the list
     * and {@link #countLowStock()} apply exactly the same
     * {@code stockQty <= minStock} rule, rather than re-deriving it here.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PartResponseDTO> listLowStock(Pageable pageable) {
        return repository.findLowStock(pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public long countLowStock() {
        return repository.countLowStock();
    }

    private void mapToEntity(PartRequestDTO r, Part e) {
        e.setSku(r.getSku());
        e.setName(r.getName());
        e.setUnit(r.getUnit());
        e.setSellingPrice(r.getSellingPrice());
        e.setPurchasePrice(r.getPurchasePrice());
        e.setStockQty(r.getStockQty() != null ? r.getStockQty() : 0);
        e.setMinStock(r.getMinStock() != null ? r.getMinStock() : 0);
    }

    private PartResponseDTO toResponse(Part e) {
        PartResponseDTO dto = new PartResponseDTO();
        dto.setId(e.getId());
        dto.setSku(e.getSku());
        dto.setName(e.getName());
        dto.setUnit(e.getUnit());
        dto.setSellingPrice(e.getSellingPrice());
        dto.setPurchasePrice(e.getPurchasePrice());
        dto.setStockQty(e.getStockQty());
        dto.setMinStock(e.getMinStock());
        dto.setLowStock(e.getStockQty() != null && e.getMinStock() != null
                && e.getStockQty() <= e.getMinStock());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
