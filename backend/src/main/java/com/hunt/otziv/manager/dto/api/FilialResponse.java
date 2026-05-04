package com.hunt.otziv.manager.dto.api;

public record FilialResponse(
        Long id,
        String title,
        String url,
        Long cityId,
        String city
) {
}
