package com.autoservicehub.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for creating a user account (SRS 8.2 High-Level Entities;
 * SRS 19 Security Requirements).
 *
 * <p>All fields except {@code status} are required. An account with no {@code role}
 * could not be authorised, and one with no {@code password} could never sign in, so
 * accepting either as null would only create a broken row that has to be repaired by
 * hand afterwards.
 *
 * <p>{@code password} is write-only: it is hashed before it reaches the database and is
 * never echoed back — {@link UserResponseDTO} carries no credential field at all.
 * There is deliberately no {@code confirmPassword} here: re-typing a password is a form
 * convenience, and the value never leaves the browser's own comparison.
 *
 * <p>{@code status} is optional and defaults to {@code Active} when omitted, mirroring
 * how the user-management screen pre-selects it. {@code role} is intentionally not
 * whitelisted with a regex: the authoritative role names live in the {@code roles}
 * table, so the service resolves the supplied name against that table and rejects
 * anything unknown.
 */
@Getter
@Setter
public class UserRequestDTO {

    @NotBlank(message = "fullName is required")
    @Size(max = 100, message = "fullName must not exceed 100 characters")
    private String fullName;

    @NotBlank(message = "username is required")
    @Size(min = 3, max = 50, message = "username must be between 3 and 50 characters")
    private String username;

    @NotBlank(message = "email is required")
    @Email(message = "Please provide a valid email address")
    @Size(max = 150, message = "email must not exceed 150 characters")
    private String email;

    @NotBlank(message = "phone is required")
    @Size(max = 30, message = "phone must not exceed 30 characters")
    private String phone;

    /** Role name as stored in the {@code roles} table, e.g. {@code SERVICE_ADVISOR}. */
    @NotBlank(message = "role is required")
    @Size(max = 50, message = "role must not exceed 50 characters")
    private String role;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    /** Active or Inactive. Defaults to {@code Active} on create when omitted. */
    @Pattern(
            regexp = "(?i)^(Active|Inactive)$",
            message = "status must be Active or Inactive")
    private String status;
}
