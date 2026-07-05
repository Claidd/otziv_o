package com.hunt.otziv.performers.dto;

public record PerformerRolloutSettingsRequest(
        Boolean enabled,
        String cityIds,
        String productIds
) {
}
