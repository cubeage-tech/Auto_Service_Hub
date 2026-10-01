package com.autoservicehub.service.impl;

import com.autoservicehub.dto.NotificationResponseDTO;
import com.autoservicehub.entity.Notification;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.NotificationRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.EmailService;
import com.autoservicehub.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Persists in-app notifications and delegates email to the configured SMTP sender. */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponseDTO> getMine() {
        Long recipientId = currentUser().getId();
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId)
                .stream().map(this::toResponse).toList();
    }

    @Override
    public void markMineRead(Long notificationId) {
        Notification notification = notificationRepository.findByIdAndRecipientId(notificationId, currentUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + notificationId));
        notification.setRead(true);
        notificationRepository.save(notification);
    }

    @Override
    public void sendInApp(Long userId, String title, String message) {
        User recipient = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification recipient not found: " + userId));
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setChannel("IN_APP");
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setStatus("SENT");
        notification.setRead(false);
        notificationRepository.save(notification);
    }

    @Override
    public void sendEmail(String toEmail, String subject, String body) {
        emailService.sendEmail(toEmail, subject, body);
    }

    @Override
    public void sendWhatsApp(String toPhone, String templateName, Object templateData) {
        throw new BusinessRuleException("WhatsApp notifications are not configured.");
    }

    @Override
    public void sendSms(String toPhone, String message) {
        throw new BusinessRuleException("SMS notifications are not configured.");
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication is required.");
        }
        return userRepository.findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found."));
    }

    private NotificationResponseDTO toResponse(Notification notification) {
        NotificationResponseDTO response = new NotificationResponseDTO();
        response.setId(notification.getId());
        response.setChannel(notification.getChannel());
        response.setTitle(notification.getTitle());
        response.setMessage(notification.getMessage());
        response.setStatus(notification.getStatus());
        response.setRead(notification.getRead());
        response.setCreatedAt(notification.getCreatedAt());
        return response;
    }
}
