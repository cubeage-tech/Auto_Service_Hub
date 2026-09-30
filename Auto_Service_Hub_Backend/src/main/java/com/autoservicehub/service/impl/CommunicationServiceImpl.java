package com.autoservicehub.service.impl;

import com.autoservicehub.dto.CommunicationRequestDTO;
import com.autoservicehub.dto.CommunicationResponseDTO;
import com.autoservicehub.entity.Communication;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.CommunicationRepository;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.service.CommunicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Customer communication log (SRS FR-CRM-4).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CommunicationServiceImpl implements CommunicationService {

    private static final Set<String> VALID_CHANNELS   = Set.of("CALL", "WHATSAPP", "EMAIL", "REMINDER");
    private static final Set<String> VALID_DIRECTIONS  = Set.of("INBOUND", "OUTBOUND");

    private final CommunicationRepository repository;
    private final CustomerRepository      customerRepository;
    private final JobCardRepository       jobCardRepository;

    @Override
    public CommunicationResponseDTO create(CommunicationRequestDTO request) {
        validateChannelAndDirection(request.getChannel(), request.getDirection());
        Communication entity = new Communication();
        mapToEntity(request, entity);
        return toResponse(repository.save(entity));
    }

    @Override
    public CommunicationResponseDTO update(Long id, CommunicationRequestDTO request) {
        validateChannelAndDirection(request.getChannel(), request.getDirection());
        Communication existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Communication not found: " + id));
        mapToEntity(request, existing);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public CommunicationResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Communication not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CommunicationResponseDTO> listByCustomer(Long customerId, Pageable pageable) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found: " + customerId);
        }
        return repository.findByCustomerIdOrderBySentAtDesc(customerId, pageable)
                         .map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Communication not found: " + id);
        }
        repository.deleteById(id);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void validateChannelAndDirection(String channel, String direction) {
        if (channel != null && !VALID_CHANNELS.contains(channel.toUpperCase())) {
            throw new BusinessRuleException(
                    "Invalid channel '" + channel + "'. Allowed: CALL, WHATSAPP, EMAIL, REMINDER.");
        }
        if (direction != null && !VALID_DIRECTIONS.contains(direction.toUpperCase())) {
            throw new BusinessRuleException(
                    "Invalid direction '" + direction + "'. Allowed: INBOUND, OUTBOUND.");
        }
    }

    private void mapToEntity(CommunicationRequestDTO r, Communication e) {
        Customer customer = customerRepository.findById(r.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + r.getCustomerId()));
        e.setCustomer(customer);

        if (r.getJobCardId() != null) {
            JobCard jobCard = jobCardRepository.findById(r.getJobCardId())
                    .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + r.getJobCardId()));
            e.setJobCard(jobCard);
        } else {
            e.setJobCard(null);
        }

        e.setChannel(r.getChannel() != null ? r.getChannel().toUpperCase() : null);
        e.setDirection(r.getDirection() != null ? r.getDirection().toUpperCase() : null);
        e.setSubject(r.getSubject());
        e.setMessage(r.getMessage());
        e.setSentAt(r.getSentAt() != null ? r.getSentAt() : LocalDateTime.now());
    }

    private CommunicationResponseDTO toResponse(Communication e) {
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
}
