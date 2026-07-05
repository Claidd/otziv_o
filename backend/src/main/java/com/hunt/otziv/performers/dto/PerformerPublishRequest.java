package com.hunt.otziv.performers.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PerformerPublishRequest {
    @NotBlank
    private String finalText;

    private String publicationUrl;

    private String comment;
}
