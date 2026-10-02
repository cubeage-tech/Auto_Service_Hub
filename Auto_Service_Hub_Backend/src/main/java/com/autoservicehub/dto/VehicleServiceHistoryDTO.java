package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A vehicle's service history (SRS FR-VEH-6, "provide vehicle-wise service
 * history and downloadable records").
 *
 * <p>Assembled from job cards, the tasks on them and the invoices raised against
 * them — all rows that already exist. No separate history table is created,
 * because a copy of these rows could only ever drift from the records they are
 * copied from.
 *
 * <p>Each entry reports what was done and what it cost, and explicitly carries
 * {@code invoiceTotal} as null when the job was never invoiced: a job with no
 * invoice has no billed total, and reporting zero would read as "billed nothing"
 * rather than "not yet billed".
 */
@Getter
@Setter
public class VehicleServiceHistoryDTO {

    private Long vehicleId;
    private String vehicleInfo;

    /** Job cards for this vehicle, newest first. */
    private List<ServiceVisitDTO> visits = List.of();

    /** Number of visits in {@link #visits}. */
    private int totalVisits;

    /** Sum of the invoiced totals across the visits that have one. */
    private BigDecimal totalInvoiced = BigDecimal.ZERO;

    /** One job card and the work recorded against it. */
    @Getter
    @Setter
    public static class ServiceVisitDTO {

        private Long jobCardId;
        private String jobCardNumber;
        private String serviceType;
        private String status;
        private LocalDateTime assignedDate;
        private LocalDateTime completedDate;

        /** The repair/labour tasks recorded on this job. */
        private List<TaskDTO> tasks = List.of();

        /** Invoices raised for this job; empty when it has not been billed yet. */
        private List<InvoiceSummaryDTO> invoices = List.of();

        /** Total billed across this job's invoices, or null when there are none. */
        private BigDecimal invoiceTotal;
    }

    /** A repair/labour task as it appears in the history. */
    @Getter
    @Setter
    public static class TaskDTO {
        private Long id;
        private String description;
        private String status;
        private BigDecimal labourCost;
        private Long mechanicId;
        private String mechanicName;
    }

    /** The invoice identity and amount, without re-listing its line items. */
    @Getter
    @Setter
    public static class InvoiceSummaryDTO {
        private Long id;
        private String status;
        private BigDecimal total;
        private LocalDate invoiceDate;
    }
}