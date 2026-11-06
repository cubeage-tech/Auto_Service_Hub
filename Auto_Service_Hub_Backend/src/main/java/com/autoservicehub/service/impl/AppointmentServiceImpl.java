package com.autoservicehub.service.impl;

import com.autoservicehub.dto.AdvisorResponseDTO;
import com.autoservicehub.dto.AppointmentRequestDTO;
import com.autoservicehub.dto.AppointmentResponseDTO;
import com.autoservicehub.entity.Appointment;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.User;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class AppointmentServiceImpl implements AppointmentService {

    /** The only role that may own an appointment. */
    static final String ADVISOR_ROLE = "SERVICE_ADVISOR";

    /** Bays this garage operates. */
    static final List<String> BAY_SLOTS = List.of("Bay 1", "Bay 2", "Bay 3", "Bay 4");

    /** How a booking may be taken. Stored upper-case; see resolveAppointmentType. */
    static final String TYPE_WALK_IN = "WALK_IN";
    static final String TYPE_ONLINE = "ONLINE";
    static final String TYPE_PICKUP_DROP = "PICKUP_DROP";

    static final List<String> APPOINTMENT_TYPES =
            List.of(TYPE_WALK_IN, TYPE_ONLINE, TYPE_PICKUP_DROP);

    private final AppointmentRepository repository;
    private final CustomerRepository customerRepository;
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;

    @Override
    public AppointmentResponseDTO create(AppointmentRequestDTO request) {
        Appointment entity = new Appointment();
        mapToEntity(request, entity);
        return toResponse(repository.save(entity));
    }

    @Override
    public AppointmentResponseDTO update(Long id, AppointmentRequestDTO request) {
        Appointment existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id));
        mapToEntity(request, existing);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AppointmentResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) throw new ResourceNotFoundException("Appointment not found: " + id);
        repository.deleteById(id);
    }

    private void mapToEntity(AppointmentRequestDTO r, Appointment e) {
        Customer customer = customerRepository.findById(r.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + r.getCustomerId()));
        Vehicle vehicle = vehicleRepository.findById(r.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + r.getVehicleId()));
        e.setCustomer(customer);
        e.setVehicle(vehicle);
        e.setServiceType(r.getServiceType());
        e.setAppointmentAt(r.getAppointmentAt());
        e.setPickupDrop(r.getPickupDrop());
        e.setNotes(r.getNotes());
        e.setStatus(r.getStatus() != null ? r.getStatus() : "SCHEDULED");
        e.setBaySlot(resolveBaySlot(r.getBaySlot()));
        e.setAdvisor(resolveAdvisor(r.getAdvisorId()));
        e.setAppointmentType(resolveAppointmentType(r.getAppointmentType()));
    }

    /**
     * Normalises and checks the submitted booking type.
     *
     * <p>Stored upper-case so the column holds one canonical spelling per
     * option and a report can group on it. A blank is treated as "not
     * specified" and clears the field, which is how appointments booked before
     * this column existed are stored — no value is inferred for them. A value
     * that is present but not one of the three supported types is rejected
     * rather than stored, so the column cannot drift outside the list the UI
     * offers.
     */
    private String resolveAppointmentType(String requested) {
        if (requested == null || requested.isBlank()) return null;
        String type = requested.trim().toUpperCase(java.util.Locale.ROOT);
        if (!APPOINTMENT_TYPES.contains(type)) {
            throw new BusinessRuleException(
                    "Unknown appointment type: " + requested
                            + ". Supported types: " + String.join(", ", APPOINTMENT_TYPES));
        }
        return type;
    }

    /**
     * Trims and checks the submitted bay against the garage's bay list.
     *
     * <p>A blank is treated as "not specified" and clears the bay, which is how
     * an appointment unassigned from the form is stored. A value that is present
     * but not a real bay is rejected rather than stored, so the column never
     * accumulates free text that the picker cannot round-trip.
     */
    private String resolveBaySlot(String requested) {
        if (requested == null || requested.isBlank()) return null;
        String bay = requested.trim();
        if (!BAY_SLOTS.contains(bay)) {
            throw new BusinessRuleException(
                    "Unknown bay: " + requested + ". Available bays: " + String.join(", ", BAY_SLOTS));
        }
        return bay;
    }

    /**
     * Resolves the advisor and proves the referenced user may actually hold an
     * appointment. An id that exists but is inactive, or holds a role other
     * than SERVICE_ADVISOR (a customer, mechanic, billing user…), is rejected —
     * a request must not be able to attach itself to an arbitrary user.
     *
     * <p>A null id leaves the appointment unassigned, which is how rows created
     * before advisor assignment existed are stored.
     */
    private User resolveAdvisor(Long advisorId) {
        if (advisorId == null) return null;

        User advisor = userRepository.findById(advisorId)
                .orElseThrow(() -> new ResourceNotFoundException("Advisor not found: " + advisorId));

        String role = advisor.getRole() == null ? null : advisor.getRole().getName();
        if (!ADVISOR_ROLE.equalsIgnoreCase(role)) {
            throw new BusinessRuleException(
                    "User " + advisorId + " is not an eligible service advisor (role: "
                            + (role == null ? "none" : role) + ").");
        }
        if (!Boolean.TRUE.equals(advisor.getActive())) {
            throw new BusinessRuleException("Advisor " + advisorId + " is inactive and cannot be assigned.");
        }
        return advisor;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> availableBaySlots() {
        return BAY_SLOTS;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> supportedAppointmentTypes() {
        return APPOINTMENT_TYPES;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdvisorResponseDTO> listAdvisors() {
        return userRepository.findActiveByRoleNameIn(List.of(ADVISOR_ROLE)).stream()
                .map(user -> {
                    AdvisorResponseDTO dto = new AdvisorResponseDTO();
                    dto.setId(user.getId());
                    dto.setName(displayName(user));
                    return dto;
                })
                .toList();
    }

    /** Prefers the human name; falls back to the login so the picker is never blank. */
    private String displayName(User user) {
        if (user.getFullName() != null && !user.getFullName().isBlank()) {
            return user.getFullName();
        }
        return user.getUsername();
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
        dto.setPickupDrop(e.getPickupDrop());
        dto.setNotes(e.getNotes());
        dto.setStatus(e.getStatus());
        dto.setBaySlot(e.getBaySlot());
        dto.setAppointmentType(e.getAppointmentType());
        if (e.getAdvisor() != null) {
            dto.setAdvisorId(e.getAdvisor().getId());
            dto.setAdvisorName(displayName(e.getAdvisor()));
        }
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
