package com.autoservicehub.service.impl;

import com.autoservicehub.dto.NotificationResponseDTO;
import com.autoservicehub.entity.Notification;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.NotificationRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * In-app notifications (SRS 16, 18).
 *
 * <p>The in-app channel is implemented: a notification is persisted against the
 * {@link User} it is addressed to and read back through the per-user queries
 * below. The WhatsApp / email / SMS methods stay as no-ops until a provider is
 * chosen (SRS 25, Open Questions 3).
 *
 * <p>Every read and every update is scoped to the caller, resolved from the
 * SecurityContext. A notification addressed to somebody else is reported as
 * not found rather than forbidden, so the API never confirms that another
 * user's notification ids exist.
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /** The only channel this implementation persists. */
    public static final String CHANNEL_IN_APP = "IN_APP";

    /** Reference type used for notifications raised by a due follow-up. */
    public static final String REFERENCE_FOLLOWUP = "FOLLOWUP";

    private final NotificationRepository repository;
    private final UserRepository         userRepository;

    // ── Existing provider-facing surface ──────────────────────────────────

    @Override
    @Transactional
    public void sendInApp(Long userId, String title, String message) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Notification notification = new Notification();
        notification.setUser(user);
        notification.setChannel(CHANNEL_IN_APP);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setStatus("UNREAD");
        notification.setRead(Boolean.FALSE);

        repository.save(notification);
    }

    @Override
    public void sendEmail(String toEmail, String subject, String body) {
        // TODO: integrate SMTP/email API provider
    }

    @Override
    public void sendWhatsApp(String toPhone, String templateName, Object templateData) {
        // TODO: integrate WhatsApp Business API provider
    }

    @Override
    public void sendSms(String toPhone, String message) {
        // TODO: integrate SMS provider
    }

    // ── In-app notification box ────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponseDTO> listMine(Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDescIdDesc(currentUserId(), pageable)
                         .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponseDTO> listMineUnread(Pageable pageable) {
        return repository.findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(currentUserId(), pageable)
                         .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public long countMineUnread() {
        return repository.countByUserIdAndReadFalse(currentUserId());
    }

    @Override
    @Transactional
    public NotificationResponseDTO markAsRead(Long id) {
        // Scoped by owner, so a notification belonging to another user is simply
        // not found — the caller cannot mark it, nor confirm that it exists.
        Notification notification = repository.findByIdAndUserId(id, currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));

        if (!Boolean.TRUE.equals(notification.getRead())) {
            notification.setRead(Boolean.TRUE);
            notification.setStatus("READ");
            repository.save(notification);
        }
        return toResponse(notification);
    }

    @Override
    @Transactional
    public int markAllAsRead() {
        List<Notification> unread =
                repository.findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(currentUserId());
        for (Notification notification : unread) {
            notification.setRead(Boolean.TRUE);
            notification.setStatus("READ");
        }
        repository.saveAll(unread);
        return unread.size();
    }

    // ── Used by the due-follow-up processing ──────────────────────────────

    /**
     * Raises a notification for a specific user, unless they already have an
     * unread one for the same reference.
     *
     * <p>This backs up the {@code notifiedAt} stamp on the follow-up. It stops the
     * same reminder reaching a recipient twice unread, while still allowing a
     * fresh notification once they have read the first and the follow-up has been
     * re-opened.
     *
     * @return true when a new notification was created
     */
    @Transactional
    public boolean notifyUserOnce(Long userId, String title, String message,
                                  String referenceType, Long referenceId) {
        if (referenceType != null && referenceId != null
                && repository.existsByUserIdAndReferenceTypeAndReferenceIdAndReadFalse(
                        userId, referenceType, referenceId)) {
            return false;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Notification notification = new Notification();
        notification.setUser(user);
        notification.setChannel(CHANNEL_IN_APP);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setStatus("UNREAD");
        notification.setRead(Boolean.FALSE);
        notification.setReferenceType(referenceType);
        notification.setReferenceId(referenceId);

        repository.save(notification);
        return true;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * The authenticated user behind the current call.
     *
     * <p>The JWT filter authenticates with the username as the principal, so
     * that is what is resolved back to a {@link User} row.
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new BusinessRuleException("No authenticated user for this request.");
        }
        return userRepository.findByUsernameIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + auth.getName()))
                .getId();
    }

    private NotificationResponseDTO toResponse(Notification n) {
        NotificationResponseDTO dto = new NotificationResponseDTO();
        dto.setId(n.getId());
        dto.setChannel(n.getChannel());
        dto.setTitle(n.getTitle());
        dto.setMessage(n.getMessage());
        dto.setStatus(n.getStatus());
        dto.setRead(Boolean.TRUE.equals(n.getRead()));
        dto.setReferenceType(n.getReferenceType());
        dto.setReferenceId(n.getReferenceId());
        dto.setCreatedAt(n.getCreatedAt());
        return dto;
    }
}

