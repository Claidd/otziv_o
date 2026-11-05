package com.hunt.otziv.manager_daily_summary.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Wording of a missed specialist goal. The remark talks about tasks, so all units of one
 * order in one work section (for example five texts of a new order) are one task.
 */
final class WorkerGoalRemarkText {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final int LISTED_TASKS = 4;

    private WorkerGoalRemarkText() {
    }

    /** The question keeps the «Специалист NAME за dd.MM.yyyy» prefix other flows rely on. */
    static String question(ManagerWorkerDailyProgressService.MissedWorkerGoal goal) {
        boolean single = goal.unfinishedTasks().size() == 1 && accounted(goal) >= goal.remaining();
        return "Специалист " + goal.workerName() + " за " + goal.date().format(DATE)
                + " не закрыл обязательную нагрузку дня: " + shortfall(goal) + ". "
                + "Что помешало закрыть " + (single ? "эту задачу" : "эти задачи")
                + " до конца дня и как вы поможете специалисту закрыть этот долг?";
    }

    static String shortfall(ManagerWorkerDailyProgressService.MissedWorkerGoal goal) {
        List<ManagerWorkerDayPlanReader.UnfinishedTask> tasks = goal.unfinishedTasks();
        if (tasks.isEmpty()) {
            return "выполнено " + goal.completed() + " из " + goal.total() + " ед. работы, осталось "
                    + goal.remaining();
        }
        int count = tasks.size();
        StringBuilder text = new StringBuilder(count == 1 ? "не выполнена " : "не выполнены ")
                .append(count).append(' ').append(plural(count, "задача", "задачи", "задач"))
                .append(" — ");
        List<String> labels = new ArrayList<>();
        tasks.stream().limit(LISTED_TASKS).forEach(task -> labels.add(task(task, goal.date())));
        text.append(String.join("; ", labels));
        if (count > LISTED_TASKS) {
            text.append("; и ещё ").append(count - LISTED_TASKS);
        }
        long unaccounted = goal.remaining() - accounted(goal);
        if (unaccounted > 0) {
            text.append("; ещё ").append(unaccounted).append(" ед. работы без уточнённого состава");
        }
        return text.toString();
    }

    static String task(ManagerWorkerDayPlanReader.UnfinishedTask task, LocalDate goalDate) {
        String order = task.orderId() == null ? "" : "заказ №" + task.orderId() + ": ";
        String time = task.availableAt() == null ? ""
                : ", в работе с " + since(task.availableAt(), goalDate);
        return order + units(task.section(), task.units()) + time;
    }

    static String units(String section, long count) {
        long value = Math.max(1, count);
        return switch (section == null ? "" : section) {
            case "NEW" -> value + " " + plural(value, "текст", "текста", "текстов") + " к написанию";
            case "CORRECTION" -> value == 1 ? "коррекция текстов" : value + " " + plural(value, "коррекция", "коррекции", "коррекций");
            case "PUBLISH" -> value + " " + plural(value, "публикация", "публикации", "публикаций");
            case "NAGUL" -> value + " " + plural(value, "выгул", "выгула", "выгулов");
            case "RECOVERY" -> value + " " + plural(value, "восстановление", "восстановления", "восстановлений") + " отзыва";
            case "BAD" -> value + " " + plural(value, "задача", "задачи", "задач") + " по плохим отзывам";
            default -> value + " ед. работы";
        };
    }

    static String plural(long value, String one, String few, String many) {
        long mod100 = Math.abs(value) % 100;
        long mod10 = mod100 % 10;
        if (mod100 >= 11 && mod100 <= 14) return many;
        if (mod10 == 1) return one;
        if (mod10 >= 2 && mod10 <= 4) return few;
        return many;
    }

    private static long accounted(ManagerWorkerDailyProgressService.MissedWorkerGoal goal) {
        return goal.unfinishedTasks().stream().mapToLong(ManagerWorkerDayPlanReader.UnfinishedTask::units).sum();
    }

    private static String since(LocalDateTime availableAt, LocalDate goalDate) {
        return goalDate != null && availableAt.toLocalDate().equals(goalDate)
                ? availableAt.format(TIME)
                : availableAt.format(DAY_TIME);
    }
}
