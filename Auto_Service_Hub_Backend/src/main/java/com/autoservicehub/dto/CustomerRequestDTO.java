package com.autoservicehub.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomerRequestDTO {
    @NotBlank
    private String name;
    @NotBlank
    private String phone;
    @Email
    private String email;
    private String address;
    private String status;

    @Pattern(
            regexp = "(?i)^(Bronze|Silver|Gold|Platinum)$",
            message = "Loyalty tier must be Bronze, Silver, Gold, or Platinum")
    private String loyaltyTier;

    @Size(max = 2000)
    private String notes;
}
