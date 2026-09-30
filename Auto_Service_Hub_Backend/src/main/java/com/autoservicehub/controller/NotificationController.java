package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.NotificationResponseDTO;
import com.autoservicehub.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * In-app notifications (SRS 16, 18).
 * Base path: /api/v1/notifications
 *
 * <p>Every endpoint operates on the <strong>current user's own</strong>
 * notifications — there is no path parameter identifying a user, so one user
 * cannot read or acknowledge another user's notifications. Marking a
 * notification that is not the caller's is reported as 404, which also avoids
 * confirming that the id exists at all.
 *
 * <p>Any authenticated role may use these: a notification box is personal, not
 * a privileged view, so the read endpoints are limited only by authentication.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    /**
     * The current user's notifications, newest first.
     * GET /api/v1/notifications?page=0&size=20
     */
    @GetMapping
    public ApiResponse<Page<NotificationResponseDTO>> listMine(Pageable pageable) {
        return ApiResponse.ok(service.listMine(pageable));
    }

    /**
     * The current user's unread notifications, newest first.
     * GET /api/v1/notifications/unread?page=0&size=20
     */
    @GetMapping("/unread")
    public ApiResponse<Page<NotificationResponseDTO>> listMineUnread(Pageable pageable) {
        return ApiResponse.ok(service.listMineUnread(pageable));
    }

    /**
     * How many notifications the current user has not read.
     * GET /api/v1/notifications/unread-count
     */
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount() {
        return ApiResponse.ok(Map.of("unreadCount", service.countMineUnread()));
    }

    /**
     * Marks one of the current user's notifications as read.
     * PUT /api/v1/notifications/{id}/read
     */
    @PutMapping("/{id}/read")
    public ApiResponse<NotificationResponseDTO> markAsRead(@PathVariable Long id) {
        return ApiResponse.ok("Marked as read", service.markAsRead(id));
    }

    /**
     * Marks every one of the current user's notifications as read.
     * PUT /api/v1/notifications/read-all
     *
     * <p>Returns how many were actually changed, so a repeated call reports 0.
     */
    @PutMapping("/read-all")
    public ApiResponse<Map<String, Integer>> markAllAsRead() {
        int updated = service.markAllAsRead();
        return ApiResponse.ok("Marked as read", Map.of("updated", updated));
    }
}
