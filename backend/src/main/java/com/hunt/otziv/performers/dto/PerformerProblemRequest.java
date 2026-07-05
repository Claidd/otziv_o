package com.hunt.otziv.performers.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PerformerProblemRequest {
    @NotBlank
    private String comment;
}
