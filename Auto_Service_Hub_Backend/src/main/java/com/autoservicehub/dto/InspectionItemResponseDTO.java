package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InspectionItemResponseDTO {
    private Long id;
    private String checklistItem;
    private String finding;
    private String photoUrl;
}