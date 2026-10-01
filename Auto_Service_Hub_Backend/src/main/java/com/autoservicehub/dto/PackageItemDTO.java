package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageItemDTO {
    private Long id;

    @NotBlank
    @Size(max = 255)
    private String itemName;

    @NotBlank
    @Size(max = 50)
    private String itemType;

    private Long partId;
}