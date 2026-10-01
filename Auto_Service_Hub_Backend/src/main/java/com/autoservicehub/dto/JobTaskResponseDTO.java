package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class JobTaskResponseDTO {
    private Long id;
    private Long jobCardId;
    private String jobCardNumber;
    private Long mechanicId;
    private String description;
    private String status;
    private BigDecimal labourCost;
    private LocalDateTime completedAt;
    private Long completedByUserId;
}