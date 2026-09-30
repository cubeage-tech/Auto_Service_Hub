package com.autoservicehub.service.impl;

import com.autoservicehub.dto.FeedbackRequestDTO;
import com.autoservicehub.dto.FeedbackResponseDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Feedback;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.FeedbackRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Post-service customer feedback and ratings (SRS FR-CRM-5).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class FeedbackServiceImpl implements FeedbackService {

    private final FeedbackRepository  repository;
    private final CustomerRepository  customerRepository;
    private final JobCardRepository   jobCardRepository;

    @Override
    public FeedbackResponseDTO create(FeedbackRequestDTO request) {
        // Guard: job card must be DELIVERED before feedback can be submitted
        JobCard jobCard = jobCardRepository.findById(request.getJobCardId())
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + request.getJobCardId()));
        if (!"DELIVERED".equalsIgnoreCase(jobCard.getStatus())) {
            throw new BusinessRuleException(
                    "Feedback can only be submitted for completed (DELIVERED) job cards.");
        }

        Feedback entity = new Feedback();
        mapToEntity(request, entity, jobCard);
        return toResponse(repository.save(entity));
    }

    @Override
    public FeedbackResponseDTO update(Long id, FeedbackRequestDTO request) {
        Feedback existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Feedback not found: " + id));
        JobCard jobCard = jobCardRepository.findById(request.getJobCardId())
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + request.getJobCardId()));
        mapToEntity(request, existing, jobCard);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Feedback not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FeedbackResponseDTO> listByCustomer(Long customerId, Pageable pageable) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found: " + customerId);
        }
        return repository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
                         .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FeedbackResponseDTO> listByJobCard(Long jobCardId, Pageable pageable) {
        if (!jobCardRepository.existsById(jobCardId)) {
            throw new ResourceNotFoundException("JobCard not found: " + jobCardId);
        }
        return repository.findByJobCardId(jobCardId)
                .map(f -> {
                    List<FeedbackResponseDTO> list = List.of(toResponse(f));
                    return (Page<FeedbackResponseDTO>) new PageImpl<>(list, pageable, 1L);
                })
                .orElseGet(() -> Page.empty(pageable));
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Feedback not found: " + id);
        }
        repository.deleteById(id);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void mapToEntity(FeedbackRequestDTO r, Feedback e, JobCard jobCard) {
        Customer customer = customerRepository.findById(r.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + r.getCustomerId()));
        e.setCustomer(customer);
        e.setJobCard(jobCard);
        e.setRating(r.getRating());
        e.setComments(r.getComments());
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

    FeedbackResponseDTO toResponse(Feedback e) {
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
}
