package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for moving a task to a new status (FR-JOB-5).
 *
 * <p>Its own small payload rather than a full {@link JobTaskRequestDTO},
 * because changing a task's status must not require re-sending its
 * description, labour cost or mechanic — a mechanic marking their own work
 * complete should not be able to silently restate what the task costs.
 */
@Getter
@Setter
public class JobTaskStatusRequestDTO {

    @NotBlank(message = "status is required")
    private String status;
}