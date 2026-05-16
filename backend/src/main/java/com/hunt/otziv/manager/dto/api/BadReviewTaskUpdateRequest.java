package com.hunt.otziv.manager.dto.api;

import java.time.LocalDate;

public record BadReviewTaskUpdateRequest(
        String taskText,
        LocalDate scheduledDate
) {
}
