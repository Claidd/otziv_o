package com.hunt.otziv.r_review.utils;

import java.time.LocalDate;

public final class ReviewPublicationDatePolicy {

    public static final int MAX_FUTURE_DAYS = 90;
    public static final int MAX_DAYS_AFTER_PREVIOUS_REVIEW = 30;

    private ReviewPublicationDatePolicy() {
    }

    public static LocalDate maxAllowedDate() {
        return LocalDate.now().plusDays(MAX_FUTURE_DAYS);
    }

    public static void requireAllowed(LocalDate date) {
        if (date == null) {
            return;
        }

        LocalDate maxAllowed = maxAllowedDate();
        if (date.isAfter(maxAllowed)) {
            throw new IllegalArgumentException(
                    "Дата публикации слишком далеко: максимум " + maxAllowed
                            + " (" + MAX_FUTURE_DAYS + " дней вперед)"
            );
        }
    }

    public static void requireAllowedAfterPrevious(LocalDate date, LocalDate previousDate) {
        if (date == null || previousDate == null) {
            return;
        }

        LocalDate maxAllowed = previousDate.plusDays(MAX_DAYS_AFTER_PREVIOUS_REVIEW);
        if (date.isAfter(maxAllowed)) {
            throw new IllegalArgumentException(
                    "Дата публикации слишком далеко от предыдущего отзыва: максимум " + maxAllowed
                            + " (" + MAX_DAYS_AFTER_PREVIOUS_REVIEW + " дней после предыдущего)"
            );
        }
    }
}
