package com.hunt.otziv.manager_daily_summary.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only view of the finalized workload day plan that produced a specialist's
 * end-of-day result. The audit reuses the plan's own decisions instead of new rules:
 * <ul>
 *     <li>work that arrived after the intake cut-off or did not fit the remaining day is
 *     a LATE decision and never part of the goal;</li>
 *     <li>work blocked by an unavailable account is counted by the final snapshot, but the
 *     specialist could not do it, so it is reported separately;</li>
 *     <li>a MANDATORY unit that is still active in the next day's plan was not finished
 *     by the end of the goal day. Units of one order and section form one task.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ManagerWorkerDayPlanReader {

    private static final Pattern NUMERIC_ORDER = Pattern.compile("^(\\d{1,18}):");

    private final NamedParameterJdbcTemplate jdbc;

    public Map<Long, DayPlan> plans(Collection<Long> workerIds, LocalDate date) {
        if (workerIds == null || date == null) return Map.of();
        List<Long> ids = workerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("date", date)
                .addValue("next", date.plusDays(1));
        try {
            Map<Long, DayPlan> result = new LinkedHashMap<>();
            Map<Long, List<UnfinishedUnit>> units = new HashMap<>();
            jdbc.query("""
                    SELECT unfinished.worker_id, unfinished.section_code, unfinished.cohort_key,
                           unfinished.batch_key, unfinished.initial_units, unfinished.source_available_at
                    FROM workload_shadow_late_batches unfinished
                    WHERE unfinished.progress_date = :date
                      AND unfinished.worker_id IN (:ids)
                      AND unfinished.decision_code = 'MANDATORY'
                      AND EXISTS (
                          SELECT 1 FROM workload_shadow_late_batches carried
                          WHERE carried.progress_date = :next
                            AND carried.worker_id = unfinished.worker_id
                            AND carried.batch_key = unfinished.batch_key
                      )
                    """, params, row -> {
                units.computeIfAbsent(row.getLong("worker_id"), ignored -> new ArrayList<>()).add(new UnfinishedUnit(
                        row.getString("section_code"),
                        row.getString("cohort_key"),
                        row.getString("batch_key"),
                        Math.max(1, row.getLong("initial_units")),
                        localDateTime(row.getTimestamp("source_available_at"))));
            });
            jdbc.query("""
                    SELECT worker_id, eligible_units, completed_units, external_blocked_units,
                           late_excluded_units, last_reached_100_at
                    FROM workload_shadow_worker_daily
                    WHERE progress_date = :date
                      AND worker_id IN (:ids)
                      AND finalized = TRUE
                      AND finalization_status = 'ON_TIME'
                    """, params, row -> {
                long workerId = row.getLong("worker_id");
                result.put(workerId, new DayPlan(
                        Math.max(0, row.getLong("eligible_units")),
                        Math.max(0, row.getLong("completed_units")),
                        Math.max(0, row.getLong("external_blocked_units")),
                        Math.max(0, row.getLong("late_excluded_units")),
                        localDateTime(row.getTimestamp("last_reached_100_at")),
                        tasks(units.getOrDefault(workerId, List.of())),
                        true));
            });
            units.forEach((workerId, rows) -> result.putIfAbsent(workerId,
                    new DayPlan(0, 0, 0, 0, null, tasks(rows), false)));
            return Map.copyOf(result);
        } catch (DataAccessException unavailable) {
            // The audit then keeps the stored end-of-day counters; it never invents a plan.
            log.warn("Workload day plan is unavailable for {} workers on {}: {}", ids.size(), date,
                    unavailable.getMessage());
            return Map.of();
        }
    }

    static List<UnfinishedTask> tasks(List<UnfinishedUnit> units) {
        Map<String, UnfinishedTask> grouped = new LinkedHashMap<>();
        units.stream()
                .sorted(Comparator.comparing(UnfinishedUnit::availableAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(UnfinishedUnit::batchKey, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(unit -> {
                    String section = unit.section() == null ? "" : unit.section().trim().toUpperCase(java.util.Locale.ROOT);
                    Long orderId = orderId(section, unit.cohortKey());
                    String key = section + ":" + (orderId == null ? "unit:" + unit.batchKey() : orderId);
                    grouped.merge(key, new UnfinishedTask(section, orderId, unit.units(), unit.availableAt()),
                            (left, right) -> new UnfinishedTask(left.section(), left.orderId(),
                                    left.units() + right.units(), earlier(left.availableAt(), right.availableAt())));
                });
        return List.copyOf(grouped.values());
    }

    /** Cohort keys are SECTION:orderId:availableAt; units without an order use their own batch key. */
    static Long orderId(String section, String cohortKey) {
        if (cohortKey == null || section == null || !cohortKey.startsWith(section + ":")) return null;
        Matcher matcher = NUMERIC_ORDER.matcher(cohortKey.substring(section.length() + 1));
        if (!matcher.find()) return null;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static LocalDateTime earlier(LocalDateTime left, LocalDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private static LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    record UnfinishedUnit(String section, String cohortKey, String batchKey, long units, LocalDateTime availableAt) {
    }

    /**
     * @param finalized false when only the unit list is known; the counters are then absent.
     */
    public record DayPlan(
            long eligibleUnits,
            long completedUnits,
            long externalBlockedUnits,
            long lateExcludedUnits,
            LocalDateTime lastReached100At,
            List<UnfinishedTask> unfinished,
            boolean finalized
    ) {
        public DayPlan {
            unfinished = unfinished == null ? List.of() : List.copyOf(unfinished);
        }
    }

    /** One unfinished task of the goal day: all units of one order in one work section. */
    public record UnfinishedTask(String section, Long orderId, long units, LocalDateTime availableAt) {
    }
}
