package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkNoteRequestDTO {
    @NotBlank
    private String content;
}