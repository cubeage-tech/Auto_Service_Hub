package com.autoservicehub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * Dashboard KPI card data (SRS 4.11 FR-REP-1/2, 10 Dashboard screen).
 */
@Getter
@Setter
@AllArgsConstructor
public class DashboardSummaryDTO {
    private long todaysJobs;
    private long completedJobs;
    private long pendingJobs;
    private BigDecimal revenue;
    private long lowStockParts;
    private long upcomingAppointments;
    private long pendingInvoices;
    private long activeAssignedJobs;
}
