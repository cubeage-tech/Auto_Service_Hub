package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Customer 360 composite view (SRS FR-CRM-7).
 * Single endpoint returns the complete picture of a customer:
 * profile + vehicles + appointments + jobs + invoices + feedback + communications.
 */
@Getter
@Setter
public class Customer360DTO {

    // ── Profile ──────────────────────────────────────────────
    private Long id;
    private String name;
    private String phone;
    private String email;
    private String address;
    private String status;
    private LocalDateTime createdAt;

    // ── Vehicles (FR-CRM-2) ───────────────────────────────────
    private List<VehicleResponseDTO> vehicles;

    // ── Service history (FR-CRM-3) – newest first ────────────
    private List<ServiceHistoryDTO> serviceHistory;

    // ── Upcoming / recent appointments ───────────────────────
    private List<AppointmentResponseDTO> appointments;

    // ── Invoices (newest first) ───────────────────────────────
    private List<InvoiceResponseDTO> invoices;

    // ── Feedback ──────────────────────────────────────────────
    private List<FeedbackResponseDTO> feedback;

    // ── Communication log (newest first) ─────────────────────
    private List<CommunicationResponseDTO> communications;

    // ── Summary stats ─────────────────────────────────────────
    /** Total number of completed job cards. */
    private long totalVisits;

    /** Sum of all invoice totals for this customer. */
    private BigDecimal totalSpend;

    /** Date of the most recent completed job card. */
    private LocalDateTime lastServiceDate;

    /** Number of invoices with status != PAID. */
    private long openInvoicesCount;

    /** Sum of totals for unpaid invoices. */
    private BigDecimal openInvoicesAmount;

    /** Average feedback rating across all submissions (null if none). */
    private Double averageRating;
}
