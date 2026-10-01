package com.autoservicehub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
public class ServiceAdvisorOperationsReportDTO {
    private LocalDate from;
    private LocalDate to;
    private Long advisorId;
    private String advisorName;
    private long assignedAppointments;
    private long activeJobCards;
    private long completedJobCards;
    private long pendingInvoices;
}