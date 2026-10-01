package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
public class CustomerResponseDTO {
    private Long id;
    private String name;
    private String phone;
    private String email;
    private String address;
    private String city;
    private String pincode;
    private String loyaltyTier;
    private String notes;
    private String preferences;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
