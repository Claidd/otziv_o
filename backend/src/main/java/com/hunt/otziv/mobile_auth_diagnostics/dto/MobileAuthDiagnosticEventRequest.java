package com.hunt.otziv.mobile_auth_diagnostics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public record MobileAuthDiagnosticEventRequest(
        @NotBlank @Size(max = 64) String eventId,
        @NotNull Instant occurredAt,
        @NotBlank @Pattern(regexp = "[a-z0-9_.-]{1,64}") String type,
        @NotBlank @Size(max = 64) String runId,
        @NotBlank @Size(max = 32) String appVersion,
        @NotBlank @Size(max = 32) String appBuild,
        @NotBlank @Size(max = 24) String networkType,
        boolean connected,
        @NotNull @Size(max = 20) Map<@NotBlank @Size(max = 48) String, @Size(max = 200) String> details
) {
}
