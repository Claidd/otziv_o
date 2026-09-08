package com.hunt.otziv.l_lead.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Immutable, signed business identity. Transport JWT ids and timestamps are separate. */
public record LeadCommandIdentity(
        @Min(1) int protocolVersion,
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String sourceId,
        @Min(1) long entityId,
        @Min(1) long entityVersion,
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String operationId,
        @NotBlank @Pattern(regexp = "SYNC|UPDATE|IMPORT") String kind) {
}
