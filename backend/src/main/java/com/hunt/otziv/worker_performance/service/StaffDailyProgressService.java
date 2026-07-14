package com.hunt.otziv.worker_performance.service;

import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private static final long MANAGER_DAILY_GOAL = 3L;
    private static final String ROLE_MANAGER = "MANAGER";
    private static final String ROLE_WORKER = "WORKER";

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
                completed,
                percent
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

    @Transactional
    public Map<Long, DailyWorkProgressResponse> workerProgressBySubjects(Collection<WorkerProgressSubject> workers, LocalDate date) {
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
        Map<Long, Long> active = activeCounts(workerIds, safeDate);
        Map<Long, WorkerCompletionStats> completed = completionStats(workerIds, safeDate);

        Map<Long, DailyWorkProgressResponse> result = new LinkedHashMap<>();
        for (WorkerProgressSubject worker : visibleWorkers) {
            long activeCount = active.getOrDefault(worker.workerId(), 0L);
            WorkerCompletionStats stats = completed.getOrDefault(worker.workerId(), WorkerCompletionStats.empty());
            DailyWorkProgressResponse response = responseForWorker(safeDate, activeCount, stats);
            result.put(worker.workerId(), response);
            saveDaily(worker, response);
        }
        rebuildMonthly(safeDate.withDayOfMonth(1), false);
        return result;
    }

    @Transactional
    public void rebuildMonthlyAggregates(LocalDate monthStart, boolean closed) {
        LocalDate safeMonthStart = safeDate(monthStart).withDayOfMonth(1);
        rebuildMonthly(safeMonthStart, closed);
    }

    @Transactional
    public DailyWorkProgressResponse aggregateWorkerProgress(Collection<Worker> workers, LocalDate date) {
        Map<Long, DailyWorkProgressResponse> progress = workerProgressByWorkers(workers, date);
        if (progress.isEmpty()) {
            return DailyWorkProgressResponse.hidden(ROLE_WORKER, safeDate(date));
        }

        LocalDate safeDate = safeDate(date);
        long completed = progress.values().stream().mapToLong(DailyWorkProgressResponse::completed).sum();
        long active = progress.values().stream().mapToLong(DailyWorkProgressResponse::active).sum();
        long total = completed + active;
        long loadScore = progress.values().stream().mapToLong(DailyWorkProgressResponse::loadScore).sum();
        long averageClose = weightedAverage(progress.values(), DailyWorkProgressResponse::averageCloseSeconds);
        long medianClose = weightedAverage(progress.values(), DailyWorkProgressResponse::medianCloseSeconds);
        long p90Close = weightedAverage(progress.values(), DailyWorkProgressResponse::p90CloseSeconds);
        int efficiency = progress.isEmpty()
                ? 0
                : (int) Math.round(progress.values().stream().mapToInt(DailyWorkProgressResponse::efficiencyScore).average().orElse(0));

        return new DailyWorkProgressResponse(
                true,
                ROLE_WORKER,
                safeDate,
                completed,
                active,
                total,
                percentInt(completed, total),
                total == 0 || active == 0,
                progress.values().stream()
                        .map(DailyWorkProgressResponse::firstCompletedAt)
                        .filter(Objects::nonNull)
                        .min(LocalDateTime::compareTo)
                        .orElse(null),
                progress.values().stream()
                        .map(DailyWorkProgressResponse::lastCompletedAt)
                        .filter(Objects::nonNull)
                        .max(LocalDateTime::compareTo)
                        .orElse(null),
                averageClose,
                medianClose,
                p90Close,
                loadScore,
                efficiency
        );
    }

    private DailyWorkProgressResponse responseForWorker(LocalDate date, long active, WorkerCompletionStats stats) {
        long completed = stats.completed();
        long total = completed + Math.max(0, active);
        int percent = total == 0 ? 100 : percentInt(completed, total);
        boolean checked = total == 0 || active == 0;
        int efficiencyScore = workerEfficiencyScore(percent, completed, active, stats.medianSeconds());

        return new DailyWorkProgressResponse(
                true,
                ROLE_WORKER,
                date,
                completed,
                Math.max(0, active),
                total,
                percent,
                checked,
                stats.firstCompletedAt(),
                stats.lastCompletedAt(),
                stats.averageSeconds(),
                stats.medianSeconds(),
                stats.p90Seconds(),
                completed,
                efficiencyScore
        );
    }

    private Map<Long, Long> activeCounts(List<Long> workerIds, LocalDate date) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workerIds", workerIds)
                .addValue("today", date)
                .addValue("nagulDate", date.plusDays(appSettingService.getInt(AppSettingService.NAGUL_LOOKAHEAD_DAYS, 60)));

        Map<Long, Long> result = new HashMap<>();
        jdbc.queryForList("""
                SELECT worker_id, SUM(active_count) AS active_count
                FROM (
                    SELECT o.order_worker AS worker_id, COUNT(*) AS active_count
                    FROM orders o
                    JOIN order_statuses s ON s.order_status_id = o.order_status
                    WHERE o.order_worker IN (:workerIds)
                      AND COALESCE(o.order_complete, 0) = 0
                      AND s.order_status_title IN ('Новый', 'Коррекция')
                      AND COALESCE(o.order_waiting_for_client, 0) = 0
                    GROUP BY o.order_worker

                    UNION ALL

                    SELECT r.review_worker AS worker_id, COUNT(*) AS active_count
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
                    GROUP BY r.review_worker

                    UNION ALL

                    SELECT r.review_worker AS worker_id, COUNT(*) AS active_count
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
                    GROUP BY r.review_worker

                    UNION ALL

                    SELECT t.bad_review_task_worker AS worker_id, COUNT(*) AS active_count
                    FROM bad_review_tasks t
                    WHERE t.bad_review_task_worker IN (:workerIds)
                      AND t.bad_review_task_status = 'NEW'
                      AND t.bad_review_task_scheduled_date <= :today
                    GROUP BY t.bad_review_task_worker

                    UNION ALL

                    SELECT t.review_recovery_task_worker AS worker_id, COUNT(*) AS active_count
                    FROM review_recovery_tasks t
                    JOIN review_recovery_batches b ON b.review_recovery_batch_id = t.review_recovery_task_batch
                    WHERE t.review_recovery_task_worker IN (:workerIds)
                      AND t.review_recovery_task_status = 'PLANNED'
                      AND b.review_recovery_batch_status = 'OPEN'
                      AND t.review_recovery_task_scheduled_date <= :today
                    GROUP BY t.review_recovery_task_worker
                ) active_items
                WHERE worker_id IS NOT NULL
                GROUP BY worker_id
                """, params).forEach(row -> result.put(
                longValue(row.get("worker_id")),
                longValue(row.get("active_count"))
        ));
        return result;
    }

    private Map<Long, WorkerCompletionStats> completionStats(List<Long> workerIds, LocalDate date) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workerIds", workerIds)
                .addValue("from", date.atStartOfDay())
                .addValue("to", date.plusDays(1).atStartOfDay())
                .addValue("date", date);

        Map<Long, List<CompletedItem>> rowsByWorker = new HashMap<>();
        jdbc.queryForList("""
                SELECT worker_id, done_at, duration_seconds
                FROM (
                    SELECT o.order_worker AS worker_id,
                           MAX(e.created_at) AS done_at,
                           0 AS duration_seconds
                    FROM business_audit_events e
                    JOIN orders o ON o.order_id = e.order_id
                    WHERE o.order_worker IN (:workerIds)
                      AND e.action = 'order_status_changed'
                      AND e.created_at >= :from
                      AND e.created_at < :to
                      AND e.old_value IN ('Новый', 'Коррекция')
                      AND COALESCE(e.new_value, '') NOT IN ('Новый', 'Коррекция')
                    GROUP BY o.order_worker, e.order_id

                    UNION ALL

                    SELECT r.review_worker AS worker_id,
                           COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed)) AS done_at,
                           GREATEST(0, TIMESTAMPDIFF(SECOND, TIMESTAMP(r.review_publish_date), COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed)))) AS duration_seconds
                    FROM reviews r
                    WHERE r.review_worker IN (:workerIds)
                      AND r.review_publish = 1
                      AND COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed)) >= :from
                      AND COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed)) < :to

                    UNION ALL

                    SELECT t.bad_review_task_worker AS worker_id,
                           TIMESTAMP(t.bad_review_task_completed_date) AS done_at,
                           GREATEST(0, TIMESTAMPDIFF(SECOND, TIMESTAMP(t.bad_review_task_scheduled_date), TIMESTAMP(t.bad_review_task_completed_date))) AS duration_seconds
                    FROM bad_review_tasks t
                    WHERE t.bad_review_task_worker IN (:workerIds)
                      AND t.bad_review_task_status = 'DONE'
                      AND t.bad_review_task_completed_date = :date

                    UNION ALL

                    SELECT t.review_recovery_task_worker AS worker_id,
                           TIMESTAMP(t.review_recovery_task_completed_date) AS done_at,
                           GREATEST(0, TIMESTAMPDIFF(SECOND, TIMESTAMP(t.review_recovery_task_scheduled_date), TIMESTAMP(t.review_recovery_task_completed_date))) AS duration_seconds
                    FROM review_recovery_tasks t
                    WHERE t.review_recovery_task_worker IN (:workerIds)
                      AND t.review_recovery_task_status = 'DONE'
                      AND t.review_recovery_task_completed_date = :date
                ) completed_items
                WHERE worker_id IS NOT NULL
                """, params).forEach(row -> rowsByWorker
                .computeIfAbsent(longValue(row.get("worker_id")), ignored -> new ArrayList<>())
                .add(new CompletedItem(
                        toLocalDateTime(row.get("done_at")),
                        longValue(row.get("duration_seconds"))
                )));

        Map<Long, WorkerCompletionStats> result = new HashMap<>();
        for (Map.Entry<Long, List<CompletedItem>> entry : rowsByWorker.entrySet()) {
            result.put(entry.getKey(), WorkerCompletionStats.from(entry.getValue()));
        }
        return result;
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
                .addValue("percent", BigDecimal.valueOf(response.percent()).setScale(2, RoundingMode.HALF_UP))
                .addValue("checked", response.checked())
                .addValue("firstCompletedAt", response.firstCompletedAt())
                .addValue("lastCompletedAt", response.lastCompletedAt())
                .addValue("averageCloseSeconds", response.averageCloseSeconds())
                .addValue("medianCloseSeconds", response.medianCloseSeconds())
                .addValue("p90CloseSeconds", response.p90CloseSeconds())
                .addValue("loadScore", response.loadScore())
                .addValue("efficiencyScore", response.efficiencyScore());
        jdbc.update("""
                INSERT INTO worker_daily_performance (
                    progress_date, worker_id, worker_user_id, worker_name,
                    active_count, completed_count, total_count, progress_percent, checked,
                    first_completed_at, last_completed_at, average_close_seconds, median_close_seconds,
                    p90_close_seconds, load_score, efficiency_score, aggregation_status
                )
                VALUES (
                    :date, :workerId, :workerUserId, :workerName,
                    :active, :completed, :total, :percent, :checked,
                    :firstCompletedAt, :lastCompletedAt, :averageCloseSeconds, :medianCloseSeconds,
                    :p90CloseSeconds, :loadScore, :efficiencyScore, 'CALCULATED'
                )
                ON DUPLICATE KEY UPDATE
                    worker_user_id = VALUES(worker_user_id),
                    worker_name = VALUES(worker_name),
                    active_count = VALUES(active_count),
                    completed_count = VALUES(completed_count),
                    total_count = VALUES(total_count),
                    progress_percent = VALUES(progress_percent),
                    checked = VALUES(checked),
                    first_completed_at = VALUES(first_completed_at),
                    last_completed_at = VALUES(last_completed_at),
                    average_close_seconds = VALUES(average_close_seconds),
                    median_close_seconds = VALUES(median_close_seconds),
                    p90_close_seconds = VALUES(p90_close_seconds),
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
                    total_count, average_progress_percent, checked_days, average_close_seconds,
                    median_close_seconds, p90_close_seconds, load_score, average_efficiency_score, closed_period
                )
                SELECT :monthStart,
                       d.worker_id,
                       MAX(d.worker_user_id),
                       COUNT(*),
                       SUM(d.completed_count),
                       SUM(d.active_count),
                       SUM(d.total_count),
                       AVG(d.progress_percent),
                       SUM(CASE WHEN d.checked = 1 THEN 1 ELSE 0 END),
                       AVG(d.average_close_seconds),
                       AVG(d.median_close_seconds),
                       AVG(d.p90_close_seconds),
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
                    average_progress_percent = VALUES(average_progress_percent),
                    checked_days = VALUES(checked_days),
                    average_close_seconds = VALUES(average_close_seconds),
                    median_close_seconds = VALUES(median_close_seconds),
                    p90_close_seconds = VALUES(p90_close_seconds),
                    load_score = VALUES(load_score),
                    average_efficiency_score = VALUES(average_efficiency_score),
                    closed_period = VALUES(closed_period)
                """, params);
    }

    private int workerEfficiencyScore(int progressPercent, long completed, long active, long medianSeconds) {
        int progressScore = Math.max(0, Math.min(100, progressPercent));
        int loadBonus = (int) Math.min(20, completed / 5);
        int cleanBonus = active == 0 ? 10 : 0;
        int speedPenalty = medianSeconds > 0 && medianSeconds > 3L * 24L * 60L * 60L ? 10 : 0;
        return Math.max(0, Math.min(100, progressScore + loadBonus + cleanBonus - speedPenalty));
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
        return date == null ? LocalDate.now() : date;
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

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        return null;
    }

    private record CompletedItem(LocalDateTime doneAt, long durationSeconds) {
    }

    public record WorkerProgressSubject(Long workerId, Long workerUserId, String workerName) {
    }

    private record WorkerCompletionStats(
            long completed,
            LocalDateTime firstCompletedAt,
            LocalDateTime lastCompletedAt,
            long averageSeconds,
            long medianSeconds,
            long p90Seconds
    ) {
        static WorkerCompletionStats empty() {
            return new WorkerCompletionStats(0, null, null, 0, 0, 0);
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
                    percentile(durations, 0.90)
            );
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
