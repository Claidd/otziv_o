package com.hunt.otziv.personal_reminders.api;

/** Internal application capability; callers must authorize the source business operation. */
public interface SystemReminderCommands {
    /** Keeps an existing open reminder unchanged, otherwise creates a reminder due now. */
    void ensureOpenDueNow(Reminder command);

    record Reminder(Long recipientUserId, String title, String text, String sourceType,
                    Long sourceId, Long sourceOrderId) {}
}
