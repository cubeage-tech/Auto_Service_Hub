package com.autoservicehub.service.impl;

import com.autoservicehub.dto.VehicleRequestDTO;
import com.autoservicehub.dto.VehicleResponseDTO;
import com.autoservicehub.dto.VehicleServiceHistoryDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class VehicleServiceImpl implements VehicleService {

    private final VehicleRepository repository;
    private final CustomerRepository customerRepository;

    /**
     * The records the history is assembled from. They are already indexed by
     * vehicle, so nothing new is stored or duplicated (FR-VEH-6).
     */
    private final JobCardRepository jobCardRepository;
    private final JobTaskRepository jobTaskRepository;
    private final InvoiceRepository invoiceRepository;

    @Override
    public VehicleResponseDTO create(VehicleRequestDTO request) {
        Vehicle entity = new Vehicle();
        mapToEntity(request, entity);
        return toResponse(repository.save(entity));
    }

    /**
     * FR-VEH-6: assembles the vehicle's service history from existing records.
     *
     * <p>Read from the job cards already raised against the vehicle, their tasks
     * and their invoices. Nothing is recomputed and nothing new is stored.
     */
    @Override
    @Transactional(readOnly = true)
    public VehicleServiceHistoryDTO getServiceHistory(Long id) {
        Vehicle vehicle = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + id));

        VehicleServiceHistoryDTO dto = new VehicleServiceHistoryDTO();
        dto.setVehicleId(vehicle.getId());
        dto.setVehicleInfo(describe(vehicle));

        List<VehicleServiceHistoryDTO.ServiceVisitDTO> visits = new ArrayList<>();
        BigDecimal totalInvoiced = BigDecimal.ZERO;

        // Newest visit first, so "last serviced" is the first row.
        for (JobCard jobCard : jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(id)) {
            VehicleServiceHistoryDTO.ServiceVisitDTO visit = new VehicleServiceHistoryDTO.ServiceVisitDTO();
            visit.setJobCardId(jobCard.getId());
            visit.setJobCardNumber(jobCard.getJobCardNumber());
            visit.setServiceType(jobCard.getServiceType());
            visit.setStatus(jobCard.getStatus());
            visit.setAssignedDate(jobCard.getAssignedDate());
            visit.setCompletedDate(jobCard.getCompletedDate());
            visit.setTasks(toTaskDtos(jobCard.getId()));

            List<Invoice> invoices =
                    invoiceRepository.findByJobCardIdOrderByInvoiceDateDescIdDesc(jobCard.getId());
            List<VehicleServiceHistoryDTO.InvoiceSummaryDTO> summaries = new ArrayList<>();
            BigDecimal visitTotal = null;
            for (Invoice invoice : invoices) {
                VehicleServiceHistoryDTO.InvoiceSummaryDTO summary =
                        new VehicleServiceHistoryDTO.InvoiceSummaryDTO();
                summary.setId(invoice.getId());
                summary.setStatus(invoice.getStatus());
                summary.setTotal(invoice.getTotal());
                summary.setInvoiceDate(invoice.getInvoiceDate());
                summaries.add(summary);
                BigDecimal amount = invoice.getTotal() == null ? BigDecimal.ZERO : invoice.getTotal();
                visitTotal = visitTotal == null ? amount : visitTotal.add(amount);
            }
            visit.setInvoices(summaries);
            // Null, not zero, when nothing has been billed: "not yet invoiced" and
            // "invoiced at zero" are different facts about the job.
            visit.setInvoiceTotal(visitTotal);
            if (visitTotal != null) {
                totalInvoiced = totalInvoiced.add(visitTotal);
            }

            visits.add(visit);
        }

        dto.setVisits(visits);
        dto.setTotalVisits(visits.size());
        dto.setTotalInvoiced(totalInvoiced);
        return dto;
    }

    private List<VehicleServiceHistoryDTO.TaskDTO> toTaskDtos(Long jobCardId) {
        List<VehicleServiceHistoryDTO.TaskDTO> tasks = new ArrayList<>();
        for (JobTask task : jobTaskRepository.findByJobCardIdOrderByIdAsc(jobCardId)) {
            VehicleServiceHistoryDTO.TaskDTO dto = new VehicleServiceHistoryDTO.TaskDTO();
            dto.setId(task.getId());
            dto.setDescription(task.getDescription());
            dto.setStatus(task.getStatus());
            dto.setLabourCost(task.getLabourCost());
            Mechanic mechanic = task.getMechanic();
            if (mechanic != null) {
                dto.setMechanicId(mechanic.getId());
                dto.setMechanicName(mechanic.getName());
            }
            tasks.add(dto);
        }
        return tasks;
    }

    /** Reuses the same wording the vehicle response already uses for display. */
    private String describe(Vehicle vehicle) {
        StringBuilder sb = new StringBuilder();
        if (vehicle.getRegistrationNo() != null && !vehicle.getRegistrationNo().isBlank()) {
            sb.append(vehicle.getRegistrationNo());
        }
        if (vehicle.getMake() != null || vehicle.getModel() != null) {
            if (sb.length() > 0) {
                sb.append(" - ");
            }
            sb.append(vehicle.getMake()).append(" ").append(vehicle.getModel());
        }
        return sb.toString();
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
        e.setYear(r.getYear());
        e.setEngineNo(r.getEngineNo());
        e.setChassisNo(r.getChassisNo());
        e.setMileage(r.getMileage());
        e.setInsuranceExpiry(r.getInsuranceExpiry());
        e.setWarrantyExpiry(r.getWarrantyExpiry());
    }

    private VehicleResponseDTO toResponse(Vehicle e) {
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
}
