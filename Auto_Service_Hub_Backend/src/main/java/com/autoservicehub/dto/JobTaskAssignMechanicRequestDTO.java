package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for allocating a mechanic to a task (FR-JOB-2, FR-JOB-3).
 *
 * <p>{@code mechanicId} is intentionally NOT {@code @NotNull}: a null id is how
 * a task is un-assigned. Requiring a value would make clearing an allocation
 * impossible through the API, and an un-assigned task is a legitimate state for
 * work that has not been allocated yet.
 */
@Getter
@Setter
public class JobTaskAssignMechanicRequestDTO {

    /** The mechanic to allocate, or null to clear the current allocation. */
    private Long mechanicId;
}