package com.autoservicehub.service.impl;

import com.autoservicehub.dto.EstimateRequestDTO;
import com.autoservicehub.dto.EstimateResponseDTO;
import com.autoservicehub.dto.EstimateItemResponseDTO;
import com.autoservicehub.entity.Estimate;
import com.autoservicehub.entity.EstimateItem;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.EstimateRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.service.EstimateService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Billing - Estimates (SRS 4.9)
 * Business rules to enforce here per SRS section 12 (e.g. server-side total
 * recalculation, stock limits, audit trail) before persisting.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EstimateServiceImpl implements EstimateService {

    private final EstimateRepository repository;
    private final JobCardRepository jobCardRepository;
    private final ServiceAdvisorAccessService advisorAccessService;

    @Override
    public EstimateResponseDTO create(EstimateRequestDTO request) {
        Estimate entity = new Estimate();
        applyRequest(request, entity);
        advisorAccessService.assertCanAccess(entity);
        entity.setStatus(request.getStatus() != null ? request.getStatus() : "DRAFT");
        Estimate saved = repository.save(entity);
        return toResponse(saved);
    }

    @Override
    public EstimateResponseDTO update(Long id, EstimateRequestDTO request) {
        Estimate existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate not found: " + id));
        advisorAccessService.assertCanAccess(existing);
        if (advisorAccessService.isAdvisorUser() && request.getJobCardId() != null
                && (existing.getJobCard() == null || !request.getJobCardId().equals(existing.getJobCard().getId()))) {
            throw new org.springframework.security.access.AccessDeniedException("Service Advisors cannot move an estimate to another job card.");
        }
        applyRequest(request, existing);
        advisorAccessService.assertCanAccess(existing);
        existing.setStatus(request.getStatus() != null ? request.getStatus() : existing.getStatus());
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public EstimateResponseDTO getById(Long id) {
        Estimate found = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate not found: " + id));
        advisorAccessService.assertCanAccess(found);
        return toResponse(found);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EstimateResponseDTO> list(Pageable pageable) {
        if (advisorAccessService.isAdvisorUser()) {
            return repository.findVisibleToAdvisor(advisorAccessService.currentAdvisor().getId(), pageable).map(this::toResponse);
        }
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        Estimate estimate = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Estimate not found: " + id));
        advisorAccessService.assertCanAccess(estimate);
        repository.delete(estimate);
    }

    private void applyRequest(EstimateRequestDTO request, Estimate estimate) {
        if (request.getJobCardId() != null) {
            JobCard jobCard = jobCardRepository.findById(request.getJobCardId())
                    .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + request.getJobCardId()));
            estimate.setJobCard(jobCard);
        }
        if (request.getItems() != null) {
            if (request.getItems().isEmpty()) {
                throw new BusinessRuleException("At least one estimate item is required when items are supplied.");
            }
            estimate.getItems().clear();
            for (var itemRequest : request.getItems()) {
                if (itemRequest.getDescription() == null || itemRequest.getDescription().isBlank()
                        || itemRequest.getQuantity() == null || itemRequest.getUnitPrice() == null
                        || itemRequest.getQuantity() <= 0 || itemRequest.getUnitPrice().signum() < 0) {
                    throw new BusinessRuleException("Estimate items require a description, positive quantity, and non-negative unit price.");
                }
                EstimateItem item = new EstimateItem();
                item.setEstimate(estimate);
                item.setDescription(itemRequest.getDescription().trim());
                item.setQuantity(itemRequest.getQuantity());
                item.setUnitPrice(itemRequest.getUnitPrice());
                estimate.getItems().add(item);
            }
        }

        BigDecimal subtotal;
        if (!estimate.getItems().isEmpty()) {
            subtotal = estimate.getItems().stream()
                    .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } else if (request.getSubtotal() != null) {
            subtotal = request.getSubtotal();
        } else {
            throw new BusinessRuleException("An estimate subtotal or at least one estimate item is required.");
        }

        BigDecimal discount = request.getDiscount() != null ? request.getDiscount()
                : estimate.getDiscount() != null ? estimate.getDiscount() : BigDecimal.ZERO;
        BigDecimal tax = request.getTax() != null ? request.getTax()
                : estimate.getTax() != null ? estimate.getTax() : BigDecimal.ZERO;
        if (subtotal.signum() < 0 || discount.signum() < 0 || tax.signum() < 0 || discount.compareTo(subtotal) > 0) {
            throw new BusinessRuleException("Estimate subtotal, discount, and tax are invalid.");
        }

        estimate.setSubtotal(subtotal);
        estimate.setDiscount(discount);
        estimate.setTax(tax);
        estimate.setTotal(subtotal.subtract(discount).add(tax));
    }

    private EstimateResponseDTO toResponse(Estimate entity) {
        EstimateResponseDTO dto = new EstimateResponseDTO();
        dto.setId(entity.getId());
        dto.setJobCardId(entity.getJobCard() == null ? null : entity.getJobCard().getId());
        dto.setSubtotal(entity.getSubtotal());
        dto.setDiscount(entity.getDiscount());
        dto.setTax(entity.getTax());
        dto.setTotal(entity.getTotal());
        dto.setStatus(entity.getStatus());
        dto.setItems(entity.getItems().stream().map(item -> {
            EstimateItemResponseDTO itemDTO = new EstimateItemResponseDTO();
            itemDTO.setId(item.getId());
            itemDTO.setDescription(item.getDescription());
            itemDTO.setQuantity(item.getQuantity());
            itemDTO.setUnitPrice(item.getUnitPrice());
            itemDTO.setLineTotal(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            return itemDTO;
        }).toList());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
