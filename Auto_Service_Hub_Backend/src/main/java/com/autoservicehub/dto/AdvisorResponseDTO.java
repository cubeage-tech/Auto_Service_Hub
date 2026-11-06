package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * A service advisor eligible to be assigned to an appointment.
 *
 * <p>Deliberately minimal: only what the appointment form needs to render its
 * picker. No credentials, contact details, salary or role metadata are exposed
 * here, so this DTO is safe to return to any caller who may book an
 * appointment — unlike the broader user/mechanic payloads.
 */
@Getter
@Setter
public class AdvisorResponseDTO {
    private Long id;
    private String name;
}