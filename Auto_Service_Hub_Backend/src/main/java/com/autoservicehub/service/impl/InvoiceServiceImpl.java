package com.autoservicehub.service.impl;

import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.service.InvoiceService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Transactional
public class InvoiceServiceImpl implements InvoiceService {

    private final InvoiceRepository repository;
    private final JobCardRepository jobCardRepository;
    private final ServiceAdvisorAccessService advisorAccessService;

    @Override
    public InvoiceResponseDTO create(InvoiceRequestDTO request) {
        Invoice entity = new Invoice();
        mapToEntity(request, entity);
        advisorAccessService.assertCanAccess(entity);
        entity.setInvoiceDate(LocalDate.now());
        entity.setStatus(request.getStatus() != null ? request.getStatus() : "PENDING");
        return toResponse(repository.save(entity));
    }

    @Override
    public InvoiceResponseDTO update(Long id, InvoiceRequestDTO request) {
        Invoice existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));
        advisorAccessService.assertCanAccess(existing);
        mapToEntity(request, existing);
        advisorAccessService.assertCanAccess(existing);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponseDTO getById(Long id) {
        Invoice invoice = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));
        advisorAccessService.assertCanAccess(invoice);
        return toResponse(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvoiceResponseDTO> list(Pageable pageable) {
        if (advisorAccessService.isAdvisorUser()) {
            return repository.findVisibleToAdvisor(advisorAccessService.currentAdvisor().getId(), pageable).map(this::toResponse);
        }
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        Invoice invoice = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));
        advisorAccessService.assertCanAccess(invoice);
        repository.delete(invoice);
    }

    private void mapToEntity(InvoiceRequestDTO r, Invoice e) {
        JobCard jobCard = jobCardRepository.findById(r.getJobCardId())
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + r.getJobCardId()));
        e.setJobCard(jobCard);
        e.setSubtotal(r.getSubtotal());
        e.setDiscount(r.getDiscount());
        e.setGst(r.getGst());
        e.setTotal(r.getTotal());
        if (r.getStatus() != null) e.setStatus(r.getStatus());
        if (r.getInvoiceDate() != null) e.setInvoiceDate(r.getInvoiceDate());
    }

    private InvoiceResponseDTO toResponse(Invoice e) {
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
}
