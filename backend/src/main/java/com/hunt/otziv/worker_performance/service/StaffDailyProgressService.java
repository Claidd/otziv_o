package com.hunt.otziv.worker_performance.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_performance.dto.ManagerPerformanceScoreResponse;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class StaffDailyProgressService {

    private static final ZoneId PROGRESS_ZONE = ZoneId.of("Asia/Irkutsk");
    private static final long MANAGER_DAILY_GOAL = 3L;
    private static final long SINGLE_ACTION_SESSION_SECONDS = 60L;
    private static final String ROLE_MANAGER = "MANAGER";
    private static final String ROLE_WORKER = "WORKER";
    private static final String TYPE_ORDER = "order";
    private static final String TYPE_NAGUL = "review_nagul";
    private static final String TYPE_PUBLISH = "review_publish";
    private static final String TYPE_BAD = "bad_task";
    private static final String TYPE_RECOVERY = "recovery_task";
    private static final List<String> BOT_CHANGE_ACTIONS = List.of(
            "REVIEW_BOT_CHANGE",
            "BAD_TASK_BOT_CHANGE",
            "RECOVERY_TASK_BOT_CHANGE"
    );
    private static final List<String> BOT_BLOCK_ACTIONS = List.of(
            "REVIEW_BOT_DEACTIVATE"
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final AppSettingService appSettingService;

    @Transactional(readOnly = true)
    public boolean progressEnabled() {
        return appSettingService.getBoolean(AppSettingService.WORKER_PROGRESS_ENABLED, true);
    }

    @Transactional(readOnly = true)
    public Map<Long, DailyWorkProgressResponse> managerProgressByUserIds(Collection<Long> userIds, LocalDate date) {
        if (!progressEnabled() || userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        LocalDate safeDate = safeDate(date);
        Map<Long, Long> todayEvents = new HashMap<>();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userIds", userIds)
                .addValue("from", safeDate.atStartOfDay())
                .addValue("to", safeDate.plusDays(1).atStartOfDay());

        jdbc.queryForList("""
                SELECT l.actor_user_id AS user_id, COUNT(*) AS completed
                FROM gamification_score_ledger l
                WHERE l.actor_user_id IN (:userIds)
                  AND l.actor_role = 'MANAGER'
                  AND l.source_event_created_at >= :from
                  AND l.source_event_created_at < :to
                  AND l.points > 0
                GROUP BY l.actor_user_id
                """, params).forEach(row -> todayEvents.put(
                longValue(row.get("user_id")),
                longValue(row.get("completed"))
        ));

        Map<Long, DailyWorkProgressResponse> result = new LinkedHashMap<>();
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            long completed = todayEvents.getOrDefault(userId, 0L);
            long total = Math.max(MANAGER_DAILY_GOAL, completed);
            long active = Math.max(0, MANAGER_DAILY_GOAL - completed);
            result.put(userId, new DailyWorkProgressResponse(
                    true,
                    ROLE_MANAGER,
                    safeDate,
                    completed,
                    active,
                    total,
                    percentInt(completed, total),
                    completed >= MANAGER_DAILY_GOAL,
                    null,
                    null,
                    0,
                    0,
                    0,
                    null,
                    null,
                    0,
                    0,
                    0,
                    completed,
                    percentInt(completed, total)
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public DailyWorkProgressResponse aggregateManagerProgressByUserIds(Collection<Long> userIds, LocalDate date) {
        LocalDate safeDate = safeDate(date);
        Map<Long, DailyWorkProgressResponse> progress = managerProgressByUserIds(userIds, safeDate);
        if (progress.isEmpty()) {
            return DailyWorkProgressResponse.hidden(ROLE_MANAGER, safeDate);
        }

        long completed = progress.values().stream().mapToLong(DailyWorkProgressResponse::completed).sum();
        long active = progress.values().stream().mapToLong(DailyWorkProgressResponse::active).sum();
        long total = progress.values().stream().mapToLong(DailyWorkProgressResponse::total).sum();
        int percent = percentInt(completed, total);

        return new DailyWorkProgressResponse(
                true,
                ROLE_MANAGER,
                safeDate,
                completed,
                active,
                total,
                percent,
                total == 0 || active == 0,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                completed,
                percent
        );
    }

    public DailyWorkProgressResponse managerProgressFromPerformance(
            ManagerPerformanceScoreResponse performance,
            LocalDate date
    ) {
        if (performance == null) {
            return null;
        }
        LocalDate safeDate = safeDate(date);
        long completed = Math.max(0, performance.handledCount());
        long active = Math.max(0, performance.openCount());
        long total = completed + active;
        int percent = total == 0 ? 100 : percentInt(completed, total);

        return new DailyWorkProgressResponse(
                true,
                ROLE_MANAGER,
                safeDate,
                completed,
                active,
                total,
                percent,
                active == 0,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                performance.actionTotal(),
                performance.loadAdjustedPerformanceScore()
        );
    }

    public DailyWorkProgressResponse aggregateManagerProgressFromPerformance(
            Collection<ManagerPerformanceScoreResponse> performances,
            LocalDate date
    ) {
        if (performances == null || performances.isEmpty()) {
            return DailyWorkProgressResponse.hidden(ROLE_MANAGER, safeDate(date));
        }
        LocalDate safeDate = safeDate(date);
        List<ManagerPerformanceScoreResponse> visible = performances.stream()
                .filter(Objects::nonNull)
                .toList();
        if (visible.isEmpty()) {
            return DailyWorkProgressResponse.hidden(ROLE_MANAGER, safeDate);
        }

        long completed = visible.stream().mapToLong(ManagerPerformanceScoreResponse::handledCount).sum();
        long active = visible.stream().mapToLong(ManagerPerformanceScoreResponse::openCount).sum();
        long total = completed + active;
        int percent = total == 0 ? 100 : percentInt(completed, total);
        int efficiency = (int) Math.round(visible.stream()
                .mapToInt(ManagerPerformanceScoreResponse::loadAdjustedPerformanceScore)
                .average()
                .orElse(0));

        return new DailyWorkProgressResponse(
                true,
                ROLE_MANAGER,
                safeDate,
                completed,
                active,
                total,
                percent,
                active == 0,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                visible.stream().mapToLong(ManagerPerformanceScoreResponse::actionTotal).sum(),
                efficiency
        );
    }

    @Transactional
    public Map<Long, DailyWorkProgressResponse> workerProgressByWorkers(Collection<Worker> workers, LocalDate date) {
        if (!progressEnabled() || workers == null || workers.isEmpty()) {
            return Map.of();
        }
        return workerProgressBySubjects(workers.stream()
                .filter(Objects::nonNull)
                .map(worker -> new WorkerProgressSubject(
                        worker.getId(),
                        worker.getUser() == null ? null : worker.getUser().getId(),
                        workerName(worker.getUser())
                ))
                .toList(), date);
    }

    @Transactional(readOnly = true)
    public Map<Long, DailyWorkProgressResponse> workerEndOfDayProgressByWorkers(
            Collection<Worker> workers,
            LocalDate date,
            LocalDateTime ignoreOpenedAtOrAfter
    ) {
        if (!progressEnabled() || workers == null || workers.isEmpty()) {
            return Map.of();
        }
        return workerProgressBySubjectsInternal(workers.stream()
                .filter(Objects::nonNull)
                .map(worker -> new WorkerProgressSubject(
                        worker.getId(),
                        worker.getUser() == null ? null : worker.getUser().getId(),
                        workerName(worker.getUser())
                ))
                .toList(), date, ignoreOpenedAtOrAfter, false);
    }

    @Transactional
    public Map<Long, DailyWorkProgressResponse> workerProgressBySubjects(Collection<WorkerProgressSubject> workers, LocalDate date) {
        return workerProgressBySubjectsInternal(workers, date, null, true);
    }

    private Map<Long, DailyWorkProgressResponse> workerProgressBySubjectsInternal(
            Collection<WorkerProgressSubject> workers,
            LocalDate date,
            LocalDateTime ignoreOpenedAtOrAfter,
            boolean persist
    ) {
        if (!progressEnabled() || workers == null || workers.isEmpty()) {
            return Map.of();
        }
        LocalDate safeDate = safeDate(date);
        List<WorkerProgressSubject> visibleWorkers = workers.stream()
                .filter(Objects::nonNull)
                .filter(worker -> worker.workerId() != null)
                .toList();
        if (visibleWorkers.isEmpty()) {
            return Map.of();
        }

        List<Long> workerIds = visibleWorkers.stream().map(WorkerProgressSubject::workerId).distinct().toList();
        Map<Long, Long> workerUserIdByWorkerId = new HashMap<>();
        visibleWorkers.forEach(worker -> workerUserIdByWorkerId.put(worker.workerId(), worker.workerUserId()));
        List<Long> workerUserIds = visibleWorkers.stream()
                .map(WorkerProgressSubject::workerUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<ActiveWorkItem> activeItems = activeItems(workerIds, safeDate);
        if (ignoreOpenedAtOrAfter != null) {
            activeItems = activeItems.stream()
                    .filter(item -> includedInEndOfDay(item.addedAt(), ignoreOpenedAtOrAfter))
                    .toList();
        }
        Map<Long, WorkerActiveStats> active = activeStats(activeItems, safeDate);
        Map<Long, WorkerCompletionStats> completed = completionStats(workerIds, safeDate, ignoreOpenedAtOrAfter);
        Map<Long, WorkerActivityStats> activity = activityStats(workerUserIds, safeDate);
        Map<Long, WorkerAuxStats> aux = auxStats(workerIds, workerUserIds, workerUserIdByWorkerId, safeDate);
        Map<Long, Reached100State> reached100States = reached100States(workerIds, safeDate);
        if (persist) {
            syncLifecycle(activeItems, completed, workerUserIdByWorkerId);
            excludeWaitingClientOrdersFromLifecycle(workerIds);
        }

        Map<Long, DailyWorkProgressResponse> result = new LinkedHashMap<>();
        for (WorkerProgressSubject worker : visibleWorkers) {
            WorkerActiveStats activeStats = active.getOrDefault(worker.workerId(), WorkerActiveStats.empty());
            WorkerCompletionStats stats = completed.getOrDefault(worker.workerId(), WorkerCompletionStats.empty());
            WorkerActivityStats activityStats = worker.workerUserId() == null
                    ? WorkerActivityStats.empty()
                    : activity.getOrDefault(worker.workerUserId(), WorkerActivityStats.empty());
            WorkerAuxStats auxStats = aux.getOrDefault(worker.workerId(), WorkerAuxStats.empty());
            Reached100State reached100State = reached100States.getOrDefault(worker.workerId(), Reached100State.empty());
            DailyWorkProgressResponse response = responseForWorker(safeDate, activeStats, stats, activityStats, auxStats, reached100State);
            result.put(worker.workerId(), response);
            if (persist) {
                saveDaily(worker, response);
            }
        }
        if (persist) {
            rebuildMonthly(safeDate.withDayOfMonth(1), false);
        }
        return result;
    }

    static boolean includedInEndOfDay(LocalDateTime addedAt, LocalDateTime cutoff) {
        return cutoff == null || addedAt == null || addedAt.isBefore(cutoff);
    }

    @Transactional
    public void rebuildMonthlyAggregates(LocalDate monthStart, boolean closed) {
        LocalDate safeMonthStart = safeDate(monthStart).withDayOfMonth(1);
        rebuildMonthly(safeMonthStart, closed);
    }

    @Transactional
    public DailyWorkProgressResponse aggregateWorkerProgress(Collection<Worker> workers, LocalDate date) {
        Map<Long, DailyWorkProgressResponse> progress = workerProgressByWorkers(workers, date);
        List<Long> workerIds = workers == null ? List.of() : workers.stream()
                .filter(Objects::nonNull)
                .map(Worker::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return aggregateTeamProgressResponses(progress.values(), workerIds, date, ROLE_WORKER);
    }

    @Transactional
    public Map<Long, DailyWorkProgressResponse> monthlyWorkerProgressBySubjects(
            Collection<WorkerProgressSubject> workers,
            LocalDate monthStart
    ) {
        if (!progressEnabled() || workers == null || workers.isEmpty()) {
            return Map.of();
        }
        LocalDate safeMonthStart = safeDate(monthStart).withDayOfMonth(1);
        List<WorkerProgressSubject> visibleWorkers = workers.stream()
                .filter(Objects::nonNull)
                .filter(worker -> worker.workerId() != null)
                .toList();
        if (visibleWorkers.isEmpty()) {
            return Map.of();
        }

        rebuildMonthly(
                safeMonthStart,
                safeMonthStart.isBefore(progressToday().withDayOfMonth(1))
        );

        List<Long> workerIds = visibleWorkers.stream().map(WorkerProgressSubject::workerId).distinct().toList();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workerIds", workerIds)
                .addValue("monthStart", safeMonthStart);
        Map<Long, DailyWorkProgressResponse> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT *
                FROM worker_performance_monthly
                WHERE month_start = :monthStart
                  AND worker_id IN (:workerIds)
                """, params).forEach(row -> {
            Long workerId = longValue(row.get("worker_id"));
            result.put(workerId, monthlyResponse(safeMonthStart, row));
        });
        return result;
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> averageDailyActiveWorkSecondsByWorkerIds(
            Collection<Long> workerIds,
            LocalDate throughDate
    ) {
        if (!progressEnabled() || workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }
        LocalDate safeThroughDate = safeDate(throughDate);
        LocalDate monthStart = safeThroughDate.withDayOfMonth(1);
        long elapsedDays = ChronoUnit.DAYS.between(monthStart, safeThroughDate) + 1;
        List<Long> visibleWorkerIds = workerIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (visibleWorkerIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workerIds", visibleWorkerIds)
                .addValue("from", monthStart)
                .addValue("to", safeThroughDate.plusDays(1));

        Map<Long, Long> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT worker_id, COALESCE(SUM(active_work_seconds), 0) AS active_work_seconds
                FROM worker_daily_performance
                WHERE worker_id IN (:workerIds)
                  AND progress_date >= :from
                  AND progress_date < :to
                GROUP BY worker_id
                """, params).forEach(row -> result.put(
                longValue(row.get("worker_id")),
                Math.round(longValue(row.get("active_work_seconds")) / (double) elapsedDays)
        ));
        return result;
    }

    public DailyWorkProgressResponse aggregateProgressResponses(
            Collection<DailyWorkProgressResponse> progress,
            LocalDate date,
            String roleType
    ) {
        return aggregateProgressResponses(progress, date, roleType, null);
    }

    public DailyWorkProgressResponse aggregateTeamProgressResponses(
            Collection<DailyWorkProgressResponse> progress,
            Collection<Long> workerIds,
            LocalDate date,
            String roleType
    ) {
        Reached100State teamReached100 = teamReached100State(workerIds, date);
        return aggregateProgressResponses(progress, date, roleType, teamReached100);
    }

    private DailyWorkProgressResponse aggregateProgressResponses(
            Collection<DailyWorkProgressResponse> progress,
            LocalDate date,
            String roleType,
            Reached100State teamReached100
    ) {
        if (progress == null || progress.isEmpty()) {
            return DailyWorkProgressResponse.hidden(roleType == null ? ROLE_WORKER : roleType, safeDate(date));
        }
        LocalDate safeDate = safeDate(date);
        List<DailyWorkProgressResponse> visible = progress.stream()
                .filter(Objects::nonNull)
                .filter(DailyWorkProgressResponse::visible)
                .toList();
        if (visible.isEmpty()) {
            return DailyWorkProgressResponse.hidden(roleType == null ? ROLE_WORKER : roleType, safeDate);
        }

        long completed = visible.stream().mapToLong(DailyWorkProgressResponse::completed).sum();
        long active = visible.stream().mapToLong(DailyWorkProgressResponse::active).sum();
        long total = completed + active;
        long loadScore = visible.stream().mapToLong(DailyWorkProgressResponse::loadScore).sum();
        long averageClose = weightedAverage(visible, DailyWorkProgressResponse::averageCloseSeconds);
        long medianClose = weightedAverage(visible, DailyWorkProgressResponse::medianCloseSeconds);
        long p90Close = weightedAverage(visible, DailyWorkProgressResponse::p90CloseSeconds);
        LocalDateTime firstActivityAt = visible.stream()
                .map(DailyWorkProgressResponse::firstActivityAt)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        LocalDateTime lastActivityAt = visible.stream()
                .map(DailyWorkProgressResponse::lastActivityAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        long activeWorkSeconds = visible.stream().mapToLong(DailyWorkProgressResponse::activeWorkSeconds).sum();
        long workWindowSeconds = secondsBetween(firstActivityAt, lastActivityAt);
        long activityEvents = visible.stream().mapToLong(DailyWorkProgressResponse::activityEvents).sum();
        int percent = total == 0 ? 100 : percentInt(completed, total);
        int efficiency = visible.isEmpty()
                ? 0
                : (int) Math.round(visible.stream().mapToInt(DailyWorkProgressResponse::efficiencyScore).average().orElse(0));
        int speedScore = (int) Math.round(visible.stream().mapToInt(DailyWorkProgressResponse::speedScore).average().orElse(0));
        int disciplineScore = (int) Math.round(visible.stream().mapToInt(DailyWorkProgressResponse::disciplineScore).average().orElse(0));
        int workloadScore = (int) Math.round(visible.stream().mapToInt(DailyWorkProgressResponse::workloadScore).average().orElse(0));
        boolean reached100Now = total > 0 && active == 0;
        boolean reached100 = teamReached100 == null ? reached100Now : teamReached100.reached100();
        LocalDateTime firstReached100At = teamReached100 == null ? null : teamReached100.firstReached100At();
        LocalDateTime lastReached100At = teamReached100 == null ? null : teamReached100.lastReached100At();
        if (reached100Now && !reached100) {
            LocalDateTime reachedAt = safeDate.equals(progressToday()) ? progressNow() : safeDate.plusDays(1).atStartOfDay();
            reached100 = true;
            firstReached100At = reachedAt;
            lastReached100At = reachedAt;
        }

        return new DailyWorkProgressResponse(
                true,
                roleType == null ? ROLE_WORKER : roleType,
                safeDate,
                completed,
                active,
                total,
                percent,
                total == 0 || active == 0,
                visible.stream()
                        .map(DailyWorkProgressResponse::firstCompletedAt)
                        .filter(Objects::nonNull)
                        .min(LocalDateTime::compareTo)
                        .orElse(null),
                visible.stream()
                        .map(DailyWorkProgressResponse::lastCompletedAt)
                        .filter(Objects::nonNull)
                        .max(LocalDateTime::compareTo)
                        .orElse(null),
                averageClose,
                medianClose,
                p90Close,
                firstActivityAt,
                lastActivityAt,
                activeWorkSeconds,
                workWindowSeconds,
                activityEvents,
                loadScore,
                efficiency,
                total,
                visible.stream().mapToLong(DailyWorkProgressResponse::orderCompletedCount).sum(),
                visible.stream().mapToLong(DailyWorkProgressResponse::nagulCompletedCount).sum(),
                visible.stream().mapToLong(DailyWorkProgressResponse::publishCompletedCount).sum(),
                visible.stream().mapToLong(DailyWorkProgressResponse::badCompletedCount).sum(),
                visible.stream().mapToLong(DailyWorkProgressResponse::recoveryCompletedCount).sum(),
                visible.stream().mapToLong(DailyWorkProgressResponse::recoveryCreatedCount).sum(),
                visible.stream().mapToLong(DailyWorkProgressResponse::orderOverdueCount).sum(),
                visible.stream().mapToLong(DailyWorkProgressResponse::totalOverdueCount).sum(),
                speedScore,
                disciplineScore,
                workloadScore,
                visible.stream().mapToLong(DailyWorkProgressResponse::botChangeCount).sum(),
                visible.stream().mapToLong(DailyWorkProgressResponse::botBlockCount).sum(),
                reached100,
                firstReached100At,
                lastReached100At,
                roleType != null && roleType.endsWith("_MONTH") ? "MONTH" : "DAY",
                visible.stream().mapToInt(DailyWorkProgressResponse::workingDays).sum(),
                visible.stream().mapToInt(DailyWorkProgressResponse::checkedDays).sum(),
                visible.stream().mapToInt(DailyWorkProgressResponse::reached100Days).sum(),
                visible.stream().allMatch(DailyWorkProgressResponse::closedPeriod)
        );
    }

    private DailyWorkProgressResponse monthlyResponse(LocalDate monthStart, Map<String, Object> row) {
        long completed = longValue(row.get("completed_count"));
        long active = longValue(row.get("active_count"));
        long total = longValue(row.get("total_count"));
        int workingDays = intValue(row.get("working_days"));
        int checkedDays = intValue(row.get("checked_days"));
        int reached100Days = intValue(row.get("reached_100_days"));
        int percent = intValue(row.get("average_progress_percent"));
        boolean closedPeriod = booleanValue(row.get("closed_period"));
        return new DailyWorkProgressResponse(
                true,
                "WORKER_MONTH",
                monthStart,
                completed,
                active,
                total,
                percent,
                workingDays > 0 && checkedDays >= workingDays,
                null,
                null,
                longValue(row.get("average_close_seconds")),
                longValue(row.get("median_close_seconds")),
                longValue(row.get("p90_close_seconds")),
                null,
                null,
                longValue(row.get("active_work_seconds")),
                longValue(row.get("average_work_window_seconds")),
                longValue(row.get("activity_events")),
                longValue(row.get("load_score")),
                intValue(row.get("average_efficiency_score")),
                longValue(row.get("opened_count")),
                longValue(row.get("order_completed_count")),
                longValue(row.get("nagul_completed_count")),
                longValue(row.get("publish_completed_count")),
                longValue(row.get("bad_completed_count")),
                longValue(row.get("recovery_completed_count")),
                longValue(row.get("recovery_created_count")),
                longValue(row.get("order_overdue_count")),
                longValue(row.get("total_overdue_count")),
                intValue(row.get("average_speed_score")),
                intValue(row.get("average_discipline_score")),
                intValue(row.get("average_workload_score")),
                longValue(row.get("bot_change_count")),
                longValue(row.get("bot_block_count")),
                reached100Days > 0,
                null,
                null,
                "MONTH",
                workingDays,
                checkedDays,
                reached100Days,
                closedPeriod
        );
    }

    private DailyWorkProgressResponse responseForWorker(
            LocalDate date,
            WorkerActiveStats activeStats,
            WorkerCompletionStats stats,
            WorkerActivityStats activityStats,
            WorkerAuxStats auxStats,
            Reached100State previousReached100
    ) {
        WorkerActiveStats safeActiveStats = activeStats == null ? WorkerActiveStats.empty() : activeStats;
        WorkerAuxStats safeAuxStats = auxStats == null ? WorkerAuxStats.empty() : auxStats;
        Reached100State safeReached100 = previousReached100 == null ? Reached100State.empty() : previousReached100;
        long completed = stats.completed();
        long active = Math.max(0, safeActiveStats.active());
        long total = completed + active;
        int percent = total == 0 ? 100 : percentInt(completed, total);
        boolean checked = total == 0 || active == 0;
        boolean reached100Now = total > 0 && active == 0;
        LocalDateTime reached100At = reached100Now
                ? (stats.lastCompletedAt() == null ? evaluationTime(date) : stats.lastCompletedAt())
                : null;
        boolean reached100 = safeReached100.reached100() || reached100Now;
        LocalDateTime firstReached100At = safeReached100.firstReached100At();
        if (firstReached100At == null && reached100Now) {
            firstReached100At = reached100At;
        }
        LocalDateTime lastReached100At = safeReached100.lastReached100At();
        if (reached100Now && reached100At != null && (lastReached100At == null || reached100At.isAfter(lastReached100At))) {
            lastReached100At = reached100At;
        }
        int speedScore = speedScore(stats);
        int disciplineScore = disciplineScore(total, stats.totalOverdue() + safeActiveStats.totalOverdue());
        int workloadScore = workloadScore(completed);
        int efficiencyScore = workerEfficiencyScore(percent, speedScore, disciplineScore, workloadScore);
        WorkerActivityStats safeActivityStats = activityStats == null ? WorkerActivityStats.empty() : activityStats;
        long orderOverdue = stats.orderOverdue() + safeActiveStats.orderOverdue();
        long totalOverdue = stats.totalOverdue() + safeActiveStats.totalOverdue();

        return new DailyWorkProgressResponse(
                true,
                ROLE_WORKER,
                date,
                completed,
                active,
                total,
                percent,
                checked,
                stats.firstCompletedAt(),
                stats.lastCompletedAt(),
                stats.averageSeconds(),
                stats.medianSeconds(),
                stats.p90Seconds(),
                safeActivityStats.firstActivityAt(),
                safeActivityStats.lastActivityAt(),
                safeActivityStats.activeWorkSeconds(),
                safeActivityStats.workWindowSeconds(),
                safeActivityStats.activityEvents(),
                total,
                efficiencyScore,
                total,
                stats.orderCompleted(),
                stats.nagulCompleted(),
                stats.publishCompleted(),
                stats.badCompleted(),
                stats.recoveryCompleted(),
                safeAuxStats.recoveryCreated(),
                orderOverdue,
                totalOverdue,
                speedScore,
                disciplineScore,
                workloadScore,
                safeAuxStats.botChange(),
                safeAuxStats.botBlock(),
                reached100,
                firstReached100At,
                lastReached100At,
                "DAY",
                0,
                0,
                0,
                false
        );
    }

    private List<ActiveWorkItem> activeItems(List<Long> workerIds, LocalDate date) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workerIds", workerIds)
                .addValue("today", date)
                .addValue("nagulDate", date.plusDays(appSettingService.getInt(AppSettingService.NAGUL_LOOKAHEAD_DAYS, 60)));

        List<ActiveWorkItem> result = new ArrayList<>();
        jdbc.queryForList("""
                SELECT active_items.worker_id,
                       active_items.item_type,
                       active_items.item_id,
                       active_items.opened_at,
                       COALESCE(lifecycle.available_at, lifecycle.opened_at, active_items.opened_at) AS added_at
                FROM (
                    SELECT o.order_worker AS worker_id,
                           'order' AS item_type,
                           o.order_id AS item_id,
                           COALESCE(o.order_status_changed_at, TIMESTAMP(o.order_created) + INTERVAL 10 HOUR) AS opened_at
                    FROM orders o
                    JOIN order_statuses s ON s.order_status_id = o.order_status
                    WHERE o.order_worker IN (:workerIds)
                      AND COALESCE(o.order_complete, 0) = 0
                      AND s.order_status_title IN ('Новый', 'Коррекция')
                      AND o.order_waiting_for_client = FALSE

                    UNION ALL

                    SELECT r.review_worker AS worker_id,
                           'review_nagul' AS item_type,
                           r.review_id AS item_id,
                           TIMESTAMP(r.review_publish_date) + INTERVAL 10 HOUR AS opened_at
                    FROM reviews r
                    WHERE r.review_worker IN (:workerIds)
                      AND r.review_publish = 0
                      AND r.review_vigul = 0
                      AND r.review_publish_date <= :nagulDate
                      AND r.review_text IS NOT NULL
                      AND TRIM(r.review_text) <> ''
                      AND LOWER(TRIM(r.review_text)) NOT LIKE 'текст отзыва%'
                      AND LOWER(TRIM(r.review_text)) NOT LIKE 'нужно подставить%'
                      AND LOWER(TRIM(r.review_text)) NOT LIKE 'нужно подсавить%'
                      AND LOWER(TRIM(r.review_text)) NOT LIKE 'подставить текст%'
                      AND LOWER(TRIM(r.review_text)) NOT LIKE 'подсавить текст%'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM order_details recovery_od
                          JOIN review_recovery_batches recovery_batch
                            ON recovery_batch.review_recovery_batch_order = recovery_od.order_detail_order
                          JOIN review_recovery_tasks recovery_task
                            ON recovery_task.review_recovery_task_batch = recovery_batch.review_recovery_batch_id
                          WHERE recovery_od.order_detail_id = r.review_order_details
                            AND recovery_batch.review_recovery_batch_status = 'OPEN'
                            AND recovery_task.review_recovery_task_status = 'PLANNED'
                      )

                    UNION ALL

                    SELECT r.review_worker AS worker_id,
                           'review_publish' AS item_type,
                           r.review_id AS item_id,
                           TIMESTAMP(r.review_publish_date) + INTERVAL 10 HOUR AS opened_at
                    FROM reviews r
                    WHERE r.review_worker IN (:workerIds)
                      AND r.review_publish = 0
                      AND r.review_vigul = 1
                      AND r.review_publish_date <= :today
                      AND r.review_text IS NOT NULL
                      AND TRIM(r.review_text) <> ''
                      AND LOWER(TRIM(r.review_text)) NOT LIKE 'текст отзыва%'
                      AND LOWER(TRIM(r.review_text)) NOT LIKE 'нужно подставить%'
                      AND LOWER(TRIM(r.review_text)) NOT LIKE 'нужно подсавить%'
                      AND LOWER(TRIM(r.review_text)) NOT LIKE 'подставить текст%'
                      AND LOWER(TRIM(r.review_text)) NOT LIKE 'подсавить текст%'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM order_details recovery_od
                          JOIN review_recovery_batches recovery_batch
                            ON recovery_batch.review_recovery_batch_order = recovery_od.order_detail_order
                          JOIN review_recovery_tasks recovery_task
                            ON recovery_task.review_recovery_task_batch = recovery_batch.review_recovery_batch_id
                          WHERE recovery_od.order_detail_id = r.review_order_details
                            AND recovery_batch.review_recovery_batch_status = 'OPEN'
                            AND recovery_task.review_recovery_task_status = 'PLANNED'
                      )

                    UNION ALL

                    SELECT t.bad_review_task_worker AS worker_id,
                           'bad_task' AS item_type,
                           t.bad_review_task_id AS item_id,
                           TIMESTAMP(t.bad_review_task_scheduled_date) + INTERVAL 10 HOUR AS opened_at
                    FROM bad_review_tasks t
                    WHERE t.bad_review_task_worker IN (:workerIds)
                      AND t.bad_review_task_status = 'NEW'
                      AND t.bad_review_task_scheduled_date <= :today

                    UNION ALL

                    SELECT t.review_recovery_task_worker AS worker_id,
                           'recovery_task' AS item_type,
                           t.review_recovery_task_id AS item_id,
                           COALESCE(t.review_recovery_task_created_at, TIMESTAMP(t.review_recovery_task_scheduled_date) + INTERVAL 10 HOUR) AS opened_at
                    FROM review_recovery_tasks t
                    JOIN review_recovery_batches b ON b.review_recovery_batch_id = t.review_recovery_task_batch
                    WHERE t.review_recovery_task_worker IN (:workerIds)
                      AND t.review_recovery_task_status = 'PLANNED'
                      AND b.review_recovery_batch_status = 'OPEN'
                      AND t.review_recovery_task_scheduled_date <= :today
                ) active_items
                LEFT JOIN worker_work_item_lifecycle lifecycle
                  ON lifecycle.work_item_key = CONCAT(active_items.item_type, ':', active_items.item_id)
                WHERE active_items.worker_id IS NOT NULL
                """, params).forEach(row -> result.add(new ActiveWorkItem(
                longValue(row.get("worker_id")),
                stringValue(row.get("item_type")),
                longValue(row.get("item_id")),
                toLocalDateTime(row.get("opened_at")),
                toLocalDateTime(row.get("added_at"))
        )));
        return result;
    }

    private Map<Long, WorkerActiveStats> activeStats(List<ActiveWorkItem> items, LocalDate date) {
        Map<Long, WorkerActiveStats.Mutable> mutable = new HashMap<>();
        LocalDateTime evaluationAt = evaluationTime(date);
        if (items != null) {
            for (ActiveWorkItem item : items) {
                if (item == null || item.workerId() == null || item.workerId() <= 0) {
                    continue;
                }
                WorkerActiveStats.Mutable stats = mutable.computeIfAbsent(item.workerId(), ignored -> new WorkerActiveStats.Mutable());
                stats.active++;
                LocalDateTime dueAt = dueAt(item.itemType(), item.openedAt());
                if (dueAt != null && evaluationAt.isAfter(dueAt)) {
                    stats.totalOverdue++;
                    if (TYPE_ORDER.equals(item.itemType())) {
                        stats.orderOverdue++;
                    }
                }
            }
        }
        Map<Long, WorkerActiveStats> result = new HashMap<>();
        mutable.forEach((workerId, stats) -> result.put(workerId, stats.toStats()));
        return result;
    }

    private Map<Long, WorkerCompletionStats> completionStats(
            List<Long> workerIds,
            LocalDate date,
            LocalDateTime ignoreOpenedAtOrAfter
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workerIds", workerIds)
                .addValue("from", date.atStartOfDay())
                .addValue("to", date.plusDays(1).atStartOfDay())
                .addValue("date", date);

        Map<Long, List<CompletedItem>> rowsByWorker = new HashMap<>();
        jdbc.queryForList("""
                SELECT completed_items.worker_id,
                       completed_items.item_type,
                       completed_items.item_id,
                       completed_items.opened_at,
                       completed_items.done_at,
                       COALESCE(lifecycle.available_at, lifecycle.opened_at, completed_items.opened_at) AS added_at
                FROM (
                    SELECT o.order_worker AS worker_id,
                           'order' AS item_type,
                           e.order_id AS item_id,
                           COALESCE((
                               SELECT MAX(prev.created_at)
                               FROM business_audit_events prev
                               WHERE prev.order_id = e.order_id
                                 AND prev.action = 'order_status_changed'
                                 AND prev.new_value = e.old_value
                                 AND prev.created_at < e.created_at
                           ), TIMESTAMP(o.order_created) + INTERVAL 10 HOUR) AS opened_at,
                           e.created_at AS done_at
                    FROM business_audit_events e
                    JOIN orders o ON o.order_id = e.order_id
                    WHERE o.order_worker IN (:workerIds)
                      AND e.action = 'order_status_changed'
                      AND e.created_at >= :from
                      AND e.created_at < :to
                      AND e.old_value IN ('Новый', 'Коррекция')
                      AND COALESCE(e.new_value, '') NOT IN ('Новый', 'Коррекция')
                      AND o.order_waiting_for_client = FALSE

                    UNION ALL

                    SELECT r.review_worker AS worker_id,
                           'review_nagul' AS item_type,
                           r.review_id AS item_id,
                           GREATEST(
                               COALESCE(TIMESTAMP(r.review_publish_date) + INTERVAL 10 HOUR, e.created_at),
                               COALESCE((
                                   SELECT MAX(recovery_batch.review_recovery_batch_completed_at)
                                   FROM order_details recovery_od
                                   JOIN review_recovery_batches recovery_batch
                                     ON recovery_batch.review_recovery_batch_order = recovery_od.order_detail_order
                                   WHERE recovery_od.order_detail_id = r.review_order_details
                                     AND recovery_batch.review_recovery_batch_completed_at IS NOT NULL
                                     AND recovery_batch.review_recovery_batch_completed_at <= e.created_at
                               ), COALESCE(TIMESTAMP(r.review_publish_date) + INTERVAL 10 HOUR, e.created_at))
                           ) AS opened_at,
                           e.created_at AS done_at
                    FROM worker_activity_events e
                    JOIN reviews r ON r.review_id = e.review_id
                    WHERE r.review_worker IN (:workerIds)
                      AND e.action = 'REVIEW_NAGUL'
                      AND e.created_at >= :from
                      AND e.created_at < :to

                    UNION ALL

                    SELECT r.review_worker AS worker_id,
                           'review_publish' AS item_type,
                           r.review_id AS item_id,
                           GREATEST(
                               COALESCE(TIMESTAMP(r.review_publish_date) + INTERVAL 10 HOUR, COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed))),
                               COALESCE((
                                   SELECT MAX(recovery_batch.review_recovery_batch_completed_at)
                                   FROM order_details recovery_od
                                   JOIN review_recovery_batches recovery_batch
                                     ON recovery_batch.review_recovery_batch_order = recovery_od.order_detail_order
                                   WHERE recovery_od.order_detail_id = r.review_order_details
                                     AND recovery_batch.review_recovery_batch_completed_at IS NOT NULL
                                     AND recovery_batch.review_recovery_batch_completed_at <= COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed))
                               ), COALESCE(TIMESTAMP(r.review_publish_date) + INTERVAL 10 HOUR, COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed))))
                           ) AS opened_at,
                           COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed)) AS done_at
                    FROM reviews r
                    WHERE r.review_worker IN (:workerIds)
                      AND r.review_publish = 1
                      AND COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed)) >= :from
                      AND COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed)) < :to

                    UNION ALL

                    SELECT t.bad_review_task_worker AS worker_id,
                           'bad_task' AS item_type,
                           t.bad_review_task_id AS item_id,
                           TIMESTAMP(t.bad_review_task_scheduled_date) + INTERVAL 10 HOUR AS opened_at,
                           COALESCE((
                               SELECT MAX(e.created_at)
                               FROM worker_activity_events e
                               WHERE e.entity_type = 'bad_review_task'
                                 AND e.entity_id = t.bad_review_task_id
                                 AND e.action = 'BAD_TASK_COMPLETE'
                                 AND e.created_at >= :from
                                 AND e.created_at < :to
                           ), TIMESTAMP(t.bad_review_task_completed_date) + INTERVAL 10 HOUR) AS done_at
                    FROM bad_review_tasks t
                    WHERE t.bad_review_task_worker IN (:workerIds)
                      AND t.bad_review_task_status = 'DONE'
                      AND t.bad_review_task_completed_date = :date

                    UNION ALL

                    SELECT t.review_recovery_task_worker AS worker_id,
                           'recovery_task' AS item_type,
                           t.review_recovery_task_id AS item_id,
                           COALESCE(t.review_recovery_task_created_at, TIMESTAMP(t.review_recovery_task_scheduled_date) + INTERVAL 10 HOUR) AS opened_at,
                           COALESCE((
                               SELECT MAX(e.created_at)
                               FROM worker_activity_events e
                               WHERE e.entity_type = 'recovery_task'
                                 AND e.entity_id = t.review_recovery_task_id
                                 AND e.action = 'RECOVERY_TASK_COMPLETE'
                                 AND e.created_at >= :from
                                 AND e.created_at < :to
                           ), TIMESTAMP(t.review_recovery_task_completed_date) + INTERVAL 10 HOUR) AS done_at
                    FROM review_recovery_tasks t
                    WHERE t.review_recovery_task_worker IN (:workerIds)
                      AND t.review_recovery_task_status = 'DONE'
                      AND t.review_recovery_task_completed_date = :date
                ) completed_items
                LEFT JOIN worker_work_item_lifecycle lifecycle
                  ON lifecycle.work_item_key = CONCAT(completed_items.item_type, ':', completed_items.item_id)
                WHERE completed_items.worker_id IS NOT NULL
                """, params).forEach(row -> {
            CompletedItem item = new CompletedItem(
                        longValue(row.get("worker_id")),
                        stringValue(row.get("item_type")),
                        longValue(row.get("item_id")),
                        toLocalDateTime(row.get("opened_at")),
                        toLocalDateTime(row.get("added_at")),
                        toLocalDateTime(row.get("done_at")),
                        effectiveSeconds(toLocalDateTime(row.get("opened_at")), toLocalDateTime(row.get("done_at"))),
                        isOverdue(stringValue(row.get("item_type")), toLocalDateTime(row.get("opened_at")), toLocalDateTime(row.get("done_at")))
                );
            if (includedInEndOfDay(item.addedAt(), ignoreOpenedAtOrAfter)) {
                rowsByWorker.computeIfAbsent(item.workerId(), ignored -> new ArrayList<>()).add(item);
            }
        });

        Map<Long, WorkerCompletionStats> result = new HashMap<>();
        for (Map.Entry<Long, List<CompletedItem>> entry : rowsByWorker.entrySet()) {
            result.put(entry.getKey(), WorkerCompletionStats.from(entry.getValue()));
        }
        return result;
    }

    private Map<Long, WorkerActivityStats> activityStats(List<Long> workerUserIds, LocalDate date) {
        if (workerUserIds == null || workerUserIds.isEmpty()) {
            return Map.of();
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workerUserIds", workerUserIds)
                .addValue("from", date.atStartOfDay())
                .addValue("to", date.plusDays(1).atStartOfDay());
        Map<Long, List<LocalDateTime>> rowsByWorker = new HashMap<>();
        jdbc.queryForList("""
                SELECT worker_user_id, created_at
                FROM worker_activity_events
                WHERE worker_user_id IN (:workerUserIds)
                  AND created_at >= :from
                  AND created_at < :to
                ORDER BY worker_user_id, created_at
                """, params).forEach(row -> {
            Long workerUserId = longValue(row.get("worker_user_id"));
            LocalDateTime createdAt = toLocalDateTime(row.get("created_at"));
            if (workerUserId != null && workerUserId > 0 && createdAt != null) {
                rowsByWorker.computeIfAbsent(workerUserId, ignored -> new ArrayList<>()).add(createdAt);
            }
        });

        long sessionGapMinutes = Math.max(5, appSettingService.getInt(
                AppSettingService.WORKER_PROGRESS_ACTIVITY_SESSION_GAP_MINUTES,
                15
        ));
        Map<Long, WorkerActivityStats> result = new HashMap<>();
        for (Map.Entry<Long, List<LocalDateTime>> entry : rowsByWorker.entrySet()) {
            result.put(entry.getKey(), WorkerActivityStats.from(entry.getValue(), sessionGapMinutes));
        }
        return result;
    }

    private Map<Long, WorkerAuxStats> auxStats(
            List<Long> workerIds,
            List<Long> workerUserIds,
            Map<Long, Long> workerUserIdByWorkerId,
            LocalDate date
    ) {
        Map<Long, WorkerAuxStats.Mutable> mutable = new HashMap<>();
        if (workerIds != null && !workerIds.isEmpty()) {
            MapSqlParameterSource recoveryParams = new MapSqlParameterSource()
                    .addValue("workerIds", workerIds)
                    .addValue("from", date.atStartOfDay())
                    .addValue("to", date.plusDays(1).atStartOfDay());
            jdbc.queryForList("""
                    SELECT review_recovery_task_worker AS worker_id, COUNT(*) AS created_count
                    FROM review_recovery_tasks
                    WHERE review_recovery_task_worker IN (:workerIds)
                      AND review_recovery_task_created_at >= :from
                      AND review_recovery_task_created_at < :to
                    GROUP BY review_recovery_task_worker
                    """, recoveryParams).forEach(row -> mutable
                    .computeIfAbsent(longValue(row.get("worker_id")), ignored -> new WorkerAuxStats.Mutable())
                    .recoveryCreated += longValue(row.get("created_count")));
        }

        if (workerUserIds != null && !workerUserIds.isEmpty()) {
            Map<Long, Long> workerIdByUserId = new HashMap<>();
            if (workerUserIdByWorkerId != null) {
                workerUserIdByWorkerId.forEach((workerId, userId) -> {
                    if (workerId != null && userId != null) {
                        workerIdByUserId.put(userId, workerId);
                    }
                });
            }
            List<String> botActions = new ArrayList<>();
            botActions.addAll(BOT_CHANGE_ACTIONS);
            botActions.addAll(BOT_BLOCK_ACTIONS);
            MapSqlParameterSource botParams = new MapSqlParameterSource()
                    .addValue("workerUserIds", workerUserIds)
                    .addValue("from", date.atStartOfDay())
                    .addValue("to", date.plusDays(1).atStartOfDay())
                    .addValue("changeActions", BOT_CHANGE_ACTIONS)
                    .addValue("blockActions", BOT_BLOCK_ACTIONS)
                    .addValue("botActions", botActions);
            jdbc.queryForList("""
                    SELECT worker_user_id,
                           SUM(CASE WHEN action IN (:changeActions) THEN 1 ELSE 0 END) AS bot_change_count,
                           COUNT(DISTINCT CASE
                               WHEN action IN (:blockActions)
                               THEN SUBSTRING_INDEX(SUBSTRING_INDEX(details, 'botId=', -1), ';', 1)
                               ELSE NULL
                           END) AS bot_block_count
                    FROM worker_activity_events
                    WHERE worker_user_id IN (:workerUserIds)
                      AND created_at >= :from
                      AND created_at < :to
                      AND action IN (:botActions)
                    GROUP BY worker_user_id
                    """, botParams).forEach(row -> {
                Long workerUserId = longValue(row.get("worker_user_id"));
                Long workerId = workerIdByUserId.get(workerUserId);
                if (workerId == null) {
                    return;
                }
                WorkerAuxStats.Mutable stats = mutable.computeIfAbsent(workerId, ignored -> new WorkerAuxStats.Mutable());
                stats.botChange += longValue(row.get("bot_change_count"));
                stats.botBlock += longValue(row.get("bot_block_count"));
            });
        }

        Map<Long, WorkerAuxStats> result = new HashMap<>();
        mutable.forEach((workerId, stats) -> result.put(workerId, stats.toStats()));
        return result;
    }

    private Map<Long, Reached100State> reached100States(List<Long> workerIds, LocalDate date) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workerIds", workerIds)
                .addValue("date", date);
        Map<Long, Reached100State> result = new HashMap<>();
        jdbc.queryForList("""
                SELECT worker_id, reached_100, first_reached_100_at, last_reached_100_at
                FROM worker_daily_performance
                WHERE progress_date = :date
                  AND worker_id IN (:workerIds)
                """, params).forEach(row -> result.put(
                longValue(row.get("worker_id")),
                new Reached100State(
                        booleanValue(row.get("reached_100")),
                        toLocalDateTime(row.get("first_reached_100_at")),
                        toLocalDateTime(row.get("last_reached_100_at"))
                )
        ));
        return result;
    }

    /**
     * Reconstructs moments when the whole team's queue was empty. Individual
     * reached_100 flags cannot be aggregated because workers may have cleared
     * their queues at different times.
     */
    private Reached100State teamReached100State(Collection<Long> workerIds, LocalDate date) {
        List<Long> safeWorkerIds = workerIds == null ? List.of() : workerIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (safeWorkerIds.isEmpty()) {
            return Reached100State.empty();
        }

        LocalDate safeDate = safeDate(date);
        LocalDateTime from = safeDate.atStartOfDay();
        LocalDateTime now = progressNow();
        if (safeDate.isAfter(now.toLocalDate())) {
            return Reached100State.empty();
        }
        LocalDateTime to = safeDate.equals(now.toLocalDate()) ? now : safeDate.plusDays(1).atStartOfDay();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workerIds", safeWorkerIds)
                .addValue("from", from)
                .addValue("to", to);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT opened_at, closed_at
                FROM worker_work_item_lifecycle
                WHERE worker_id IN (:workerIds)
                  AND excluded = 0
                  AND opened_at < :to
                  AND (closed_at IS NULL OR closed_at > :from)
                """, params);
        return teamReached100State(rows, from, to);
    }

    static Reached100State teamReached100State(
            Collection<Map<String, Object>> lifecycleRows,
            LocalDateTime from,
            LocalDateTime to
    ) {
        if (lifecycleRows == null || lifecycleRows.isEmpty() || from == null || to == null || !to.isAfter(from)) {
            return Reached100State.empty();
        }

        long active = 0;
        boolean workSeen = false;
        Map<LocalDateTime, Long> changes = new TreeMap<>();
        for (Map<String, Object> row : lifecycleRows) {
            LocalDateTime openedAt = toLocalDateTime(row.get("opened_at"));
            LocalDateTime closedAt = toLocalDateTime(row.get("closed_at"));
            if (openedAt == null || !openedAt.isBefore(to) || (closedAt != null && !closedAt.isAfter(from))) {
                continue;
            }
            if (openedAt.isBefore(from)) {
                active++;
                workSeen = true;
            } else {
                changes.merge(openedAt, 1L, Long::sum);
            }
            if (closedAt != null && closedAt.isAfter(from) && !closedAt.isAfter(to)) {
                changes.merge(closedAt, -1L, Long::sum);
            }
        }

        LocalDateTime firstReached100At = null;
        LocalDateTime lastReached100At = null;
        for (Map.Entry<LocalDateTime, Long> change : changes.entrySet()) {
            long before = active;
            active = Math.max(0, active + change.getValue());
            if (change.getValue() > 0) {
                workSeen = true;
            }
            if (workSeen && before > 0 && active == 0) {
                if (firstReached100At == null) {
                    firstReached100At = change.getKey();
                }
                lastReached100At = change.getKey();
            }
        }
        return new Reached100State(firstReached100At != null, firstReached100At, lastReached100At);
    }

    private void syncLifecycle(
            List<ActiveWorkItem> activeItems,
            Map<Long, WorkerCompletionStats> completionStats,
            Map<Long, Long> workerUserIdByWorkerId
    ) {
        List<MapSqlParameterSource> batch = new ArrayList<>();
        if (activeItems != null) {
            for (ActiveWorkItem item : activeItems) {
                if (item == null || item.workerId() == null || item.itemId() == null || item.itemId() <= 0) {
                    continue;
                }
                batch.add(lifecycleParams(
                        item.workerId(),
                        workerUserIdByWorkerId.get(item.workerId()),
                        item.itemType(),
                        item.itemId(),
                        item.openedAt(),
                        null,
                        true,
                        0,
                        false
                ));
            }
        }
        if (completionStats != null) {
            completionStats.values().stream()
                    .filter(Objects::nonNull)
                    .flatMap(stats -> stats.items().stream())
                    .filter(item -> item.workerId() != null && item.itemId() != null && item.itemId() > 0)
                    .forEach(item -> batch.add(lifecycleParams(
                            item.workerId(),
                            workerUserIdByWorkerId.get(item.workerId()),
                            item.itemType(),
                            item.itemId(),
                            item.openedAt(),
                            item.doneAt(),
                            false,
                            item.durationSeconds(),
                            item.overdue()
                    )));
        }
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO worker_work_item_lifecycle (
                    work_item_key, worker_id, worker_user_id, section_code, item_type, item_id,
                    opened_at, available_at, due_at, closed_at, effective_close_seconds,
                    active, overdue, excluded, exclusion_reason
                )
                VALUES (
                    :workItemKey, :workerId, :workerUserId, :sectionCode, :itemType, :itemId,
                    :openedAt, :availableAt, :dueAt, :closedAt, :effectiveCloseSeconds,
                    :active, :overdue, 0, NULL
                )
                ON DUPLICATE KEY UPDATE
                    worker_id = VALUES(worker_id),
                    worker_user_id = VALUES(worker_user_id),
                    section_code = VALUES(section_code),
                    opened_at = CASE
                        WHEN excluded = 1 AND exclusion_reason = 'waiting_for_client' THEN VALUES(opened_at)
                        ELSE LEAST(opened_at, VALUES(opened_at))
                    END,
                    available_at = CASE
                        WHEN excluded = 1 AND exclusion_reason = 'waiting_for_client' THEN VALUES(available_at)
                        ELSE COALESCE(available_at, VALUES(available_at))
                    END,
                    due_at = VALUES(due_at),
                    closed_at = VALUES(closed_at),
                    effective_close_seconds = VALUES(effective_close_seconds),
                    active = VALUES(active),
                    overdue = VALUES(overdue),
                    excluded = 0,
                    exclusion_reason = NULL
                """, batch.toArray(MapSqlParameterSource[]::new));
    }

    private void excludeWaitingClientOrdersFromLifecycle(List<Long> workerIds) {
        if (workerIds == null || workerIds.isEmpty()) {
            return;
        }
        jdbc.update("""
                UPDATE worker_work_item_lifecycle lifecycle
                JOIN orders orders_waiting
                  ON lifecycle.item_type = 'order'
                 AND lifecycle.item_id = orders_waiting.order_id
                SET lifecycle.active = 0,
                    lifecycle.overdue = 0,
                    lifecycle.excluded = 1,
                    lifecycle.exclusion_reason = 'waiting_for_client'
                WHERE orders_waiting.order_worker IN (:workerIds)
                  AND orders_waiting.order_waiting_for_client = TRUE
                """, new MapSqlParameterSource("workerIds", workerIds));
    }

    private MapSqlParameterSource lifecycleParams(
            Long workerId,
            Long workerUserId,
            String itemType,
            Long itemId,
            LocalDateTime openedAt,
            LocalDateTime closedAt,
            boolean active,
            long effectiveCloseSeconds,
            boolean overdue
    ) {
        LocalDateTime safeOpenedAt = openedAt == null ? progressNow() : openedAt;
        return new MapSqlParameterSource()
                .addValue("workItemKey", itemType + ":" + itemId)
                .addValue("workerId", workerId)
                .addValue("workerUserId", workerUserId)
                .addValue("sectionCode", sectionCode(itemType))
                .addValue("itemType", itemType)
                .addValue("itemId", itemId)
                .addValue("openedAt", safeOpenedAt)
                .addValue("availableAt", safeOpenedAt)
                .addValue("dueAt", dueAt(itemType, safeOpenedAt))
                .addValue("closedAt", closedAt)
                .addValue("effectiveCloseSeconds", Math.max(0, effectiveCloseSeconds))
                .addValue("active", active)
                .addValue("overdue", overdue);
    }

    private String sectionCode(String itemType) {
        return switch (itemType) {
            case TYPE_NAGUL -> "nagul";
            case TYPE_PUBLISH -> "publish";
            case TYPE_BAD -> "bad";
            case TYPE_RECOVERY -> "recovery";
            default -> "order";
        };
    }

    private void saveDaily(WorkerProgressSubject worker, DailyWorkProgressResponse response) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("date", response.date())
                .addValue("workerId", worker.workerId())
                .addValue("workerUserId", worker.workerUserId())
                .addValue("workerName", worker.workerName())
                .addValue("active", response.active())
                .addValue("completed", response.completed())
                .addValue("total", response.total())
                .addValue("openedCount", response.openedCount())
                .addValue("percent", BigDecimal.valueOf(response.percent()).setScale(2, RoundingMode.HALF_UP))
                .addValue("checked", response.checked())
                .addValue("reached100", response.reached100())
                .addValue("firstReached100At", response.firstReached100At())
                .addValue("lastReached100At", response.lastReached100At())
                .addValue("orderCompletedCount", response.orderCompletedCount())
                .addValue("nagulCompletedCount", response.nagulCompletedCount())
                .addValue("publishCompletedCount", response.publishCompletedCount())
                .addValue("badCompletedCount", response.badCompletedCount())
                .addValue("recoveryCompletedCount", response.recoveryCompletedCount())
                .addValue("recoveryCreatedCount", response.recoveryCreatedCount())
                .addValue("orderOverdueCount", response.orderOverdueCount())
                .addValue("totalOverdueCount", response.totalOverdueCount())
                .addValue("firstCompletedAt", response.firstCompletedAt())
                .addValue("lastCompletedAt", response.lastCompletedAt())
                .addValue("averageCloseSeconds", response.averageCloseSeconds())
                .addValue("medianCloseSeconds", response.medianCloseSeconds())
                .addValue("p90CloseSeconds", response.p90CloseSeconds())
                .addValue("speedScore", response.speedScore())
                .addValue("disciplineScore", response.disciplineScore())
                .addValue("workloadScore", response.workloadScore())
                .addValue("firstActivityAt", response.firstActivityAt())
                .addValue("lastActivityAt", response.lastActivityAt())
                .addValue("activeWorkSeconds", response.activeWorkSeconds())
                .addValue("workWindowSeconds", response.workWindowSeconds())
                .addValue("activityEvents", response.activityEvents())
                .addValue("botChangeCount", response.botChangeCount())
                .addValue("botBlockCount", response.botBlockCount())
                .addValue("loadScore", response.loadScore())
                .addValue("efficiencyScore", response.efficiencyScore());
        jdbc.update("""
                INSERT INTO worker_daily_performance (
                    progress_date, worker_id, worker_user_id, worker_name,
                    active_count, completed_count, total_count, opened_count, progress_percent, checked,
                    reached_100, first_reached_100_at, last_reached_100_at,
                    order_completed_count, nagul_completed_count, publish_completed_count,
                    bad_completed_count, recovery_completed_count, recovery_created_count,
                    order_overdue_count, total_overdue_count,
                    first_completed_at, last_completed_at, average_close_seconds, median_close_seconds,
                    p90_close_seconds, speed_score, discipline_score, workload_score,
                    first_activity_at, last_activity_at, active_work_seconds,
                    work_window_seconds, activity_events, bot_change_count, bot_block_count,
                    load_score, efficiency_score, aggregation_status
                )
                VALUES (
                    :date, :workerId, :workerUserId, :workerName,
                    :active, :completed, :total, :openedCount, :percent, :checked,
                    :reached100, :firstReached100At, :lastReached100At,
                    :orderCompletedCount, :nagulCompletedCount, :publishCompletedCount,
                    :badCompletedCount, :recoveryCompletedCount, :recoveryCreatedCount,
                    :orderOverdueCount, :totalOverdueCount,
                    :firstCompletedAt, :lastCompletedAt, :averageCloseSeconds, :medianCloseSeconds,
                    :p90CloseSeconds, :speedScore, :disciplineScore, :workloadScore,
                    :firstActivityAt, :lastActivityAt, :activeWorkSeconds,
                    :workWindowSeconds, :activityEvents, :botChangeCount, :botBlockCount,
                    :loadScore, :efficiencyScore, 'CALCULATED'
                )
                ON DUPLICATE KEY UPDATE
                    worker_user_id = VALUES(worker_user_id),
                    worker_name = VALUES(worker_name),
                    active_count = VALUES(active_count),
                    completed_count = VALUES(completed_count),
                    total_count = VALUES(total_count),
                    opened_count = VALUES(opened_count),
                    progress_percent = VALUES(progress_percent),
                    checked = VALUES(checked),
                    reached_100 = CASE
                        WHEN worker_daily_performance.reached_100 = 1 OR VALUES(reached_100) = 1 THEN 1
                        ELSE 0
                    END,
                    first_reached_100_at = CASE
                        WHEN VALUES(reached_100) = 1 THEN COALESCE(worker_daily_performance.first_reached_100_at, VALUES(first_reached_100_at))
                        ELSE worker_daily_performance.first_reached_100_at
                    END,
                    last_reached_100_at = CASE
                        WHEN VALUES(reached_100) = 1 THEN
                            CASE
                                WHEN worker_daily_performance.last_reached_100_at IS NULL THEN VALUES(last_reached_100_at)
                                WHEN VALUES(last_reached_100_at) IS NULL THEN worker_daily_performance.last_reached_100_at
                                WHEN VALUES(last_reached_100_at) > worker_daily_performance.last_reached_100_at THEN VALUES(last_reached_100_at)
                                ELSE worker_daily_performance.last_reached_100_at
                            END
                        ELSE worker_daily_performance.last_reached_100_at
                    END,
                    order_completed_count = VALUES(order_completed_count),
                    nagul_completed_count = VALUES(nagul_completed_count),
                    publish_completed_count = VALUES(publish_completed_count),
                    bad_completed_count = VALUES(bad_completed_count),
                    recovery_completed_count = VALUES(recovery_completed_count),
                    recovery_created_count = VALUES(recovery_created_count),
                    order_overdue_count = VALUES(order_overdue_count),
                    total_overdue_count = VALUES(total_overdue_count),
                    first_completed_at = VALUES(first_completed_at),
                    last_completed_at = VALUES(last_completed_at),
                    average_close_seconds = VALUES(average_close_seconds),
                    median_close_seconds = VALUES(median_close_seconds),
                    p90_close_seconds = VALUES(p90_close_seconds),
                    speed_score = VALUES(speed_score),
                    discipline_score = VALUES(discipline_score),
                    workload_score = VALUES(workload_score),
                    first_activity_at = VALUES(first_activity_at),
                    last_activity_at = VALUES(last_activity_at),
                    active_work_seconds = VALUES(active_work_seconds),
                    work_window_seconds = VALUES(work_window_seconds),
                    activity_events = VALUES(activity_events),
                    bot_change_count = VALUES(bot_change_count),
                    bot_block_count = VALUES(bot_block_count),
                    load_score = VALUES(load_score),
                    efficiency_score = VALUES(efficiency_score),
                    aggregation_status = VALUES(aggregation_status)
                """, params);
    }

    private void rebuildMonthly(LocalDate monthStart, boolean closed) {
        if (!appSettingService.getBoolean(AppSettingService.WORKER_PROGRESS_MONTHLY_AGGREGATE_ENABLED, true)) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("monthStart", monthStart)
                .addValue("nextMonth", monthStart.plusMonths(1))
                .addValue("closed", closed);
        jdbc.update("""
                INSERT INTO worker_performance_monthly (
                    month_start, worker_id, worker_user_id, working_days, completed_count, active_count,
                    total_count, opened_count, average_progress_percent, checked_days, reached_100_days,
                    order_completed_count, nagul_completed_count, publish_completed_count,
                    bad_completed_count, recovery_completed_count, recovery_created_count,
                    order_overdue_count, total_overdue_count,
                    average_close_seconds, median_close_seconds, p90_close_seconds,
                    average_speed_score, average_discipline_score, average_workload_score,
                    active_work_seconds, average_work_window_seconds,
                    activity_events, bot_change_count, bot_block_count,
                    load_score, average_efficiency_score, closed_period
                )
                SELECT :monthStart,
                       d.worker_id,
                       MAX(d.worker_user_id),
                       COUNT(*),
                       SUM(d.completed_count),
                       SUM(d.active_count),
                       SUM(d.total_count),
                       SUM(d.opened_count),
                       AVG(d.progress_percent),
                       SUM(CASE WHEN d.checked = 1 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN d.reached_100 = 1 THEN 1 ELSE 0 END),
                       SUM(d.order_completed_count),
                       SUM(d.nagul_completed_count),
                       CASE WHEN :closed THEN SUM(d.publish_completed_count) ELSE (
                           SELECT COUNT(*)
                           FROM reviews monthly_review
                           WHERE monthly_review.review_worker = d.worker_id
                             AND monthly_review.review_publish = 1
                             AND COALESCE(monthly_review.review_published_marked_at, TIMESTAMP(monthly_review.review_changed)) >= :monthStart
                             AND COALESCE(monthly_review.review_published_marked_at, TIMESTAMP(monthly_review.review_changed)) < :nextMonth
                       ) END,
                       SUM(d.bad_completed_count),
                       SUM(d.recovery_completed_count),
                       CASE WHEN :closed THEN SUM(d.recovery_created_count) ELSE (
                           SELECT COUNT(*)
                           FROM review_recovery_tasks monthly_recovery
                           WHERE monthly_recovery.review_recovery_task_worker = d.worker_id
                             AND monthly_recovery.review_recovery_task_created_at >= :monthStart
                             AND monthly_recovery.review_recovery_task_created_at < :nextMonth
                       ) END,
                       SUM(d.order_overdue_count),
                       SUM(d.total_overdue_count),
                       AVG(d.average_close_seconds),
                       AVG(d.median_close_seconds),
                       AVG(d.p90_close_seconds),
                       AVG(d.speed_score),
                       AVG(d.discipline_score),
                       AVG(d.workload_score),
                       SUM(d.active_work_seconds),
                       AVG(d.work_window_seconds),
                       SUM(d.activity_events),
                       CASE WHEN :closed THEN SUM(d.bot_change_count) ELSE (
                           SELECT COUNT(*)
                           FROM worker_activity_events monthly_change
                           WHERE monthly_change.worker_user_id = (
                               SELECT monthly_worker.user_id
                               FROM workers monthly_worker
                               WHERE monthly_worker.worker_id = d.worker_id
                           )
                             AND monthly_change.created_at >= :monthStart
                             AND monthly_change.created_at < :nextMonth
                             AND monthly_change.action IN ('REVIEW_BOT_CHANGE', 'BAD_TASK_BOT_CHANGE', 'RECOVERY_TASK_BOT_CHANGE')
                       ) END,
                       CASE WHEN :closed THEN SUM(d.bot_block_count) ELSE (
                           SELECT COUNT(*)
                           FROM worker_activity_events monthly_block
                           WHERE monthly_block.worker_user_id = (
                               SELECT monthly_worker.user_id
                               FROM workers monthly_worker
                               WHERE monthly_worker.worker_id = d.worker_id
                           )
                             AND monthly_block.created_at >= :monthStart
                             AND monthly_block.created_at < :nextMonth
                             AND monthly_block.action = 'REVIEW_BOT_DEACTIVATE'
                       ) END,
                       SUM(d.load_score),
                       AVG(d.efficiency_score),
                       :closed
                FROM worker_daily_performance d
                WHERE d.progress_date >= :monthStart
                  AND d.progress_date < :nextMonth
                GROUP BY d.worker_id
                ON DUPLICATE KEY UPDATE
                    worker_user_id = VALUES(worker_user_id),
                    working_days = VALUES(working_days),
                    completed_count = VALUES(completed_count),
                    active_count = VALUES(active_count),
                    total_count = VALUES(total_count),
                    opened_count = VALUES(opened_count),
                    average_progress_percent = VALUES(average_progress_percent),
                    checked_days = VALUES(checked_days),
                    reached_100_days = VALUES(reached_100_days),
                    order_completed_count = VALUES(order_completed_count),
                    nagul_completed_count = VALUES(nagul_completed_count),
                    publish_completed_count = VALUES(publish_completed_count),
                    bad_completed_count = VALUES(bad_completed_count),
                    recovery_completed_count = VALUES(recovery_completed_count),
                    recovery_created_count = VALUES(recovery_created_count),
                    order_overdue_count = VALUES(order_overdue_count),
                    total_overdue_count = VALUES(total_overdue_count),
                    average_close_seconds = VALUES(average_close_seconds),
                    median_close_seconds = VALUES(median_close_seconds),
                    p90_close_seconds = VALUES(p90_close_seconds),
                    average_speed_score = VALUES(average_speed_score),
                    average_discipline_score = VALUES(average_discipline_score),
                    average_workload_score = VALUES(average_workload_score),
                    active_work_seconds = VALUES(active_work_seconds),
                    average_work_window_seconds = VALUES(average_work_window_seconds),
                    activity_events = VALUES(activity_events),
                    bot_change_count = VALUES(bot_change_count),
                    bot_block_count = VALUES(bot_block_count),
                    load_score = VALUES(load_score),
                    average_efficiency_score = VALUES(average_efficiency_score),
                    closed_period = VALUES(closed_period)
                """, params);
    }

    private int workerEfficiencyScore(int progressPercent, int speedScore, int disciplineScore, int workloadScore) {
        return clampPercent((int) Math.round(
                clampPercent(progressPercent) * 0.35D
                        + clampPercent(speedScore) * 0.35D
                        + clampPercent(disciplineScore) * 0.20D
                        + clampPercent(workloadScore) * 0.10D
        ));
    }

    private int speedScore(WorkerCompletionStats stats) {
        if (stats == null || stats.completed() <= 0) {
            return 100;
        }
        long targetSeconds = Math.max(30, appSettingService.getInt(
                AppSettingService.WORKER_PROGRESS_SPEED_TARGET_MINUTES,
                240
        )) * 60L;
        int medianScore = durationScore(stats.medianSeconds(), targetSeconds);
        int p90Score = stats.p90Seconds() > 0 ? durationScore(stats.p90Seconds(), targetSeconds * 2L) : medianScore;
        return clampPercent((int) Math.round(medianScore * 0.70D + p90Score * 0.30D));
    }

    private int durationScore(long seconds, long targetSeconds) {
        if (seconds <= 0) {
            return 100;
        }
        if (seconds <= targetSeconds) {
            return 100;
        }
        return clampPercent((int) Math.round(targetSeconds * 100D / seconds));
    }

    private int disciplineScore(long total, long overdue) {
        if (total <= 0) {
            return 100;
        }
        return clampPercent((int) Math.round(100D - Math.min(1D, overdue * 1D / total) * 100D));
    }

    private int workloadScore(long completed) {
        long expectedDailyLoad = Math.max(1, appSettingService.getInt(
                AppSettingService.WORKER_PROGRESS_EXPECTED_DAILY_LOAD,
                15
        ));
        return clampPercent((int) Math.round(completed * 100D / expectedDailyLoad));
    }

    private long effectiveSeconds(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return 0;
        }
        LocalTime nightStart = nightStartTime();
        LocalTime nightEnd = workStartTime();
        long seconds = 0;
        LocalDate currentDate = from.toLocalDate();
        LocalDate endDate = to.toLocalDate();
        while (!currentDate.isAfter(endDate)) {
            LocalDateTime dayStart = currentDate.atStartOfDay();
            LocalDateTime dayEnd = currentDate.plusDays(1).atStartOfDay();
            LocalDateTime segmentStart = max(from, dayStart);
            LocalDateTime segmentEnd = min(to, dayEnd);
            if (segmentEnd.isAfter(segmentStart)) {
                long segmentSeconds = Duration.between(segmentStart, segmentEnd).getSeconds();
                LocalDateTime nightFrom = currentDate.atTime(nightStart);
                LocalDateTime nightTo = nightEnd.isAfter(nightStart)
                        ? currentDate.atTime(nightEnd)
                        : currentDate.plusDays(1).atTime(nightEnd);
                long ignoredSeconds = overlapSeconds(segmentStart, segmentEnd, nightFrom, nightTo);
                seconds += Math.max(0, segmentSeconds - ignoredSeconds);
            }
            currentDate = currentDate.plusDays(1);
        }
        return Math.max(0, seconds);
    }

    private boolean isOverdue(String itemType, LocalDateTime openedAt, LocalDateTime checkedAt) {
        LocalDateTime dueAt = dueAt(itemType, openedAt);
        return dueAt != null && checkedAt != null && checkedAt.isAfter(dueAt);
    }

    private LocalDateTime dueAt(String itemType, LocalDateTime openedAt) {
        if (openedAt == null) {
            return null;
        }
        int lateTaskHour = clampHour(appSettingService.getInt(AppSettingService.WORKER_PROGRESS_LATE_TASK_HOUR, 22));
        int lateDeadlineHour = clampHour(appSettingService.getInt(AppSettingService.WORKER_PROGRESS_LATE_TASK_DEADLINE_HOUR, 12));
        LocalDate dueDate = openedAt.toLocalDate();
        if (TYPE_ORDER.equals(itemType) && openedAt.toLocalTime().getHour() >= lateTaskHour) {
            dueDate = dueDate.plusDays(1);
            return dueDate.atTime(lateDeadlineHour, 0);
        }
        return dueDate.plusDays(1).atStartOfDay().minusNanos(1);
    }

    private LocalDateTime evaluationTime(LocalDate date) {
        LocalDate safeDate = safeDate(date);
        LocalDate today = progressToday();
        if (safeDate.equals(today)) {
            return progressNow();
        }
        return safeDate.plusDays(1).atStartOfDay();
    }

    private LocalTime nightStartTime() {
        return LocalTime.of(clampHour(appSettingService.getInt(
                AppSettingService.WORKER_PROGRESS_NIGHT_WINDOW_START_HOUR,
                0
        )), 0);
    }

    private LocalTime workStartTime() {
        return LocalTime.of(clampHour(appSettingService.getInt(
                AppSettingService.WORKER_PROGRESS_NIGHT_WINDOW_END_HOUR,
                10
        )), 0);
    }

    private int clampHour(int hour) {
        return Math.max(0, Math.min(23, hour));
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private LocalDateTime min(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private LocalDateTime max(LocalDateTime first, LocalDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    private long overlapSeconds(LocalDateTime firstStart, LocalDateTime firstEnd, LocalDateTime secondStart, LocalDateTime secondEnd) {
        LocalDateTime start = max(firstStart, secondStart);
        LocalDateTime end = min(firstEnd, secondEnd);
        if (!end.isAfter(start)) {
            return 0;
        }
        return Duration.between(start, end).getSeconds();
    }

    private long weightedAverage(Collection<DailyWorkProgressResponse> values, java.util.function.ToLongFunction<DailyWorkProgressResponse> getter) {
        long totalWeight = values.stream().mapToLong(DailyWorkProgressResponse::completed).sum();
        if (totalWeight <= 0) {
            return 0;
        }
        long weighted = values.stream()
                .mapToLong(item -> getter.applyAsLong(item) * Math.max(0, item.completed()))
                .sum();
        return weighted / totalWeight;
    }

    private static long secondsBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || to.isBefore(from)) {
            return 0;
        }
        return Math.max(0, Duration.between(from, to).getSeconds());
    }

    private String workerName(User user) {
        if (user == null) {
            return null;
        }
        if (user.getFio() != null && !user.getFio().isBlank()) {
            return user.getFio();
        }
        return user.getUsername();
    }

    private LocalDate safeDate(LocalDate date) {
        return date == null ? progressToday() : date;
    }

    private LocalDate progressToday() {
        return LocalDate.now(PROGRESS_ZONE);
    }

    private LocalDateTime progressNow() {
        return LocalDateTime.now(PROGRESS_ZONE);
    }

    private static int percentInt(long part, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, Math.round(part * 100D / total)));
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return (int) Math.round(number.doubleValue());
        }
        return 0;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.longValue() != 0;
        }
        if (value instanceof byte[] bytes) {
            return bytes.length > 0 && bytes[0] != 0;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        return null;
    }

    private record ActiveWorkItem(
            Long workerId,
            String itemType,
            Long itemId,
            LocalDateTime openedAt,
            LocalDateTime addedAt
    ) {
    }

    private record CompletedItem(
            Long workerId,
            String itemType,
            Long itemId,
            LocalDateTime openedAt,
            LocalDateTime addedAt,
            LocalDateTime doneAt,
            long durationSeconds,
            boolean overdue
    ) {
    }

    public record WorkerProgressSubject(Long workerId, Long workerUserId, String workerName) {
    }

    private record Reached100State(boolean reached100, LocalDateTime firstReached100At, LocalDateTime lastReached100At) {
        static Reached100State empty() {
            return new Reached100State(false, null, null);
        }
    }

    private record WorkerActiveStats(long active, long orderOverdue, long totalOverdue) {
        static WorkerActiveStats empty() {
            return new WorkerActiveStats(0, 0, 0);
        }

        private static final class Mutable {
            private long active;
            private long orderOverdue;
            private long totalOverdue;

            private WorkerActiveStats toStats() {
                return new WorkerActiveStats(active, orderOverdue, totalOverdue);
            }
        }
    }

    private record WorkerAuxStats(long recoveryCreated, long botChange, long botBlock) {
        static WorkerAuxStats empty() {
            return new WorkerAuxStats(0, 0, 0);
        }

        private static final class Mutable {
            private long recoveryCreated;
            private long botChange;
            private long botBlock;

            private WorkerAuxStats toStats() {
                return new WorkerAuxStats(recoveryCreated, botChange, botBlock);
            }
        }
    }

    private record WorkerActivityStats(
            long activityEvents,
            LocalDateTime firstActivityAt,
            LocalDateTime lastActivityAt,
            long activeWorkSeconds,
            long workWindowSeconds
    ) {
        static WorkerActivityStats empty() {
            return new WorkerActivityStats(0, null, null, 0, 0);
        }

        static WorkerActivityStats from(List<LocalDateTime> events, long sessionGapMinutes) {
            if (events == null || events.isEmpty()) {
                return empty();
            }
            List<LocalDateTime> sorted = events.stream()
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
            if (sorted.isEmpty()) {
                return empty();
            }

            long safeGapSeconds = Math.max(1, sessionGapMinutes) * 60L;
            LocalDateTime first = sorted.get(0);
            LocalDateTime last = sorted.get(sorted.size() - 1);
            LocalDateTime sessionStart = first;
            LocalDateTime previous = first;
            int sessionEvents = 1;
            long activeSeconds = 0;

            for (int i = 1; i < sorted.size(); i++) {
                LocalDateTime current = sorted.get(i);
                long gapSeconds = secondsBetween(previous, current);
                if (gapSeconds > safeGapSeconds) {
                    activeSeconds += sessionSeconds(sessionStart, previous, sessionEvents);
                    sessionStart = current;
                    sessionEvents = 1;
                } else {
                    sessionEvents++;
                }
                previous = current;
            }
            activeSeconds += sessionSeconds(sessionStart, previous, sessionEvents);

            return new WorkerActivityStats(
                    sorted.size(),
                    first,
                    last,
                    activeSeconds,
                    secondsBetween(first, last)
            );
        }

        private static long sessionSeconds(LocalDateTime from, LocalDateTime to, int eventCount) {
            if (eventCount <= 1) {
                return SINGLE_ACTION_SESSION_SECONDS;
            }
            return Math.max(SINGLE_ACTION_SESSION_SECONDS, secondsBetween(from, to));
        }
    }

    private record WorkerCompletionStats(
            long completed,
            LocalDateTime firstCompletedAt,
            LocalDateTime lastCompletedAt,
            long averageSeconds,
            long medianSeconds,
            long p90Seconds,
            long orderCompleted,
            long nagulCompleted,
            long publishCompleted,
            long badCompleted,
            long recoveryCompleted,
            long orderOverdue,
            long totalOverdue,
            List<CompletedItem> items
    ) {
        static WorkerCompletionStats empty() {
            return new WorkerCompletionStats(0, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        static WorkerCompletionStats from(List<CompletedItem> items) {
            if (items == null || items.isEmpty()) {
                return empty();
            }
            List<Long> durations = items.stream()
                    .map(CompletedItem::durationSeconds)
                    .filter(value -> value >= 0)
                    .sorted()
                    .toList();
            long average = durations.isEmpty()
                    ? 0
                    : Math.round(durations.stream().mapToLong(Long::longValue).average().orElse(0));
            return new WorkerCompletionStats(
                    items.size(),
                    items.stream().map(CompletedItem::doneAt).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null),
                    items.stream().map(CompletedItem::doneAt).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null),
                    average,
                    percentile(durations, 0.50),
                    percentile(durations, 0.90),
                    countType(items, TYPE_ORDER),
                    countType(items, TYPE_NAGUL),
                    countType(items, TYPE_PUBLISH),
                    countType(items, TYPE_BAD),
                    countType(items, TYPE_RECOVERY),
                    items.stream().filter(item -> TYPE_ORDER.equals(item.itemType()) && item.overdue()).count(),
                    items.stream().filter(CompletedItem::overdue).count(),
                    List.copyOf(items)
            );
        }

        private static long countType(List<CompletedItem> items, String type) {
            return items.stream().filter(item -> type.equals(item.itemType())).count();
        }

        private static long percentile(List<Long> values, double percentile) {
            if (values == null || values.isEmpty()) {
                return 0;
            }
            int index = Math.max(0, Math.min(values.size() - 1, (int) Math.ceil(values.size() * percentile) - 1));
            return values.get(index);
        }
    }
}
