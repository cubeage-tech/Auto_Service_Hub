package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * Outbound view of a user account (SRS 8.2 High-Level Entities).
 *
 * <p>Safe projection only: {@code users.password_hash} is deliberately absent, so this
 * DTO can be returned to any authorised caller without leaking credentials. It is also
 * the reason the response is a hand-mapped DTO rather than the entity itself.
 *
 * <p>{@code role} is the stored role name from the {@code roles} table (e.g.
 * {@code SERVICE_ADVISOR}), not the display label rendered in the UI. {@code active}
 * mirrors {@code users.active}; the same state is expressed as {@code Active} /
 * {@code Inactive} on the way in.
 */
@Getter
@Setter
public class UserResponseDTO {

    private Long id;

    private String fullName;

    private String username;

    private String email;

    private String phone;

    private String role;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
