package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class MechanicWorkloadResponseDTO {
    private Long mechanicId;
    private String mechanicName;
    private long workloadCount;
    private long pendingTaskCount;
    private List<AssignedJobSummaryDTO> assignedJobs;
    private List<JobTaskResponseDTO> pendingTasks;

    @Getter
    @Setter
    public static class AssignedJobSummaryDTO {
        private Long jobCardId;
        private String jobCardNumber;
        private String serviceType;
        private String status;
        private LocalDateTime assignedDate;
        private LocalDateTime estimatedDelivery;
    }
}