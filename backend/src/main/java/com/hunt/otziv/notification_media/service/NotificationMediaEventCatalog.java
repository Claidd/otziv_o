package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.notification_media.model.NotificationRecipientType;
import java.util.Arrays;
import java.util.Optional;

public enum NotificationMediaEventCatalog {
    WORKER_TASK_FIRST(
            NotificationRecipientType.WORKER,
            "Первая задача специалисту",
            "Первичное уведомление о задаче или запросе пояснения",
            false
    ),
    WORKER_TASK_REPEAT(
            NotificationRecipientType.WORKER,
            "Повторное напоминание специалисту",
            "Первое повторное напоминание по задаче без ответа",
            false
    ),
    WORKER_TASK_OVERDUE(
            NotificationRecipientType.WORKER,
            "Просроченная задача специалиста",
            "Повторное напоминание после уже отправленного напоминания",
            false
    ),
    WORKER_RISK_REMINDER(
            NotificationRecipientType.WORKER,
            "Напоминание по риску",
            "Нужно предоставить пояснение по открытому риску",
            true
    ),
    WORKER_RISK_OVERDUE(
            NotificationRecipientType.WORKER,
            "Просрочено пояснение по риску",
            "Срок ответа по открытому риску истёк",
            true
    ),
    WORKER_PROGRESS_GROWING(
            NotificationRecipientType.WORKER,
            "Прогресс специалиста растёт",
            "Итог дня с выполненной дневной целью",
            false
    ),
    WORKER_PROGRESS_SLOWED(
            NotificationRecipientType.WORKER,
            "Прогресс специалиста замедлился",
            "Итог дня, когда дневная цель ещё не достигнута",
            false
    ),
    WORKER_STREAK(
            NotificationRecipientType.WORKER,
            "Серия специалиста",
            "Несколько дней подряд с выполненной целью",
            false
    ),
    WORKER_DAY_START(
            NotificationRecipientType.WORKER,
            "Пора начать рабочий день",
            "Специалист зашёл на сайт, но ещё не начал обязательную нагрузку",
            false
    ),
    WORKER_SITE_INACTIVE(
            NotificationRecipientType.WORKER,
            "Специалист не заходил на сайт",
            "На сегодня есть обязательная нагрузка, но входа и активности ещё не было",
            false
    ),
    WORKER_PUBLICATION_PENDING(
            NotificationRecipientType.WORKER,
            "Публикация ждёт выполнения",
            "У специалиста остались активные задачи в разделе публикации",
            false
    ),
    MANAGER_REPORT_REMINDER(
            NotificationRecipientType.MANAGER,
            "Напоминание менеджеру о разборе",
            "Отчёт доставлен, но разбор не завершён",
            false
    ),
    MANAGER_REPORT_OVERDUE(
            NotificationRecipientType.MANAGER,
            "Разбор менеджера просрочен",
            "Разбор отчёта не завершён за установленный срок",
            true
    ),
    MANAGER_TEAM_PROGRESS_GROWING(
            NotificationRecipientType.MANAGER,
            "Прогресс команды растёт",
            "Все специалисты менеджера выполнили дневную нагрузку",
            false
    ),
    MANAGER_TEAM_PROGRESS_SLOWED(
            NotificationRecipientType.MANAGER,
            "Прогресс команды требует внимания",
            "Итог дня, когда дневную нагрузку выполнила не вся команда",
            false
    ),
    MANAGER_TEAM_STREAK(
            NotificationRecipientType.MANAGER,
            "Командная серия",
            "Команда несколько дней подряд выполняет дневную цель",
            false
    ),
    MANAGER_WORKER_COMPLETION_WARNING(
            NotificationRecipientType.MANAGER,
            "Предупреждение о закрытиях",
            "Специалист подозрительно быстро закрыл много задач",
            true
    );

    private final NotificationRecipientType recipientType;
    private final String label;
    private final String description;
    private final boolean serious;

    NotificationMediaEventCatalog(
            NotificationRecipientType recipientType,
            String label,
            String description,
            boolean serious
    ) {
        this.recipientType = recipientType;
        this.label = label;
        this.description = description;
        this.serious = serious;
    }

    public String code() {
        return name();
    }

    public NotificationRecipientType recipientType() {
        return recipientType;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public boolean serious() {
        return serious;
    }

    public static Optional<NotificationMediaEventCatalog> find(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(event -> event.name().equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
