package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InspectionItemRequestDTO {
    @NotBlank
    @Size(max = 255)
    private String checklistItem;

    @Size(max = 4000)
    private String finding;

    @Size(max = 2048)
    private String photoUrl;
}