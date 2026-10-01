package com.autoservicehub.service.impl;

import com.autoservicehub.dto.*;
import com.autoservicehub.entity.*;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.*;
import com.autoservicehub.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Customer CRM implementation (SRS FR-CRM-1 through FR-CRM-7).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository      repository;
    private final VehicleRepository       vehicleRepository;
    private final JobCardRepository       jobCardRepository;
    private final AppointmentRepository   appointmentRepository;
    private final InvoiceRepository       invoiceRepository;
    private final FeedbackRepository      feedbackRepository;
    private final CommunicationRepository communicationRepository;

    // ── FR-CRM-1: Basic CRUD ──────────────────────────────────────────────

    @Override
    public CustomerResponseDTO create(CustomerRequestDTO request) {
        // Guard duplicate phone
        if (repository.existsByPhone(request.getPhone())) {
            throw new BusinessRuleException(
                    "A customer with phone '" + request.getPhone() + "' already exists.");
        }
        Customer entity = new Customer();
        mapToEntity(request, entity);
        return toResponse(repository.save(entity));
    }

    @Override
    public CustomerResponseDTO update(Long id, CustomerRequestDTO request) {
        Customer existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
        // Guard duplicate phone (exclude self)
        if (repository.existsByPhoneAndIdNot(request.getPhone(), id)) {
            throw new BusinessRuleException(
                    "A customer with phone '" + request.getPhone() + "' already exists.");
        }
        mapToEntity(request, existing);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    /**
     * FR-CRM-1: Soft-deactivate — sets status = INACTIVE.
     * Preserves all historical records (job cards, invoices, etc.).
     */
    @Override
    public void deactivate(Long id) {
        Customer existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
        if ("INACTIVE".equalsIgnoreCase(existing.getStatus())) {
            throw new BusinessRuleException("Customer " + id + " is already inactive.");
        }
        existing.setStatus("INACTIVE");
        repository.save(existing);
    }

    /** Hard delete — admin only; normal flows should use deactivate(). */
    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Customer not found: " + id);
        }
        repository.deleteById(id);
    }

    // ── FR-CRM-6: Search / filter / sort ─────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerResponseDTO> search(String q, String status, String vehicleReg, Pageable pageable) {
        // Vehicle-reg filter takes priority when supplied
        if (vehicleReg != null && !vehicleReg.isBlank()) {
            return repository.searchByVehicleRegistration(vehicleReg.trim(), pageable)
                             .map(this::toResponse);
        }
        // Normalise nulls so JPQL receives consistent values
        String queryParam  = (q      != null && !q.isBlank())      ? q.trim()      : null;
        String statusParam = (status != null && !status.isBlank()) ? status.trim() : null;
        return repository.search(queryParam, statusParam, pageable).map(this::toResponse);
    }

    // ── FR-CRM-2: Vehicles per customer ───────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<VehicleResponseDTO> getVehiclesByCustomer(Long customerId) {
        assertCustomerExists(customerId);
        return vehicleRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                                .stream()
                                .map(this::vehicleToResponse)
                                .toList();
    }

    // ── FR-CRM-3: Service history ─────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ServiceHistoryDTO> getServiceHistoryByCustomer(Long customerId) {
        assertCustomerExists(customerId);
        return jobCardRepository.findByCustomerIdOrderByAssignedDateDesc(customerId)
                                .stream()
                                .map(this::toServiceHistory)
                                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceHistoryDTO> getServiceHistoryByVehicle(Long vehicleId) {
        if (!vehicleRepository.existsById(vehicleId)) {
            throw new ResourceNotFoundException("Vehicle not found: " + vehicleId);
        }
        return jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(vehicleId)
                                .stream()
                                .map(this::toServiceHistory)
                                .toList();
    }

    // ── FR-CRM-7: Customer 360 ────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Customer360DTO getCustomer360(Long customerId) {
        Customer customer = repository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        Customer360DTO dto = new Customer360DTO();

        // Profile
        dto.setId(customer.getId());
        dto.setName(customer.getName());
        dto.setPhone(customer.getPhone());
        dto.setEmail(customer.getEmail());
        dto.setAddress(customer.getAddress());
        dto.setStatus(customer.getStatus());
        dto.setCreatedAt(customer.getCreatedAt());

        // Vehicles
        dto.setVehicles(
            vehicleRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                             .stream().map(this::vehicleToResponse).toList()
        );

        // Service history (job cards) — newest first
        List<ServiceHistoryDTO> history =
            jobCardRepository.findByCustomerIdOrderByAssignedDateDesc(customerId)
                             .stream().map(this::toServiceHistory).toList();
        dto.setServiceHistory(history);

        // Appointments — newest first
        dto.setAppointments(
            appointmentRepository.findByCustomerIdOrderByAppointmentAtDesc(customerId)
                                 .stream().map(this::appointmentToResponse).toList()
        );

        // Invoices — newest first
        dto.setInvoices(
            invoiceRepository.findByCustomerId(customerId)
                             .stream().map(this::invoiceToResponse).toList()
        );

        // Feedback
        dto.setFeedback(
            feedbackRepository.findTop10ByCustomerIdOrderByCreatedAtDesc(customerId)
                              .stream().map(this::feedbackToResponse).toList()
        );

        // Communications — last 20
        dto.setCommunications(
            communicationRepository.findTop20ByCustomerIdOrderBySentAtDesc(customerId)
                                   .stream().map(this::communicationToResponse).toList()
        );

        // Summary stats
        dto.setTotalVisits(
            history.stream().filter(h -> "DELIVERED".equalsIgnoreCase(h.getStatus())).count()
        );

        BigDecimal totalSpend = invoiceRepository.sumTotalByCustomerId(customerId);
        dto.setTotalSpend(totalSpend != null ? totalSpend : BigDecimal.ZERO);

        LocalDateTime lastServiceDate = jobCardRepository
                .findLastServiceDateByCustomerId(customerId).orElse(null);
        dto.setLastServiceDate(lastServiceDate);

        dto.setOpenInvoicesCount(invoiceRepository.countOpenByCustomerId(customerId));

        BigDecimal openAmt = invoiceRepository.sumOpenTotalByCustomerId(customerId);
        dto.setOpenInvoicesAmount(openAmt != null ? openAmt : BigDecimal.ZERO);

        dto.setAverageRating(feedbackRepository.findAverageRatingByCustomerId(customerId));

        return dto;
    }

    // ── Private mapping helpers ───────────────────────────────────────────

    private void mapToEntity(CustomerRequestDTO r, Customer e) {
        e.setName(r.getName());
        e.setPhone(r.getPhone());
        e.setEmail(r.getEmail());
        e.setAddress(r.getAddress());
        if (r.getCity() != null) e.setCity(r.getCity());
        if (r.getPincode() != null) e.setPincode(r.getPincode());
        if (r.getLoyaltyTier() != null) e.setLoyaltyTier(r.getLoyaltyTier());
        if (r.getNotes() != null) e.setNotes(r.getNotes());
        if (r.getPreferences() != null) e.setPreferences(r.getPreferences());
        e.setStatus(r.getStatus() != null ? r.getStatus() : "ACTIVE");
    }

    private CustomerResponseDTO toResponse(Customer e) {
        CustomerResponseDTO dto = new CustomerResponseDTO();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setPhone(e.getPhone());
        dto.setEmail(e.getEmail());
        dto.setAddress(e.getAddress());
        dto.setCity(e.getCity());
        dto.setPincode(e.getPincode());
        dto.setLoyaltyTier(e.getLoyaltyTier());
        dto.setNotes(e.getNotes());
        dto.setPreferences(e.getPreferences());
        dto.setStatus(e.getStatus());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    private VehicleResponseDTO vehicleToResponse(Vehicle e) {
        VehicleResponseDTO dto = new VehicleResponseDTO();
        dto.setId(e.getId());
        dto.setRegistrationNo(e.getRegistrationNo());
        dto.setMake(e.getMake());
        dto.setModel(e.getModel());
        dto.setVariant(e.getVariant());
        dto.setYear(e.getYear());
        dto.setEngineNo(e.getEngineNo());
        dto.setChassisNo(e.getChassisNo());
        dto.setMileage(e.getMileage());
        dto.setInsuranceExpiry(e.getInsuranceExpiry());
        dto.setWarrantyExpiry(e.getWarrantyExpiry());
        if (e.getCustomer() != null) {
            dto.setCustomerId(e.getCustomer().getId());
            dto.setCustomerName(e.getCustomer().getName());
        }
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    private ServiceHistoryDTO toServiceHistory(JobCard e) {
        ServiceHistoryDTO dto = new ServiceHistoryDTO();
        dto.setJobCardId(e.getId());
        dto.setJobCardNumber(e.getJobCardNumber());
        if (e.getVehicle() != null) {
            dto.setVehicleId(e.getVehicle().getId());
            dto.setRegistrationNo(e.getVehicle().getRegistrationNo());
            dto.setVehicleInfo(e.getVehicle().getRegistrationNo() + " | " + e.getVehicle().getModel());
        }
        if (e.getMechanic() != null) {
            dto.setMechanicId(e.getMechanic().getId());
            dto.setMechanicName(e.getMechanic().getName());
        }
        dto.setServiceType(e.getServiceType());
        dto.setComplaint(e.getComplaint());
        dto.setTechnicianNotes(e.getTechnicianNotes());
        dto.setOdometerReading(e.getOdometerReading());
        dto.setEstimatedCost(e.getEstimatedCost());
        dto.setStatus(e.getStatus());
        dto.setProgress(statusToProgress(e.getStatus()));
        dto.setAssignedDate(e.getAssignedDate());
        dto.setCompletedDate(e.getCompletedDate());
        dto.setCreatedAt(e.getCreatedAt());
        return dto;
    }

    private AppointmentResponseDTO appointmentToResponse(Appointment e) {
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
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    private InvoiceResponseDTO invoiceToResponse(Invoice e) {
        InvoiceResponseDTO dto = new InvoiceResponseDTO();
        dto.setId(e.getId());
        if (e.getJobCard() != null) {
            dto.setJobCardId(e.getJobCard().getId());
            if (e.getJobCard().getCustomer() != null) {
                dto.setCustomerName(e.getJobCard().getCustomer().getName());
            }
            if (e.getJobCard().getVehicle() != null) {
                dto.setVehicleInfo(e.getJobCard().getVehicle().getRegistrationNo()
                        + " | " + e.getJobCard().getVehicle().getModel());
            }
        }
        dto.setSubtotal(e.getSubtotal());
        dto.setDiscount(e.getDiscount());
        dto.setGst(e.getGst());
        dto.setTotal(e.getTotal());
        dto.setStatus(e.getStatus());
        dto.setInvoiceDate(e.getInvoiceDate());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    private FeedbackResponseDTO feedbackToResponse(Feedback e) {
        FeedbackResponseDTO dto = new FeedbackResponseDTO();
        dto.setId(e.getId());
        if (e.getCustomer() != null) {
            dto.setCustomerId(e.getCustomer().getId());
            dto.setCustomerName(e.getCustomer().getName());
        }
        if (e.getJobCard() != null) {
            dto.setJobCardId(e.getJobCard().getId());
            dto.setJobCardNumber(e.getJobCard().getJobCardNumber());
            dto.setServiceType(e.getJobCard().getServiceType());
        }
        dto.setRating(e.getRating());
        dto.setRatingLabel(ratingLabel(e.getRating()));
        dto.setComments(e.getComments());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    private CommunicationResponseDTO communicationToResponse(Communication e) {
        CommunicationResponseDTO dto = new CommunicationResponseDTO();
        dto.setId(e.getId());
        if (e.getCustomer() != null) {
            dto.setCustomerId(e.getCustomer().getId());
            dto.setCustomerName(e.getCustomer().getName());
            dto.setCustomerPhone(e.getCustomer().getPhone());
        }
        if (e.getJobCard() != null) {
            dto.setJobCardId(e.getJobCard().getId());
            dto.setJobCardNumber(e.getJobCard().getJobCardNumber());
        }
        dto.setChannel(e.getChannel());
        dto.setDirection(e.getDirection());
        dto.setSubject(e.getSubject());
        dto.setMessage(e.getMessage());
        dto.setSentAt(e.getSentAt());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    private String ratingLabel(Integer rating) {
        if (rating == null) return null;
        return switch (rating) {
            case 1 -> "Poor";
            case 2 -> "Fair";
            case 3 -> "Good";
            case 4 -> "Very Good";
            case 5 -> "Excellent";
            default -> "Unknown";
        };
    }

    private int statusToProgress(String status) {
        if (status == null) return 1;
        return switch (status.toUpperCase()) {
            case "RECEIVED"     -> 1;
            case "INSPECTION"   -> 2;
            case "IN_REPAIR", "IN REPAIR" -> 3;
            case "QUALITY_CHECK", "QUALITY CHECK", "QC" -> 4;
            case "DELIVERED"    -> 5;
            default             -> 1;
        };
    }

    private void assertCustomerExists(Long customerId) {
        if (!repository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found: " + customerId);
        }
    }
}
