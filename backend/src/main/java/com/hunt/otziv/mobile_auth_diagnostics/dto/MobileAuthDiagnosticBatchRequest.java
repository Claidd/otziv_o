package com.hunt.otziv.mobile_auth_diagnostics.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record MobileAuthDiagnosticBatchRequest(
        @NotBlank @Size(max = 64) String batchId,
        @NotBlank @Size(max = 128) String installationId,
        @NotEmpty @Size(max = 80) List<@Valid MobileAuthDiagnosticEventRequest> events
) {
}
