package com.hunt.otziv.gamification.service;

import com.hunt.otziv.gamification.dto.GamificationMyBreakdownResponse;
import com.hunt.otziv.gamification.dto.GamificationMyMissionResponse;
import com.hunt.otziv.gamification.dto.GamificationMyProgressResponse;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class GamificationUserProgressService {

    private final GamificationScoreLedgerRepository ledgerRepository;
    private final GamificationSettingsService settingsService;
    private final UserService userService;

    @Transactional(readOnly = true)
    public GamificationMyProgressResponse myProgress(Principal principal, int days) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        }

        User user = userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        String role = primaryRole(user.getRoles());
        Period period = period(days);
        if (!settingsService.isCabinetVisibleForRole(role)) {
            return response(false, period, user, role, emptyBreakdown());
        }

        Map<String, BreakdownAccumulator> breakdown = emptyBreakdownMap();
        for (Object[] row : ledgerRepository.balanceRowsForActor(
                user.getId(),
                period.fromInclusive(),
                period.toExclusive()
        )) {
            if (row == null || row.length < 3) {
                continue;
            }
            String eventType = row[0] == null ? "" : String.valueOf(row[0]);
            BreakdownAccumulator accumulator = breakdown.computeIfAbsent(eventType, BreakdownAccumulator::new);
            accumulator.events += number(row[1]);
            accumulator.points += number(row[2]);
        }

        return response(true, period, user, role, breakdown.values().stream()
                .map(BreakdownAccumulator::response)
                .toList());
    }

    private GamificationMyProgressResponse response(
            boolean enabled,
            Period period,
            User user,
            String role,
            List<GamificationMyBreakdownResponse> breakdown
    ) {
        long totalEvents = enabled ? breakdown.stream().mapToLong(GamificationMyBreakdownResponse::events).sum() : 0L;
        long totalPoints = enabled ? breakdown.stream().mapToLong(GamificationMyBreakdownResponse::points).sum() : 0L;
        long onTimeEvents = 0L;
        long delayedEvents = 0L;
        long lostPoints = 0L;
        long todayEvents = 0L;
        int streakDays = 0;
        if (enabled) {
            TimelinessStats stats = timelinessStats(user.getId(), period);
            onTimeEvents = stats.onTimeEvents();
            delayedEvents = stats.delayedEvents();
            lostPoints = stats.lostPoints();
            todayEvents = stats.todayEvents();
            streakDays = stats.streakDays();
        }
        long dailyGoal = dailyGoal(role);
        int dailyGoalPercent = percent(todayEvents, dailyGoal);
        LevelInfo levelInfo = levelInfo(totalPoints);
        int timelinessPercent = percent(onTimeEvents, Math.max(1L, onTimeEvents + delayedEvents));
        List<GamificationMyMissionResponse> missions = enabled
                ? missions(role, breakdown, todayEvents, dailyGoal, onTimeEvents, delayedEvents)
                : List.of();
        return new GamificationMyProgressResponse(
                enabled,
                period.from(),
                period.to(),
                period.days(),
                user.getId(),
                actorName(user),
                shortRole(role),
                totalEvents,
                totalPoints,
                dailyGoal,
                todayEvents,
                dailyGoalPercent,
                levelInfo.level(),
                levelInfo.currentLevelPoints(),
                levelInfo.nextLevelPoints(),
                levelInfo.pointsToNextLevel(),
                onTimeEvents,
                delayedEvents,
                lostPoints,
                timelinessPercent,
                streakDays,
                missions,
                enabled ? breakdown : List.of()
        );
    }

    private TimelinessStats timelinessStats(Long actorUserId, Period period) {
        long onTimeEvents = 0L;
        long delayedEvents = 0L;
        long lostPoints = 0L;
        for (Object[] row : ledgerRepository.balanceRowsForActor(actorUserId, period.fromInclusive(), period.toExclusive())) {
            if (row == null || row.length < 3) {
                continue;
            }
            long points = number(row[2]);
            long basePoints = row.length > 3 ? number(row[3]) : points;
            onTimeEvents += row.length > 4 ? number(row[4]) : number(row[1]);
            delayedEvents += row.length > 5 ? number(row[5]) : 0L;
            lostPoints += Math.max(0, basePoints - points);
        }

        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(30);
        Map<LocalDate, DailyStats> days = new HashMap<>();
        for (Object[] row : ledgerRepository.dailyRowsForActor(actorUserId, from.atStartOfDay(), today.plusDays(1).atStartOfDay())) {
            if (row == null || row.length < 3 || row[0] == null) {
                continue;
            }
            LocalDate day = row[0] instanceof java.sql.Date date ? date.toLocalDate() : LocalDate.parse(String.valueOf(row[0]));
            days.put(day, new DailyStats(number(row[1]), number(row[2])));
        }
        long todayEvents = days.getOrDefault(today, new DailyStats(0, 0)).events();
        int streak = 0;
        for (LocalDate cursor = today; !cursor.isBefore(from); cursor = cursor.minusDays(1)) {
            DailyStats day = days.get(cursor);
            if (day == null || day.events() == 0 || day.delayedEvents() > 0) {
                break;
            }
            streak++;
        }
        return new TimelinessStats(onTimeEvents, delayedEvents, lostPoints, todayEvents, streak);
    }

    private long dailyGoal(String role) {
        String shortRole = shortRole(role);
        if (GamificationEventService.ROLE_MANAGER.equals(shortRole)) {
            return 3L;
        }
        if (GamificationEventService.ROLE_WORKER.equals(shortRole)) {
            return 5L;
        }
        return 3L;
    }

    private List<GamificationMyMissionResponse> missions(
            String role,
            List<GamificationMyBreakdownResponse> breakdown,
            long todayEvents,
            long dailyGoal,
            long onTimeEvents,
            long delayedEvents
    ) {
        String shortRole = shortRole(role);
        long reviews = eventsOf(breakdown, GamificationEventService.REVIEW_PUBLISHED);
        long paidOrders = eventsOf(breakdown, GamificationEventService.ORDER_PAID);
        long badReviews = eventsOf(breakdown, GamificationEventService.BAD_REVIEW_TASK_DONE);
        long recoveries = eventsOf(breakdown, GamificationEventService.REVIEW_RECOVERY_TASK_DONE);
        if (GamificationEventService.ROLE_MANAGER.equals(shortRole)) {
            return List.of(
                    mission("daily", "Темп дня", "Закрыть план по рабочим действиям сегодня.", todayEvents, dailyGoal),
                    mission("paid", "Оплаты", "Довести оплаченные заказы до недельной цели.", paidOrders, 10),
                    mission("clean", "Без просрочек", "Сохранять чистый период без задержек.", onTimeEvents, Math.max(1, onTimeEvents + delayedEvents))
            );
        }
        return List.of(
                mission("daily", "Темп дня", "Закрыть план по рабочим действиям сегодня.", todayEvents, dailyGoal),
                mission("reviews", "Публикации", "Поддерживать поток опубликованных отзывов.", reviews, 20),
                mission("quality", "Качество", "Закрывать плохие и восстановления без провалов.", badReviews + recoveries, 5)
        );
    }

    private GamificationMyMissionResponse mission(String code, String title, String description, long progress, long target) {
        long safeTarget = Math.max(1, target);
        return new GamificationMyMissionResponse(
                code,
                title,
                description,
                progress,
                safeTarget,
                percent(progress, safeTarget),
                progress >= safeTarget
        );
    }

    private long eventsOf(List<GamificationMyBreakdownResponse> breakdown, String eventType) {
        return breakdown.stream()
                .filter(item -> eventType.equals(item.eventType()))
                .mapToLong(GamificationMyBreakdownResponse::events)
                .findFirst()
                .orElse(0L);
    }

    private int percent(long value, long target) {
        if (target <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, Math.round((double) value * 100D / (double) target)));
    }

    private LevelInfo levelInfo(long points) {
        int level = (int) (points / 500L) + 1;
        long current = (long) (level - 1) * 500L;
        long next = (long) level * 500L;
        return new LevelInfo(level, current, next, Math.max(0, next - points));
    }

    private Map<String, BreakdownAccumulator> emptyBreakdownMap() {
        Map<String, BreakdownAccumulator> result = new LinkedHashMap<>();
        result.put(GamificationEventService.REVIEW_PUBLISHED, new BreakdownAccumulator(GamificationEventService.REVIEW_PUBLISHED));
        result.put(GamificationEventService.ORDER_PAID, new BreakdownAccumulator(GamificationEventService.ORDER_PAID));
        result.put(GamificationEventService.BAD_REVIEW_TASK_DONE, new BreakdownAccumulator(GamificationEventService.BAD_REVIEW_TASK_DONE));
        result.put(GamificationEventService.REVIEW_RECOVERY_TASK_DONE, new BreakdownAccumulator(GamificationEventService.REVIEW_RECOVERY_TASK_DONE));
        return result;
    }

    private List<GamificationMyBreakdownResponse> emptyBreakdown() {
        return emptyBreakdownMap().values().stream()
                .map(BreakdownAccumulator::response)
                .toList();
    }

    private Period period(int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(safeDays - 1L);
        return new Period(from, to, safeDays, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    private String primaryRole(Collection<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        return roles.stream()
                .map(Role::getName)
                .filter(role -> role != null && !role.isBlank())
                .findFirst()
                .orElse("");
    }

    private String shortRole(String role) {
        if (role == null) {
            return null;
        }
        return role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role;
    }

    private String actorName(User user) {
        if (user.getFio() != null && !user.getFio().isBlank()) {
            return user.getFio();
        }
        return user.getUsername();
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private record Period(
            LocalDate from,
            LocalDate to,
            int days,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    ) {
    }

    private record DailyStats(long events, long delayedEvents) {
    }

    private record TimelinessStats(long onTimeEvents, long delayedEvents, long lostPoints, long todayEvents, int streakDays) {
    }

    private record LevelInfo(int level, long currentLevelPoints, long nextLevelPoints, long pointsToNextLevel) {
    }

    private static class BreakdownAccumulator {
        private final String eventType;
        private long events;
        private long points;

        private BreakdownAccumulator(String eventType) {
            this.eventType = eventType;
        }

        private GamificationMyBreakdownResponse response() {
            return new GamificationMyBreakdownResponse(eventType, events, points);
        }
    }
}
