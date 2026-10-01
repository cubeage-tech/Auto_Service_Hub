package com.autoservicehub.service.impl;

import com.autoservicehub.dto.VehicleRequestDTO;
import com.autoservicehub.dto.VehicleResponseDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class VehicleServiceImpl implements VehicleService {

    private final VehicleRepository repository;
    private final CustomerRepository customerRepository;

    @Override
    public VehicleResponseDTO create(VehicleRequestDTO request) {
        Vehicle entity = new Vehicle();
        mapToEntity(request, entity);
        return toResponse(repository.save(entity));
    }

    @Override
    public VehicleResponseDTO update(Long id, VehicleRequestDTO request) {
        Vehicle existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + id));
        mapToEntity(request, existing);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public VehicleResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VehicleResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) throw new ResourceNotFoundException("Vehicle not found: " + id);
        repository.deleteById(id);
    }

    private void mapToEntity(VehicleRequestDTO r, Vehicle e) {
        if (r.getCustomerId() != null) {
            Customer customer = customerRepository.findById(r.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + r.getCustomerId()));
            e.setCustomer(customer);
        }
        e.setRegistrationNo(r.getRegistrationNo());
        e.setMake(r.getMake());
        e.setModel(r.getModel());
        e.setVariant(r.getVariant());
        if (r.getFuelType() != null) e.setFuelType(r.getFuelType());
        e.setYear(r.getYear());
        e.setEngineNo(r.getEngineNo());
        e.setChassisNo(r.getChassisNo());
        e.setMileage(r.getMileage());
        e.setInsuranceExpiry(r.getInsuranceExpiry());
        e.setWarrantyExpiry(r.getWarrantyExpiry());
        if (r.getNotes() != null) e.setNotes(r.getNotes());
    }

    private VehicleResponseDTO toResponse(Vehicle e) {
        VehicleResponseDTO dto = new VehicleResponseDTO();
        dto.setId(e.getId());
        dto.setRegistrationNo(e.getRegistrationNo());
        dto.setMake(e.getMake());
        dto.setModel(e.getModel());
        dto.setVariant(e.getVariant());
        dto.setFuelType(e.getFuelType());
        dto.setYear(e.getYear());
        dto.setEngineNo(e.getEngineNo());
        dto.setChassisNo(e.getChassisNo());
        dto.setMileage(e.getMileage());
        dto.setInsuranceExpiry(e.getInsuranceExpiry());
        dto.setWarrantyExpiry(e.getWarrantyExpiry());
        dto.setNotes(e.getNotes());
        if (e.getCustomer() != null) {
            dto.setCustomerId(e.getCustomer().getId());
            dto.setCustomerName(e.getCustomer().getName());
        }
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
