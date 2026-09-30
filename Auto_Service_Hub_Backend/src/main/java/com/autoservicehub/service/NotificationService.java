package com.autoservicehub.service;

import com.autoservicehub.dto.NotificationResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * In-app notifications (SRS 16, 18).
 *
 * <p>Provider calls (WhatsApp / email / SMS) sit behind this interface so the
 * rest of the app stays provider-independent and can fall back gracefully
 * (SRS 23). Only the in-app channel is implemented; no provider is integrated
 * yet.
 *
 * <p>Every read or update is scoped to the current user, so one user can never
 * see or mark read another user's notifications.
 */
public interface NotificationService {

    void sendInApp(Long userId, String title, String message);

    void sendEmail(String toEmail, String subject, String body);

    void sendWhatsApp(String toPhone, String templateName, Object templateData);

    void sendSms(String toPhone, String message);

    // ── In-app notification box, always scoped to one user ────────────────

    /** The current user's notifications, newest first. */
    Page<NotificationResponseDTO> listMine(Pageable pageable);

    /** The current user's unread notifications, newest first. */
    Page<NotificationResponseDTO> listMineUnread(Pageable pageable);

    /** How many notifications the current user has not read. */
    long countMineUnread();

    /**
     * Marks one of the current user's notifications as read.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException if it does
     *         not exist, or belongs to somebody else
     */
    NotificationResponseDTO markAsRead(Long id);

    /**
     * Marks every one of the current user's notifications as read and returns
     * how many were changed. Idempotent: a second call changes nothing.
     */
    int markAllAsRead();
}

