package com.autoservicehub.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MechanicAssignmentConfirmationRequestDTO {

    @NotNull
    @Positive
    private Long jobCardId;

    @NotEmpty
    private List<@NotNull @Positive Long> mechanicIds;
}