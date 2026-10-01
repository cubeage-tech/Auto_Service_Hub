package com.autoservicehub.service.impl;

import com.autoservicehub.dto.FollowupRequestDTO;
import com.autoservicehub.dto.FollowupResponseDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Followup;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.FollowupRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.service.FollowupService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class FollowupServiceImpl implements FollowupService {

    private final FollowupRepository repository;
    private final CustomerRepository customerRepository;
    private final JobCardRepository jobCardRepository;
    private final ServiceAdvisorAccessService advisorAccessService;

    @Override
    public FollowupResponseDTO create(FollowupRequestDTO request) {
        Followup entity = new Followup();
        mapToEntity(request, entity);
        advisorAccessService.assertCanAccess(entity);
        return toResponse(repository.save(entity));
    }

    @Override
    public FollowupResponseDTO update(Long id, FollowupRequestDTO request) {
        Followup existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Followup not found: " + id));
        advisorAccessService.assertCanAccess(existing);
        if (advisorAccessService.isAdvisorUser() && request.getJobCardId() != null
                && (existing.getJobCard() == null || !request.getJobCardId().equals(existing.getJobCard().getId()))) {
            throw new org.springframework.security.access.AccessDeniedException("Service Advisors cannot move a follow-up to another job card.");
        }
        mapToEntity(request, existing);
        advisorAccessService.assertCanAccess(existing);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public FollowupResponseDTO getById(Long id) {
        Followup followup = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Followup not found: " + id));
        advisorAccessService.assertCanAccess(followup);
        return toResponse(followup);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FollowupResponseDTO> list(Pageable pageable) {
        if (advisorAccessService.isAdvisorUser()) {
            return repository.findVisibleToAdvisor(advisorAccessService.currentAdvisor().getId(), pageable).map(this::toResponse);
        }
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        Followup followup = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Followup not found: " + id));
        advisorAccessService.assertCanAccess(followup);
        repository.delete(followup);
    }

    private void mapToEntity(FollowupRequestDTO r, Followup e) {
        Customer customer = customerRepository.findById(r.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + r.getCustomerId()));
        e.setCustomer(customer);
        if (r.getJobCardId() != null) {
            JobCard jobCard = jobCardRepository.findById(r.getJobCardId())
                    .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + r.getJobCardId()));
            e.setJobCard(jobCard);
        }
        if (e.getJobCard() != null && e.getJobCard().getCustomer() != null
                && !customer.getId().equals(e.getJobCard().getCustomer().getId())) {
            throw new BusinessRuleException("Follow-up customer must match the linked job card customer.");
        }
        e.setDueDate(r.getDueDate());
        e.setReason(r.getReason());
        e.setStatus(r.getStatus() != null ? r.getStatus() : "PENDING");
    }

    private FollowupResponseDTO toResponse(Followup e) {
        FollowupResponseDTO dto = new FollowupResponseDTO();
        dto.setId(e.getId());
        if (e.getCustomer() != null) {
            dto.setCustomerId(e.getCustomer().getId());
            dto.setCustomerName(e.getCustomer().getName());
            dto.setCustomerPhone(e.getCustomer().getPhone());
        }
        if (e.getJobCard() != null) {
            dto.setJobCardId(e.getJobCard().getId());
        }
        dto.setDueDate(e.getDueDate());
        dto.setReason(e.getReason());
        dto.setStatus(e.getStatus());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
