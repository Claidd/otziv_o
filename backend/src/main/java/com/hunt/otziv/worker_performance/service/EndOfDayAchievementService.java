package com.hunt.otziv.worker_performance.service;

import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EndOfDayAchievementService {

    public static final String ROLE_WORKER = "WORKER";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_MANAGER_WORKDAY = "MANAGER_WORKDAY";

    private final NamedParameterJdbcTemplate jdbc;
    private final TelegramService telegramService;
    private final GamificationEventService gamificationEventService;

    @Transactional
    public AchievementResult saveResult(
            LocalDate date,
            String actorRole,
            Long actorId,
            Long actorUserId,
            long eligibleCount,
            long completedCount,
            double progressPercent,
            long ignoredLateCount,
            boolean reached100
    ) {
        if (date == null || actorId == null || actorRole == null || actorRole.isBlank()) {
            return AchievementResult.empty(date, actorRole, actorId);
        }
        boolean eligible = eligibleCount > 0;
        boolean achieved = eligible && reached100;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("date", date)
                .addValue("actorRole", actorRole)
                .addValue("actorId", actorId)
                .addValue("actorUserId", actorUserId)
                .addValue("eligibleCount", Math.max(0, eligibleCount))
                .addValue("completedCount", Math.max(0, completedCount))
                .addValue("progressPercent", BigDecimal.valueOf(Math.max(0, Math.min(100, progressPercent)))
                        .setScale(2, RoundingMode.HALF_UP))
                .addValue("ignoredLateCount", Math.max(0, ignoredLateCount))
                .addValue("reached100", achieved);
        jdbc.update("""
                INSERT INTO end_of_day_achievement_results (
                    result_date, actor_role, actor_id, actor_user_id,
                    eligible_count, completed_count, progress_percent,
                    ignored_late_count, reached_100, streak_days
                ) VALUES (
                    :date, :actorRole, :actorId, :actorUserId,
                    :eligibleCount, :completedCount, :progressPercent,
                    :ignoredLateCount, :reached100, 0
                )
                ON DUPLICATE KEY UPDATE
                    actor_user_id = VALUES(actor_user_id),
                    eligible_count = VALUES(eligible_count),
                    completed_count = VALUES(completed_count),
                    progress_percent = VALUES(progress_percent),
                    ignored_late_count = VALUES(ignored_late_count),
                    reached_100 = VALUES(reached_100)
                """, params);
        int streakDays = achieved || !eligible ? calculateStreak(date, actorRole, actorId) : 0;
        params.addValue("streakDays", streakDays);
        jdbc.update("""
                UPDATE end_of_day_achievement_results
                SET streak_days = :streakDays
                WHERE result_date = :date
                  AND actor_role = :actorRole
                  AND actor_id = :actorId
                """, params);
        boolean notified = !jdbc.queryForList("""
                SELECT telegram_notified_at
                FROM end_of_day_achievement_results
                WHERE result_date = :date
                  AND actor_role = :actorRole
                  AND actor_id = :actorId
                  AND telegram_notified_at IS NOT NULL
                """, params).isEmpty();
        return new AchievementResult(
                date,
                actorRole,
                actorId,
                Math.max(0, eligibleCount),
                Math.max(0, completedCount),
                round1(progressPercent),
                Math.max(0, ignoredLateCount),
                achieved,
                streakDays,
                notified
        );
    }

    public void notifyWorker(Worker worker, AchievementResult result) {
        if (worker == null || result == null) {
            return;
        }
        if (result.reached100()) {
            recordWorkerEvents(worker, result);
        }
        if (result.notified()) return;
        User user = worker.getUser();
        Long chatId = user == null ? null : firstNonNull(user.getWorkerTelegramGroupChatId(), user.getTelegramChatId());
        if (chatId == null) {
            return;
        }
        String name = user == null ? "Специалист" : firstNonBlank(user.getFio(), user.getUsername(), "Специалист");
        String text = workerWorkdayText(name, result);
        if (telegramService.sendMessage(chatId, text, "HTML")) {
            markNotified(result);
        }
    }

    public void notifyManager(Manager manager, AchievementResult result) {
        if (manager == null || result == null || !result.reached100()) {
            return;
        }
        recordManagerEvents(manager, result);
        if (result.notified()) return;
        User user = manager.getUser();
        Long chatId = user == null ? null : user.getTelegramChatId();
        if (chatId == null) {
            return;
        }
        String text = "🏆 <b>Команда закрыла день на 100%!</b>\n\n"
                + "👥 Все работники выполнили задачи, поступившие до <b>23:00</b>: <b>"
                + result.completedCount() + " из " + result.eligibleCount() + "</b>.\n"
                + streakLine(result.streakDays(), true)
                + ignoredLine(result.ignoredLateCount())
                + "\nСильная командная работа! 🔥";
        if (telegramService.sendMessage(chatId, text, "HTML")) {
            markNotified(result);
        }
    }

    public boolean notifyManagerWorkday(Manager manager, AchievementResult result, long siteActiveSeconds) {
        if (manager == null || result == null || result.notified()) {
            return false;
        }
        User user = manager.getUser();
        Long chatId = user == null ? null : user.getTelegramChatId();
        if (chatId == null) {
            return false;
        }
        String name = user == null ? "Менеджер" : firstNonBlank(user.getFio(), user.getUsername(), "Менеджер");
        String text = managerWorkdayText(name, result, siteActiveSeconds);
        if (!telegramService.sendMessage(chatId, text, "HTML")) {
            return false;
        }
        markNotified(result);
        return true;
    }

    private int calculateStreak(LocalDate date, String actorRole, Long actorId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("date", date)
                .addValue("actorRole", actorRole)
                .addValue("actorId", actorId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT reached_100
                FROM end_of_day_achievement_results
                WHERE actor_role = :actorRole
                  AND actor_id = :actorId
                  AND result_date <= :date
                  AND eligible_count > 0
                ORDER BY result_date DESC
                LIMIT 366
                """, params);
        int streak = 0;
        for (Map<String, Object> row : rows) {
            if (!booleanValue(row.get("reached_100"))) {
                break;
            }
            streak++;
        }
        return streak;
    }

    private void markNotified(AchievementResult result) {
        jdbc.update("""
                UPDATE end_of_day_achievement_results
                SET telegram_notified_at = CURRENT_TIMESTAMP(6)
                WHERE result_date = :date
                  AND actor_role = :actorRole
                  AND actor_id = :actorId
                  AND telegram_notified_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("date", result.date())
                .addValue("actorRole", result.actorRole())
                .addValue("actorId", result.actorId()));
    }

    private void recordWorkerEvents(Worker worker, AchievementResult result) {
        LocalDateTime occurredAt = result.date().atTime(23, 59, 50);
        String payload = payload(result);
        gamificationEventService.recordWorkerMilestone(
                GamificationEventService.WORKER_DAY_100,
                worker,
                worker.getId() + ":" + result.date(),
                occurredAt,
                payload
        );
        if (result.streakDays() >= 3) {
            gamificationEventService.recordWorkerMilestone(
                    GamificationEventService.WORKER_100_STREAK,
                    worker,
                    worker.getId() + ":" + result.date() + ":" + result.streakDays(),
                    occurredAt,
                    payload
            );
        }
    }

    private void recordManagerEvents(Manager manager, AchievementResult result) {
        LocalDateTime occurredAt = result.date().atTime(23, 59, 50);
        String payload = payload(result);
        gamificationEventService.recordManagerMilestone(
                GamificationEventService.MANAGER_TEAM_DAY_100,
                manager,
                manager.getId() + ":" + result.date(),
                occurredAt,
                payload
        );
        if (result.streakDays() >= 3) {
            gamificationEventService.recordManagerMilestone(
                    GamificationEventService.MANAGER_TEAM_100_STREAK,
                    manager,
                    manager.getId() + ":" + result.date() + ":" + result.streakDays(),
                    occurredAt,
                    payload
            );
        }
    }

    private String payload(AchievementResult result) {
        return "date=" + result.date()
                + ";streakDays=" + result.streakDays()
                + ";eligibleCount=" + result.eligibleCount()
                + ";completedCount=" + result.completedCount()
                + ";ignoredLateCount=" + result.ignoredLateCount();
    }

    private String streakLine(int streakDays, boolean team) {
        if (streakDays >= 3) {
            return "🔥 " + (team ? "Командная серия" : "Серия") + ": <b>" + streakDays
                    + " " + dayWord(streakDays) + " подряд на 100%</b>!\n";
        }
        if (streakDays == 2) {
            return "🔥 Уже <b>2 дня подряд на 100%</b>. Ещё день — и будет серия!\n";
        }
        return "🌟 Первый день новой серии на 100%.\n";
    }

    private String ignoredLine(long ignoredLateCount) {
        if (ignoredLateCount <= 0) {
            return "";
        }
        return "🕚 Новые задачи после 23:00 (<b>" + ignoredLateCount
                + "</b>) результат не снизили — они перейдут на следующий день.\n";
    }

    private String workerWorkdayText(String name, AchievementResult result) {
        if (result.reached100()) {
            return "🏆 <b>День закрыт на 100%!</b>\n\n"
                    + "✨ " + escape(name) + ", все задачи, поступившие до <b>23:00</b>, выполнены.\n"
                    + streakLine(result.streakDays(), false)
                    + ignoredLine(result.ignoredLateCount())
                    + "\nОтличный финиш дня — так держать! 🚀";
        }
        long remaining = Math.max(0, result.eligibleCount() - result.completedCount());
        return "📊 <b>Итоги рабочего дня</b>\n\n"
                + "👤 " + escape(name) + ", <b>цель на день не выполнена.</b>\n"
                + "✅ Выполнено: <b>" + result.completedCount() + " из " + result.eligibleCount()
                + "</b> (" + formatPercent(result.progressPercent()) + "%).\n"
                + "📌 Осталось выполнить: <b>" + remaining + "</b>.\n"
                + "🔥 Счётчик дней на 100%: <b>0 дней</b>.\n"
                + ignoredLine(result.ignoredLateCount())
                + "\nЗавтра можно начать новую серию.";
    }

    private String managerWorkdayText(String name, AchievementResult result, long siteActiveSeconds) {
        StringBuilder text = new StringBuilder();
        if (result.eligibleCount() <= 0) {
            text.append("📊 <b>Итоги рабочего дня</b>\n\n")
                    .append("👤 ").append(escape(name)).append(", сегодня заданий для расчёта не было.\n")
                    .append("🔥 Серия на 100%: <b>").append(result.streakDays()).append(' ')
                    .append(dayWord(result.streakDays())).append("</b> — без изменений.\n");
        } else if (result.reached100()) {
            text.append("🏆 <b>День менеджера закрыт на 100%!</b>\n\n")
                    .append("✨ ").append(escape(name)).append(", все задания выполнены: <b>")
                    .append(result.completedCount()).append(" из ").append(result.eligibleCount()).append("</b>.\n")
                    .append(streakLine(result.streakDays(), false));
        } else {
            long remaining = Math.max(0, result.eligibleCount() - result.completedCount());
            text.append("📊 <b>Итоги рабочего дня</b>\n\n")
                    .append("👤 ").append(escape(name)).append(", <b>цель на день не выполнена.</b>\n")
                    .append("✅ Выполнено на <b>").append(formatPercent(result.progressPercent())).append("%</b>: <b>")
                    .append(result.completedCount()).append(" из ").append(result.eligibleCount()).append("</b>.\n")
                    .append("📌 Осталось к действию: <b>").append(remaining).append("</b>.\n")
                    .append("🔥 Счётчик дней на 100%: <b>0 дней</b>.\n");
        }
        text.append("⏱ Активная работа на сайте: <b>")
                .append(duration(siteActiveSeconds)).append("</b>.");
        return text.toString();
    }

    private String formatPercent(double value) {
        double rounded = round1(value);
        return rounded == Math.rint(rounded)
                ? String.valueOf((long) rounded)
                : String.valueOf(rounded);
    }

    private String duration(long seconds) {
        if (seconds <= 0) return "—";
        Duration value = Duration.ofSeconds(seconds);
        long hours = value.toHours();
        long minutes = value.minusHours(hours).toMinutes();
        if (hours > 0) return hours + " ч " + minutes + " мин";
        if (minutes > 0) return minutes + " мин";
        return Math.max(1, seconds) + " сек";
    }

    private String dayWord(int days) {
        int mod100 = days % 100;
        int mod10 = days % 10;
        if (mod100 >= 11 && mod100 <= 14) return "дней";
        if (mod10 == 1) return "день";
        if (mod10 >= 2 && mod10 <= 4) return "дня";
        return "дней";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) return null;
        for (T value : values) if (value != null) return value;
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return value != null && ("1".equals(value.toString()) || Boolean.parseBoolean(value.toString()));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public record AchievementResult(
            LocalDate date,
            String actorRole,
            Long actorId,
            long eligibleCount,
            long completedCount,
            double progressPercent,
            long ignoredLateCount,
            boolean reached100,
            int streakDays,
            boolean notified
    ) {
        public static AchievementResult empty(LocalDate date, String actorRole, Long actorId) {
            return new AchievementResult(date, actorRole, actorId, 0, 0, 0, 0, false, 0, false);
        }
    }
}
