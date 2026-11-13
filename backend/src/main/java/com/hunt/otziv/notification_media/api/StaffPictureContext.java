package com.hunt.otziv.notification_media.api;

/** Facts supplied by the notification producer about this specific staff message. */
public enum StaffPictureContext {
    TASK_FOLLOWUP("Повторный запрос ответа по задаче"),
    LAST_TASK("В текущем плане осталась ровно одна обязательная задача"),
    TEXT_PROOFREADING("Специалист готовит или проверяет текст"),
    TASK_ACKNOWLEDGED("Специалист подтвердил получение запроса, пояснение ещё ожидается"),
    VK_PUBLICATION("Открыта карточка публикации во ВКонтакте"),
    BEFORE_REMINDERS("Дневной план закрыт без зарегистрированных напоминаний"),
    TASK_GUIDANCE("Менеджер выдал задачу или запрос пояснения"),
    TASK_HANDOFF("Менеджер передал задачу специалисту"),
    CORRECTION_REPORTED("Специалист сообщил об исправлении в пояснении"),
    ACCOUNT_ACCESS("В плане есть задача без рабочего аккаунта"),
    BEFORE_22("До конца смены в 22:00 ещё есть время"),
    TEXT_CORRECTION("Открыт заказ в статусе «Коррекция»"),
    BROKEN_STREAK("После серии минимум в три рабочих дня дневная цель пропущена"),
    ACCOUNT_SHORTAGE("При подборе аккаунта карточке назначена заглушка"),
    DISPUTABLE_RECEIPT("В сообщении получателю доступна кнопка «Оспорить поступление»"),
    CHANGE_ACCOUNT_BUTTON("Во всех перечисленных карточках доступна кнопка «смена»"),
    BAD_TASK_TEXT("Открыта новая задача по плохому отзыву"),
    NEW_PROFILE("Открыт выгул свежего аккаунта");

    private final String label;

    StaffPictureContext(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
