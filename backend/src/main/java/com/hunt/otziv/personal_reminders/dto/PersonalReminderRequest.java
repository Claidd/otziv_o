package com.hunt.otziv.personal_reminders.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;

public record PersonalReminderRequest(
        @Size(max = 120) String title,
        @Size(max = 1000) String text,
        String reminderMode,
        Instant remindAt,
        Integer timerMinutes
) {
}
