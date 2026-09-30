package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MechanicSkillResponseDTO {
    private Long id;
    private Long mechanicId;
    private String skillName;
    private String level;
}