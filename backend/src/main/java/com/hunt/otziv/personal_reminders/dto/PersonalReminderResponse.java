package com.hunt.otziv.personal_reminders.dto;

import com.hunt.otziv.personal_reminders.model.PersonalReminder;

import java.time.Instant;

public record PersonalReminderResponse(
        Long id,
        String title,
        String text,
        String reminderMode,
        Instant remindAt,
        Integer timerMinutes,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static PersonalReminderResponse from(PersonalReminder reminder) {
        return new PersonalReminderResponse(
                reminder.getId(),
                reminder.getTitle(),
                reminder.getText(),
                reminder.getReminderMode(),
                reminder.getRemindAt(),
                reminder.getTimerMinutes(),
                reminder.getCompletedAt(),
                reminder.getCreatedAt(),
                reminder.getUpdatedAt()
        );
    }
}
