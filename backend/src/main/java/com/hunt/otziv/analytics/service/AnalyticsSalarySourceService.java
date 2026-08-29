package com.hunt.otziv.analytics.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads final salary attribution in one batch for analytics screens. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsSalarySourceService {

    private final NamedParameterJdbcTemplate jdbc;

    public List<DailySalary> dailyForUsers(
            Collection<Long> requestedUserIds,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        List<Long> userIds = normalizedUserIds(requestedUserIds);
        if (userIds.isEmpty() || fromInclusive == null || toInclusive == null || toInclusive.isBefore(fromInclusive)) {
            return List.of();
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userIds", userIds)
                .addValue("fromInclusive", fromInclusive)
                .addValue("toInclusive", toInclusive);

        return jdbc.query("""
                SELECT
                    salary.metric_date,
                    salary.user_id,
                    COALESCE(SUM(salary.salary_sum), 0) AS salary_sum,
                    COUNT(DISTINCT salary.source_zp_id) AS salary_entry_count,
                    COALESCE(SUM(salary.salary_review_count), 0) AS salary_review_count
                FROM analytics_salary_source salary
                WHERE salary.user_id IN (:userIds)
                  AND salary.metric_date BETWEEN :fromInclusive AND :toInclusive
                GROUP BY salary.metric_date, salary.user_id
                ORDER BY salary.metric_date, salary.user_id
                """, params, (rs, rowNum) -> new DailySalary(
                rs.getDate("metric_date").toLocalDate(),
                rs.getLong("user_id"),
                defaultZero(rs.getBigDecimal("salary_sum")),
                rs.getLong("salary_entry_count"),
                rs.getLong("salary_review_count")
        ));
    }

    public Map<Long, SalaryTotal> totalsForUsers(
            Collection<Long> requestedUserIds,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        Map<Long, SalaryTotal> totals = new LinkedHashMap<>();
        for (DailySalary daily : dailyForUsers(requestedUserIds, fromInclusive, toInclusive)) {
            totals.merge(
                    daily.userId(),
                    new SalaryTotal(daily.salarySum(), daily.salaryEntryCount(), daily.salaryReviewCount()),
                    SalaryTotal::add
            );
        }
        return totals;
    }

    private List<Long> normalizedUserIds(Collection<Long> requestedUserIds) {
        if (requestedUserIds == null || requestedUserIds.isEmpty()) {
            return List.of();
        }
        return requestedUserIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record DailySalary(
            LocalDate metricDate,
            Long userId,
            BigDecimal salarySum,
            long salaryEntryCount,
            long salaryReviewCount
    ) {
    }

    public record SalaryTotal(BigDecimal salarySum, long salaryEntryCount, long salaryReviewCount) {
        private SalaryTotal add(SalaryTotal other) {
            return new SalaryTotal(
                    salarySum.add(other.salarySum),
                    Math.addExact(salaryEntryCount, other.salaryEntryCount),
                    Math.addExact(salaryReviewCount, other.salaryReviewCount)
            );
        }
    }
}
