package com.autoservicehub.service.impl;

import com.autoservicehub.dto.AppointmentRequestDTO;
import com.autoservicehub.dto.AppointmentResponseDTO;
import com.autoservicehub.entity.Appointment;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.entity.User;
import com.autoservicehub.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Service
@RequiredArgsConstructor
@Transactional
public class AppointmentServiceImpl implements AppointmentService {

    private final AppointmentRepository repository;
    private final CustomerRepository customerRepository;
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;

    @Override
    public AppointmentResponseDTO create(AppointmentRequestDTO request) {
        validateAppointmentTime(request.getAppointmentAt(), null);
        Appointment entity = new Appointment();
        mapToEntity(request, entity);
        validateNoBayConflict(entity, null);
        return toResponse(repository.save(entity));
    }

    @Override
    public AppointmentResponseDTO update(Long id, AppointmentRequestDTO request) {
        Appointment existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id));
        assertAdvisorCanAccess(existing);
        validateAppointmentTime(request.getAppointmentAt(), existing.getAppointmentAt());
        mapToEntity(request, existing);
        validateNoBayConflict(existing, id);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentResponseDTO getById(Long id) {
        Appointment appointment = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id));
        assertAdvisorCanAccess(appointment);
        return toResponse(appointment);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AppointmentResponseDTO> list(Pageable pageable) {
        if (isAdvisorUser()) {
                return repository.findVisibleToAdvisor(currentAdvisor().getId(), pageable)
                    .map(this::toResponse);
        }
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        Appointment appointment = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id));
        assertAdvisorCanAccess(appointment);
        repository.delete(appointment);
    }

    private void mapToEntity(AppointmentRequestDTO r, Appointment e) {
        Customer customer = customerRepository.findById(r.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + r.getCustomerId()));
        Vehicle vehicle = vehicleRepository.findById(r.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + r.getVehicleId()));
        if (vehicle.getCustomer() == null || !vehicle.getCustomer().getId().equals(customer.getId())) {
            throw new BusinessRuleException("The selected vehicle does not belong to the selected customer.");
        }
        e.setCustomer(customer);
        e.setVehicle(vehicle);
        if (isAdvisorUser()) {
            User advisor = currentAdvisor();
            if (r.getAssignedAdvisorId() != null && !r.getAssignedAdvisorId().equals(advisor.getId())) {
                throw new AccessDeniedException("Service Advisors can assign appointments only to themselves.");
            }
            if (e.getAssignedAdvisor() == null) e.setAssignedAdvisor(advisor);
        } else if (r.getAssignedAdvisorId() != null) {
            User advisor = userRepository.findById(r.getAssignedAdvisorId())
                .orElseThrow(() -> new ResourceNotFoundException("Assigned advisor not found: " + r.getAssignedAdvisorId()));
            String roleName = advisor.getRole() == null ? "" : advisor.getRole().getName();
            if (!roleName.replaceFirst("(?i)^ROLE_", "").trim().equalsIgnoreCase("SERVICE_ADVISOR")) {
            throw new BusinessRuleException("Appointment assignee must have the SERVICE_ADVISOR role.");
            }
            e.setAssignedAdvisor(advisor);
        }
        if (r.getAppointmentType() != null) e.setAppointmentType(r.getAppointmentType().trim());
        e.setServiceType(r.getServiceType());
        e.setAppointmentAt(r.getAppointmentAt());
        if (r.getTimeSlot() != null) e.setTimeSlot(r.getTimeSlot().trim());
        if (r.getBay() != null) e.setBay(r.getBay().trim());
        e.setPickupDrop(r.getPickupDrop());
        e.setNotes(r.getNotes());
        e.setStatus(r.getStatus() != null ? r.getStatus() : "SCHEDULED");
    }

    private void assertAdvisorCanAccess(Appointment appointment) {
        if (isAdvisorUser() && appointment.getAssignedAdvisor() != null
                && !appointment.getAssignedAdvisor().getId().equals(currentAdvisor().getId())) {
            throw new AccessDeniedException("Service Advisors can access only their assigned appointments.");
        }
    }

    private boolean isAdvisorUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SERVICE_ADVISOR".equals(authority.getAuthority()));
    }

    private User currentAdvisor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return userRepository.findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated Service Advisor not found."));
    }

    private void validateAppointmentTime(java.time.LocalDateTime requestedAt, java.time.LocalDateTime existingAt) {
        if (requestedAt == null) throw new BusinessRuleException("Appointment date and time are required.");
        if ((existingAt == null || !requestedAt.equals(existingAt)) && requestedAt.isBefore(java.time.LocalDateTime.now())) {
            throw new BusinessRuleException("New appointment date and time cannot be in the past.");
        }
    }

    private void validateNoBayConflict(Appointment appointment, Long excludedId) {
        String status = appointment.getStatus() == null ? "" : appointment.getStatus().trim();
        if (status.equalsIgnoreCase("CANCELLED") || status.equalsIgnoreCase("CANCELED")) return;
        if (appointment.getBay() != null && !appointment.getBay().isBlank()
                && repository.existsBayConflict(appointment.getAppointmentAt(), appointment.getBay().trim(), excludedId)) {
            throw new BusinessRuleException("The selected bay already has an appointment at this time.");
        }
    }

    private AppointmentResponseDTO toResponse(Appointment e) {
        AppointmentResponseDTO dto = new AppointmentResponseDTO();
        dto.setId(e.getId());
        if (e.getCustomer() != null) {
            dto.setCustomerId(e.getCustomer().getId());
            dto.setCustomerName(e.getCustomer().getName());
            dto.setCustomerPhone(e.getCustomer().getPhone());
        }
        if (e.getVehicle() != null) {
            dto.setVehicleId(e.getVehicle().getId());
            dto.setVehicleInfo(e.getVehicle().getRegistrationNo() + " | " + e.getVehicle().getModel());
        }
        dto.setServiceType(e.getServiceType());
        dto.setAppointmentAt(e.getAppointmentAt());
        dto.setAppointmentType(e.getAppointmentType());
        dto.setTimeSlot(e.getTimeSlot());
        dto.setBay(e.getBay());
        if (e.getAssignedAdvisor() != null) {
            dto.setAssignedAdvisorId(e.getAssignedAdvisor().getId());
            dto.setAssignedAdvisorName(e.getAssignedAdvisor().getFullName());
        }
        dto.setPickupDrop(e.getPickupDrop());
        dto.setNotes(e.getNotes());
        dto.setStatus(e.getStatus());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
