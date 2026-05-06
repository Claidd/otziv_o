package com.hunt.otziv.manager.dto.api;

public record FilialUpdateRequest(
        String title,
        String url,
        Long cityId
) {
}
