package com.hunt.otziv.manager.services;

import java.util.List;

public final class ManagerBoardStatusCatalog {

    private static final List<String> COMPANY_STATUSES = List.of(
            "Все",
            "Новая",
            "В работе",
            "Новый заказ",
            "К рассылке",
            "Ожидание",
            "На стопе",
            "Бан"
    );

    private static final List<String> ORDER_STATUSES = List.of(
            "Все",
            "Новый",
            "В проверку",
            "На проверке",
            "Коррекция",
            "Публикация",
            "Опубликовано",
            "Выставлен счет",
            "Напоминание",
            "Не оплачено",
            "Архив",
            "Оплачено"
    );

    private ManagerBoardStatusCatalog() {
    }

    public static List<String> companyStatuses() {
        return COMPANY_STATUSES;
    }

    public static List<String> orderStatuses() {
        return ORDER_STATUSES;
    }
}
