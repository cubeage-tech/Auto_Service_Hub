package com.autoservicehub.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for creating or updating a supplier (FR-INV-2).
 *
 * <p>{@code name} is the only required field: a supplier with no identity cannot
 * be told apart on a purchase order. Contact details are optional, but if an
 * email is given it must be a well-formed one — an unparseable address is worse
 * than none, because it silently fails to deliver.
 */
@Getter
@Setter
public class SupplierRequestDTO {

    @NotBlank(message = "name is required")
    private String name;

    private String phone;

    @Email(message = "email must be a valid address")
    private String email;

    private String address;
}