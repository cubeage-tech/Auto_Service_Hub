package com.autoservicehub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
public class MechanicPerformanceReportDTO {
    private LocalDate from;
    private LocalDate to;
    private Long mechanicId;
    private String mechanicName;
    private long completedJobs;
    private BigDecimal totalRevenue;
    private BigDecimal averageRevenuePerJob;
    private BigDecimal averageTurnaroundHours;
    private long activeAssignedJobs;
    private Double averageFeedbackRating;
    private long feedbackCount;
}
