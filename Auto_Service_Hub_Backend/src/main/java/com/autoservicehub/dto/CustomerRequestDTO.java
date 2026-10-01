package com.autoservicehub.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
    @Size(max = 300)
    private String address;
    @Size(max = 100)
    private String city;
    @Size(max = 20)
    private String pincode;
    @Size(max = 50)
    private String loyaltyTier;
    @Size(max = 5000)
    private String notes;
    @Size(max = 5000)
    private String preferences;
    private String status;
}
