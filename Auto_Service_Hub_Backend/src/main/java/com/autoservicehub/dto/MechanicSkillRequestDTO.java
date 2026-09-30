package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MechanicSkillRequestDTO {
    @NotBlank
    @Size(max = 100)
    private String skillName;

    @NotBlank
    @Size(max = 50)
    private String level;
}