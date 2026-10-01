package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class NotificationResponseDTO {
    private Long id;
    private String channel;
    private String title;
    private String message;
    private String status;
    private Boolean read;
    private LocalDateTime createdAt;
}