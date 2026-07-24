package com.hunt.otziv.manager_performance.service;

import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerTeamProgressService {

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public void saveEndOfDaySnapshot(
            LocalDate date,
            Long managerId,
            Long managerUserId,
            int expectedWorkerCount,
            Collection<DailyWorkProgressResponse> workerProgress
    ) {
        if (date == null || managerId == null) {
            return;
        }
        List<DailyWorkProgressResponse> progress = workerProgress == null
                ? List.of()
                : workerProgress.stream()
                        .filter(ManagerTeamProgressService::isEligible)
                        .toList();
        if (expectedWorkerCount <= 0 || progress.isEmpty()) {
            jdbc.update("""
                    DELETE FROM manager_team_daily_progress
                    WHERE progress_date = :date
                      AND manager_id = :managerId
                    """, new MapSqlParameterSource()
                    .addValue("date", date)
                    .addValue("managerId", managerId));
            return;
        }
        int safeWorkerCount = progress.size();
        int workersAt100 = (int) progress.stream()
                .filter(ManagerTeamProgressService::isAt100)
                .count();
        long completed = progress.stream().mapToLong(DailyWorkProgressResponse::completed).sum();
        long total = progress.stream().mapToLong(DailyWorkProgressResponse::total).sum();
        double progressPercent = progress.stream()
                .mapToInt(DailyWorkProgressResponse::percent)
                .sum() / (double) safeWorkerCount;
        boolean reached100 = workersAt100 >= safeWorkerCount;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("date", date)
                .addValue("managerId", managerId)
                .addValue("managerUserId", managerUserId)
                .addValue("workerCount", safeWorkerCount)
                .addValue("workersAt100", workersAt100)
                .addValue("completed", completed)
                .addValue("total", total)
                .addValue("progressPercent", BigDecimal.valueOf(progressPercent).setScale(2, RoundingMode.HALF_UP))
                .addValue("reached100", reached100);
        jdbc.update("""
                INSERT INTO manager_team_daily_progress (
                    progress_date, manager_id, manager_user_id,
                    worker_count, workers_at_100, completed_count, total_count,
                    progress_percent, reached_100, captured_at
                ) VALUES (
                    :date, :managerId, :managerUserId,
                    :workerCount, :workersAt100, :completed, :total,
                    :progressPercent, :reached100, CURRENT_TIMESTAMP(6)
                )
                ON DUPLICATE KEY UPDATE
                    manager_user_id = VALUES(manager_user_id),
                    worker_count = VALUES(worker_count),
                    workers_at_100 = VALUES(workers_at_100),
                    completed_count = VALUES(completed_count),
                    total_count = VALUES(total_count),
                    progress_percent = VALUES(progress_percent),
                    reached_100 = VALUES(reached_100),
                    captured_at = VALUES(captured_at)
                """, params);
    }

    @Transactional(readOnly = true)
    public Map<Long, TeamProgressStats> statisticsByManagerIds(
            Collection<Long> managerIds,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        if (managerIds == null || managerIds.isEmpty() || fromInclusive == null || toInclusive == null
                || toInclusive.isBefore(fromInclusive)) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("managerIds", managerIds)
                .addValue("from", fromInclusive)
                .addValue("to", toInclusive);
        Map<Long, TeamProgressStats> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT manager_id,
                       COUNT(*) AS eligible_days,
                       SUM(CASE WHEN reached_100 = 1 THEN 1 ELSE 0 END) AS reached_100_days,
                       AVG(progress_percent) AS average_progress_percent,
                       SUM(GREATEST(worker_count - workers_at_100, 0)) AS missed_worker_days
                FROM manager_team_daily_progress
                WHERE manager_id IN (:managerIds)
                  AND progress_date >= :from
                  AND progress_date <= :to
                GROUP BY manager_id
                """, params).forEach(row -> {
            Long managerId = longValue(row.get("manager_id"));
            long eligibleDays = longValue(row.get("eligible_days"));
            long reached100Days = longValue(row.get("reached_100_days"));
            double averageProgress = doubleValue(row.get("average_progress_percent"));
            long missedWorkerDays = longValue(row.get("missed_worker_days"));
            double reached100Rate = eligibleDays <= 0 ? 0 : reached100Days * 100.0 / eligibleDays;
            int score = eligibleDays <= 0 ? 0 : clampScore((int) Math.round(
                    reached100Rate * 0.70 + averageProgress * 0.30
            ));
            result.put(managerId, new TeamProgressStats(
                    eligibleDays,
                    reached100Days,
                    Math.max(0, eligibleDays - reached100Days),
                    round1(reached100Rate),
                    round1(averageProgress),
                    missedWorkerDays,
                    score
            ));
        });
        return Map.copyOf(result);
    }

    private static boolean isAt100(DailyWorkProgressResponse progress) {
        return isEligible(progress) && progress.percent() >= 100 && progress.active() <= 0;
    }

    private static boolean isEligible(DailyWorkProgressResponse progress) {
        return progress != null && progress.visible() && progress.total() > 0;
    }

    private static int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? 0 : Double.parseDouble(value.toString());
    }

    public record TeamProgressStats(
            long eligibleDays,
            long reached100Days,
            long incompleteDays,
            double reached100Rate,
            double averageProgressPercent,
            long missedWorkerDays,
            int score
    ) {
        public static TeamProgressStats empty() {
            return new TeamProgressStats(0, 0, 0, 0, 0, 0, 0);
        }

        public boolean hasData() {
            return eligibleDays > 0;
        }
    }
}
