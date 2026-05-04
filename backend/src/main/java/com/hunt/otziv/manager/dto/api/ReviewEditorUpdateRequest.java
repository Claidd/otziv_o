package com.hunt.otziv.manager.dto.api;

import java.time.LocalDate;

public record ReviewEditorUpdateRequest(
        String text,
        String answer,
        String comment,
        LocalDate created,
        LocalDate changed,
        LocalDate publishedDate,
        Boolean publish,
        Boolean vigul,
        String botName,
        String botPassword,
        Long productId,
        String url
) {
}
