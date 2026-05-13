package com.hunt.otziv.common;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class BoardLiveSlice {

    public static final List<String> ACTIVE_ORDER_STATUSES = List.of(
            "Новый",
            "В проверку",
            "На проверке",
            "Коррекция",
            "Публикация",
            "Опубликовано",
            "Выставлен счет",
            "Напоминание",
            "Не оплачено"
    );

    public static final List<String> WORKER_BOARD_ALL_ORDER_STATUSES = List.of(
            "Новый",
            "В проверку",
            "На проверке",
            "Коррекция",
            "Публикация",
            "Опубликовано",
            "Выставлен счет",
            "Напоминание",
            "Не оплачено",
            "Оплачено"
    );

    public static final Set<String> HIDDEN_COMPANY_STATUSES = Set.of(
            "На стопе",
            "Бан"
    );

    private BoardLiveSlice() {
    }

    public static LocalDate cutoff(int retentionDays) {
        return LocalDate.now().minusDays(Math.max(retentionDays, 1));
    }
}
