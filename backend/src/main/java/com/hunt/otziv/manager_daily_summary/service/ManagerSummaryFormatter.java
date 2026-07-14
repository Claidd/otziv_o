package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.repository.ManagerPerformanceDailyRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManagerSummaryFormatter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private final ManagerPerformanceDailyRepository dailyRepository;

    public String format(List<ManagerDailySummaryResponse> managers, boolean testMode) {
        if (managers == null || managers.isEmpty()) {
            return testMode ? "🧪 <b>Тестовая сводка</b>\n\nНет данных по менеджерам." : "Нет данных по менеджерам.";
        }
        long averageScore = Math.round(managers.stream().mapToInt(ManagerDailySummaryResponse::score).average().orElse(0));
        long tasks = managers.stream().mapToLong(ManagerDailySummaryResponse::taskTotal).sum();
        long completed = managers.stream().mapToLong(ManagerDailySummaryResponse::taskCompleted).sum();
        long autoClosed = managers.stream().mapToLong(ManagerDailySummaryResponse::taskAutoClosed).sum();
        long remaining = managers.stream().mapToLong(ManagerDailySummaryResponse::taskOpen).sum();
        long unanswered = managers.stream().mapToLong(ManagerDailySummaryResponse::unansweredCount).sum();
        long replies = managers.stream().mapToLong(ManagerDailySummaryResponse::replyCount).sum();
        long replySeconds = managers.stream().mapToLong(row -> row.allReplyAverageSeconds() * row.replyCount()).sum();
        boolean provisional = managers.stream().anyMatch(row -> !"VERIFIED".equals(row.aggregationStatus()));
        LocalDateTime snapshotAt = managers.stream().map(ManagerDailySummaryResponse::snapshotAt)
                .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);

        StringBuilder result = new StringBuilder();
        if (testMode) result.append("🧪 <b>ТЕСТОВАЯ СВОДКА</b>\n\n");
        if (provisional) {
            result.append("⏳ <b>Предварительные данные")
                    .append(snapshotAt == null ? "" : " на " + snapshotAt.format(TIME))
                    .append(".</b> Итог фиксируется в 23:00.\n\n");
        }
        result.append("📊 <b>Итоги рабочего дня — ")
                .append(managers.getFirst().date().format(DATE)).append("</b>\n\n")
                .append("👥 Менеджеров: <b>").append(managers.size()).append("</b>\n")
                .append("🏅 Средний рейтинг: <b>").append(averageScore).append("/100</b>\n")
                .append("✅ Обработано за день: <b>").append(completed).append(" из ").append(tasks).append("</b>\n")
                .append("📌 Осталось к действию: <b>").append(remaining).append("</b>\n");
        if (autoClosed > 0) {
            result.append("🤖 Снято автоматически: <b>").append(autoClosed).append("</b>\n");
        }
        result.append("💬 Среднее время всех ответов: <b>")
                .append(duration(replies == 0 ? 0 : Math.round(replySeconds / (double) replies))).append("</b>")
                .append(replies == 0 ? "" : " · ответов: " + replies).append("\n")
                .append("📭 Без ответа: <b>").append(unanswered).append("</b>");
        for (ManagerDailySummaryResponse manager : managers) {
            result.append("\n\n").append(formatManager(manager));
        }
        return result.toString();
    }

    private String formatManager(ManagerDailySummaryResponse row) {
        var previous = dailyRepository.findTopByManager_IdAndSummaryDateLessThanOrderBySummaryDateDesc(row.managerId(), row.date());
        var week = dailyRepository.findByManager_IdAndSummaryDateBetween(row.managerId(), row.date().minusDays(7), row.date().minusDays(1));
        String dayDelta = previous.map(item -> signed(row.score() - item.getAdjustedScore())).orElse("нет данных");
        String weekDelta = week.isEmpty()
                ? "нет данных"
                : signed(row.score() - (int) Math.round(week.stream().mapToInt(item -> item.getAdjustedScore()).average().orElse(row.score())));
        String weekLabel = week.isEmpty()
                ? "История для сравнения"
                : week.size() >= 7
                ? "К среднему за 7 дней"
                : "К среднему за доступные " + week.size() + " дн. (из 7)";
        String slaResult = row.replyCount() == 0
                ? "нет данных"
                : Math.round(row.repliesInSla() * 100.0 / row.replyCount()) + "%";
        boolean provisional = !"VERIFIED".equals(row.aggregationStatus());
        return "👤 <b>" + escape(row.managerName()) + "</b>\n"
                + "🏅 Текущий рейтинг за месяц: " + row.grade() + " — <b>" + row.score() + "/100</b>\n"
                + "↕️ Изменение с прошлого дня: <b>" + dayDelta + "</b>\n"
                + "📈 " + weekLabel + ": <b>" + weekDelta + "</b>\n"
                + "✅ Обработано: <b>" + row.taskCompleted() + " из " + row.taskTotal() + "</b> ("
                + row.taskProgressPercent().setScale(0, java.math.RoundingMode.HALF_UP) + "%)\n"
                + handledBreakdown(row)
                + "📌 Осталось к действию: <b>" + row.taskOpen() + "</b>\n"
                + remainingBreakdown(row)
                + (row.taskAutoClosed() > 0 ? "🤖 Снято автоматически: <b>" + row.taskAutoClosed() + "</b>\n" : "")
                + "💬 Первый ответ: <b>" + duration(row.firstReplyAverageSeconds()) + "</b>, медиана "
                + duration(row.firstReplyMedianSeconds()) + " · ответов: " + row.firstReplyCount() + "\n"
                + "↩️ Все ответы: <b>" + duration(row.allReplyAverageSeconds()) + "</b>, медиана "
                + duration(row.allReplyMedianSeconds()) + " · ответов: " + row.replyCount() + " · в нормативе " + slaResult + "\n"
                + "🛠 Проблемные карточки: <b>" + row.problemCount() + "</b>\n"
                + "├ окончательно решено: " + row.problemResolvedCount() + "\n"
                + "├ действие выполнено: " + row.problemActionTakenCount() + "\n"
                + "├ осталось открыто: " + row.problemOpenCount() + "\n"
                + "└ среднее окончательного решения: " + duration(row.problemResolutionAverageSeconds()) + "\n"
                + "⚠️ Просрочки: " + row.overdueCount() + " · риски: " + row.riskCount() + " · без ответа: " + row.unansweredCount() + "\n"
                + "🎮 " + (provisional ? "Предварительный результат" : "Итог дня") + ": <b>" + stars(row.dayStars())
                + "</b> · под контролем " + duration(row.controlledSeconds())
                + " · чистая очередь " + duration(row.cleanQueueSeconds()) + " · +" + row.xpEarned() + " XP\n"
                + "⏱ Подтверждённая активность: <b>" + duration(row.confirmedActiveSeconds()) + "</b>\n"
                + "├ сайт: " + duration(row.siteActiveSeconds()) + "\n"
                + "└ мессенджеры вне сайта: " + duration(row.messengerActiveSeconds());
    }

    private String handledBreakdown(ManagerDailySummaryResponse row) {
        return "├ решено: " + row.taskResolved() + "\n"
                + "├ действие выполнено: " + row.taskActionTaken() + "\n"
                + "├ отложено: " + row.taskDeferred() + "\n"
                + "└ принято в работу: " + row.taskAcknowledged() + "\n";
    }

    private String remainingBreakdown(ManagerDailySummaryResponse row) {
        return "├ просрочки: " + row.overdueCount() + "\n"
                + "├ риски: " + row.riskCount() + "\n"
                + "├ без ответа по SLA: " + row.unansweredCount() + "\n"
                + "└ прочее: " + row.taskOtherOpen() + "\n";
    }

    private String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private String stars(int count) {
        return "★".repeat(Math.max(0, Math.min(3, count))) + "☆".repeat(Math.max(0, 3 - count));
    }

    private String duration(long seconds) {
        if (seconds <= 0) return "—";
        Duration duration = Duration.ofSeconds(seconds);
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        if (hours > 0) return hours + " ч " + minutes + " мин";
        if (minutes > 0) return minutes + " мин";
        return Math.max(1, seconds) + " сек";
    }

    private String escape(String value) {
        if (value == null || value.isBlank()) return "Без имени";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
