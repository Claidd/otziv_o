package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.workload_shadow.api.MandatoryAccountBlockers.BlockedTask;
import com.hunt.otziv.workload_shadow.api.MandatoryAccountBlockers.Kind;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Staff-only texts about today's cards that have no usable account. Never addressed to clients. */
final class AccountBlockerNoticeText {

    /** Exact labels of the account button on the specialist's card (web and mobile). */
    static final String GET_ACCOUNT_BUTTON = "Получить аккаунт";
    static final String CHANGE_ACCOUNT_BUTTON = "смена";
    static final int MAX_LISTED_TASKS = 10;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private AccountBlockerNoticeText() {
    }

    static String worker(LocalDate date, List<BlockedTask> tasks) {
        StringBuilder text = new StringBuilder()
                .append("⚠️ <b>В плане дня есть задачи без рабочего аккаунта</b>\n\n")
                .append("📅 Рабочий день: <b>").append(DATE.format(date)).append("</b>.\n")
                .append("Эти задачи входят в итог дня: если их не выполнить, день не будет засчитан на 100%.\n\n");
        int listed = 0;
        for (BlockedTask task : tasks) {
            if (listed == MAX_LISTED_TASKS) {
                break;
            }
            text.append("• ").append(kindLabel(task.kind())).append(subject(task)).append(" — нажмите «")
                    .append(task.recoveryAccountMissing() ? GET_ACCOUNT_BUTTON : CHANGE_ACCOUNT_BUTTON)
                    .append("» в карточке задачи.\n");
            listed++;
        }
        if (tasks.size() > listed) {
            text.append("• …и ещё ").append(tasks.size() - listed).append(".\n");
        }
        return text.append("\nЕсли кнопка не помогла или свободных аккаунтов нет, напишите менеджеру.")
                .toString();
    }

    /** {@code tasksByWorkerName} keeps the caller's order; values are that specialist's blocked cards. */
    static String manager(LocalDate date, Map<String, List<BlockedTask>> tasksByWorkerName) {
        StringBuilder text = new StringBuilder()
                .append("⚠️ <b>У специалистов остались задачи без аккаунта</b>\n\n")
                .append("📅 Рабочий день: <b>").append(DATE.format(date)).append("</b>.\n")
                .append("Эти задачи войдут в итог дня и не дадут закрыть его на 100%:\n");
        tasksByWorkerName.forEach((name, tasks) -> text.append("• ").append(escape(name)).append(": <b>")
                .append(tasks.size()).append("</b> (").append(kinds(tasks)).append(")\n"));
        return text.append("\nВ карточке задачи специалист может нажать «").append(GET_ACCOUNT_BUTTON)
                .append("» или «").append(CHANGE_ACCOUNT_BUTTON).append("». Если свободных аккаунтов нет, ")
                .append("помогите подобрать аккаунт или перенесите задачу.")
                .toString();
    }

    static String kindLabel(Kind kind) {
        if (kind == null) {
            return "Задача";
        }
        return switch (kind) {
            case NAGUL -> "Выгул";
            case PUBLISH -> "Публикация";
            case BAD -> "Задача по оценке";
            case RECOVERY -> "Восстановление";
        };
    }

    private static String subject(BlockedTask task) {
        StringBuilder subject = new StringBuilder();
        if (task.companyTitle() != null && !task.companyTitle().isBlank()) {
            subject.append(" · «").append(escape(task.companyTitle().trim())).append("»");
        }
        if (task.orderId() != null) {
            subject.append(subject.isEmpty() ? " · " : ", ").append("заказ №").append(task.orderId());
        }
        return subject.toString();
    }

    private static String kinds(List<BlockedTask> tasks) {
        Map<String, Long> counts = tasks.stream().collect(Collectors.groupingBy(
                task -> kindLabel(task.kind()).toLowerCase(java.util.Locale.ROOT),
                LinkedHashMap::new,
                Collectors.counting()));
        return counts.entrySet().stream()
                .map(entry -> entry.getValue() > 1 ? entry.getKey() + " ×" + entry.getValue() : entry.getKey())
                .collect(Collectors.joining(", "));
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
