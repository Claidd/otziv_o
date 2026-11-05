package com.hunt.otziv.manager_daily_summary.service;

/** One reading of the audit timing settings, within the ranges the settings page accepts. */
public final class ManagerReportReviewDeadline {

    static final String FIRST_REMINDER_KEY = "manager.report-review.reminder-one-minutes";
    static final String DEADLINE_KEY = "manager.report-review.reminder-three-minutes";
    static final String RESTRICTION_KEY = "manager.report-review.restriction-enabled";
    static final int DEFAULT_FIRST_REMINDER_MINUTES = 60;
    static final int DEFAULT_DEADLINE_MINUTES = 180;

    private ManagerReportReviewDeadline() {
    }

    /** The configured first reminder, clamped to the settings page range 5..1440 minutes. */
    public static int firstReminderMinutes(int configured) {
        return Math.max(5, Math.min(1440, configured));
    }

    /** The configured deadline, clamped to the settings page range 10..4320 minutes. */
    public static int deadlineMinutes(int configured) {
        return Math.max(10, Math.min(4320, configured));
    }

    /** "3 часа", "45 минут", "1 ч 30 мин". */
    public static String duration(int minutes) {
        int value = Math.max(1, minutes);
        if (value % 60 == 0) {
            int hours = value / 60;
            return hours + " " + plural(hours, "час", "часа", "часов");
        }
        if (value < 60) {
            return value + " " + plural(value, "минута", "минуты", "минут");
        }
        return value / 60 + " ч " + value % 60 + " мин";
    }

    /** "Срок прохождения (3 часа)". */
    public static String deadlineLabel(int configuredDeadlineMinutes) {
        return "Срок прохождения (" + duration(deadlineMinutes(configuredDeadlineMinutes)) + ")";
    }

    private static String plural(int value, String one, String few, String many) {
        int mod100 = value % 100;
        int mod10 = value % 10;
        if (mod100 >= 11 && mod100 <= 14) return many;
        if (mod10 == 1) return one;
        if (mod10 >= 2 && mod10 <= 4) return few;
        return many;
    }
}
