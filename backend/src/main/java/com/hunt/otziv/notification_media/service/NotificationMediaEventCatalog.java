package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.notification_media.model.NotificationRecipientType;
import java.util.Arrays;
import java.util.Optional;

public enum NotificationMediaEventCatalog {
    WORKER_STREAK_15(NotificationRecipientType.WORKER,
            "Серия от 15 дней",
            "Дневная цель выполнена, серия составляет не менее 15 рабочих дней", false),
    WORKER_NAGUL_GUIDE(NotificationRecipientType.WORKER,
            "Подготовка к выгулу",
            "Открыта своя незавершённая карточка выгула; памятка о порядке действий", false),
    WORKER_FRESH_ACCOUNT_GUIDE(NotificationRecipientType.WORKER,
            "Подготовка свежего аккаунта",
            "Открыта своя карточка выгула с аккаунтом, у которого не более одного опубликованного отзыва; памятка об оформлении профиля и выгуле", false),
    WORKER_TEXT_GUIDE(NotificationRecipientType.WORKER,
            "Работа над текстом",
            "Открыт свой заказ на подготовку или коррекцию текста; памятка о качестве, уникальности и соответствии фото", false),
    WORKER_TEXT_PENDING(NotificationRecipientType.WORKER,
            "Пустой текст",
            "Попытка сохранить свой отзыв с пустым текстом", false),
    WORKER_TEXT_SAVED(NotificationRecipientType.WORKER,
            "Текст сохранён",
            "Успешное сохранение непустого текста своего отзыва", false),
    WORKER_NAGUL_DONE(NotificationRecipientType.WORKER,
            "Выгул завершён",
            "Подтверждённое сохранение выполненного выгула", false),
    WORKER_PUBLICATION_DONE(NotificationRecipientType.WORKER,
            "Публикация завершена",
            "Отзыв успешно отмечен опубликованным", false),
    WORKER_THREE_PUBLICATIONS(NotificationRecipientType.WORKER,
            "Три публикации за день",
            "Успешная публикация, после которой за текущий день у специалиста ровно три разных опубликованных отзыва", false),
    WORKER_RATING_GUIDE(NotificationRecipientType.WORKER,
            "Задача по оценке",
            "Открыта своя невыполненная задача исправления плохой оценки", false),
    WORKER_RATING_DONE(NotificationRecipientType.WORKER,
            "Оценка исправлена",
            "Своя задача исправления оценки успешно завершена", false),
    WORKER_RECOVERY_GUIDE(NotificationRecipientType.WORKER,
            "Работа над восстановлением",
            "Открыта своя запланированная задача восстановления; памятка о работе по карточке", false),
    WORKER_RECOVERY_FINISH(NotificationRecipientType.WORKER,
            "Завершение восстановления",
            "Изменения в задаче восстановления сохранены, задача ещё не завершена; напоминание нажать Восстановил после публикации", false),
    WORKER_ACCOUNT_CHANGED(NotificationRecipientType.WORKER,
            "Текст после смены аккаунта",
            "Успешная смена аккаунта в своём отзыве или задаче восстановления", false),
    WORKER_UNSAVED_CHANGES(NotificationRecipientType.WORKER,
            "Несохранённые изменения",
            "Редактор своей карточки закрыт с изменённым, но не сохранённым черновиком", false),
    WORKER_SITE_ERROR(NotificationRecipientType.WORKER,
            "Ошибка рабочего раздела",
            "Интерфейс специалиста получил серверную ошибку при загрузке или сохранении рабочей карточки", false),
    WORKER_NETWORK_BLOCKED(NotificationRecipientType.WORKER,
            "Заблокирована немобильная сеть",
            "Сервер зафиксировал блокировку доступа из немобильной сети, интерфейс получил отказ", false),
    MANAGER_CLIENT_CHAT_PENDING(NotificationRecipientType.MANAGER,
            "Клиент ожидает ответ",
            "В сегодняшнем контроле менеджера есть открытая карточка клиентского чата без ответа", false),
    MANAGER_BAD_REVIEW_INVOICED(NotificationRecipientType.MANAGER,
            "Счёт после удаления плохого отзыва",
            "Только подтверждённое удаление плохого отзыва и начисление счёта; без подтверждения удаления автоматическая отправка запрещена", false),
    WORKER_ACCOUNT_LOGIN_GUIDE(NotificationRecipientType.WORKER,
            "Подсказка при входе в аккаунт",
            "Специалист скопировал пароль своего рабочего аккаунта; условная памятка на случай запроса авторизации MAX", false),
    MANAGER_PAYMENT_PENDING(NotificationRecipientType.MANAGER,
            "Клиентский счёт ожидает оплаты",
            "У менеджера есть текущий заказ в статусе Оплата", false),

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
