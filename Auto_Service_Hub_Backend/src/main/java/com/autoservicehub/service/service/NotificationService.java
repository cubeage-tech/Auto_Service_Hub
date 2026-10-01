package com.autoservicehub.service;

import com.autoservicehub.dto.NotificationResponseDTO;
import java.util.List;

/**
 * In-app, email, WhatsApp and SMS notifications (SRS 16, 18). Provider calls
 * (WhatsApp/Email/SMS) should be implemented behind this interface so the
 * rest of the app is provider-independent and can fall back gracefully (SRS 23).
 */
public interface NotificationService {
    List<NotificationResponseDTO> getMine();
    void markMineRead(Long notificationId);
    void sendInApp(Long userId, String title, String message);
    void sendEmail(String toEmail, String subject, String body);
    void sendWhatsApp(String toPhone, String templateName, Object templateData);
    void sendSms(String toPhone, String message);
}
