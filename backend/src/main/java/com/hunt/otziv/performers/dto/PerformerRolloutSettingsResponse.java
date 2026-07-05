package com.hunt.otziv.performers.dto;

import java.util.List;

public record PerformerRolloutSettingsResponse(
        boolean enabled,
        String cityIds,
        String productIds,
        List<Long> parsedCityIds,
        List<Long> parsedProductIds
) {
}
