package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class JobCardStatusHistoryResponseDTO {
    private Long id;
    private String fromStatus;
    private String toStatus;
    private Long changedByUserId;
    private String changedBy;
    private LocalDateTime changedAt;
}