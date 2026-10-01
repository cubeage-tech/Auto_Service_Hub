package com.autoservicehub.service.impl;

import com.autoservicehub.dto.ServicePackageRequestDTO;
import com.autoservicehub.dto.ServicePackageResponseDTO;
import com.autoservicehub.dto.PackageItemDTO;
import com.autoservicehub.entity.PackageItem;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.entity.ServicePackage;
import com.autoservicehub.repository.ServicePackageRepository;
import com.autoservicehub.service.ServicePackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ServicePackageServiceImpl implements ServicePackageService {

    private final ServicePackageRepository repository;
    private final PartRepository partRepository;

    @Override
    public ServicePackageResponseDTO create(ServicePackageRequestDTO request) {
        ServicePackage entity = new ServicePackage();
        mapToEntity(request, entity);
        return toResponse(repository.save(entity));
    }

    @Override
    public ServicePackageResponseDTO update(Long id, ServicePackageRequestDTO request) {
        ServicePackage existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ServicePackage not found: " + id));
        mapToEntity(request, existing);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public ServicePackageResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ServicePackage not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ServicePackageResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) throw new ResourceNotFoundException("ServicePackage not found: " + id);
        repository.deleteById(id);
    }

    private void mapToEntity(ServicePackageRequestDTO r, ServicePackage e) {
        e.setName(r.getName());
        if (r.getCode() != null) e.setCode(r.getCode());
        e.setType(r.getType());
        e.setPrice(r.getPrice());
        if (r.getDurationMinutes() != null) e.setDurationMinutes(r.getDurationMinutes());
        if (r.getValidityDays() != null) e.setValidityDays(r.getValidityDays());
        if (r.getGstRate() != null) e.setGstRate(r.getGstRate());
        if (r.getDiscount() != null) e.setDiscount(r.getDiscount());
        e.setActive(r.getActive() != null ? r.getActive() : true);
        if (r.getItems() != null) {
            e.getItems().clear();
            for (PackageItemDTO itemRequest : r.getItems()) {
                PackageItem item = new PackageItem();
                item.setItemName(itemRequest.getItemName().trim());
                item.setItemType(itemRequest.getItemType().trim());
                item.setServicePackage(e);
                if (itemRequest.getPartId() != null) {
                    item.setPart(partRepository.findById(itemRequest.getPartId())
                            .orElseThrow(() -> new ResourceNotFoundException("Part not found: " + itemRequest.getPartId())));
                }
                e.getItems().add(item);
            }
        }
    }

    private ServicePackageResponseDTO toResponse(ServicePackage e) {
        ServicePackageResponseDTO dto = new ServicePackageResponseDTO();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setCode(e.getCode());
        dto.setType(e.getType());
        dto.setPrice(e.getPrice());
        dto.setDurationMinutes(e.getDurationMinutes());
        dto.setValidityDays(e.getValidityDays());
        dto.setGstRate(e.getGstRate());
        dto.setDiscount(e.getDiscount());
        dto.setActive(e.getActive());
        dto.setItems(e.getItems().stream().map(item -> {
            PackageItemDTO itemDTO = new PackageItemDTO();
            itemDTO.setId(item.getId());
            itemDTO.setItemName(item.getItemName());
            itemDTO.setItemType(item.getItemType());
            itemDTO.setPartId(item.getPart() == null ? null : item.getPart().getId());
            return itemDTO;
        }).toList());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
