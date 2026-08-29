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

/**
 * Reads the current financial truth used by analytics screens.
 *
 * <p>Nightly aggregates remain useful for non-financial metrics, but salary and
 * received-payment figures must never wait for that batch. They are therefore
 * read from the same active salary/payment sources that drive payroll.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsFinancialSourceService {

    private static final String OWNER_SCOPE_PREFIX = AnalyticsAggregateReadService.SCOPE_TYPE_OWNER + ":";

    private final NamedParameterJdbcTemplate jdbc;

    public List<DailyFinancial> dailyForScope(
            String scopeKey,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        if (scopeKey == null || fromInclusive == null || toInclusive == null || toInclusive.isBefore(fromInclusive)) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fromInclusive", fromInclusive)
                .addValue("toInclusive", toInclusive);
        if (AnalyticsAggregateReadService.SCOPE_ADMIN_ALL.equals(scopeKey)) {
            return jdbc.query(ADMIN_DAILY_SQL, params, this::mapDailyFinancial);
        }
        Long ownerUserId = ownerUserId(scopeKey);
        if (ownerUserId == null) {
            return List.of();
        }
        params.addValue("ownerUserId", ownerUserId);
        return jdbc.query(OWNER_DAILY_SQL, params, this::mapDailyFinancial);
    }

    public Map<Long, PaymentTotal> paymentTotalsForUsers(
            Collection<Long> requestedUserIds,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        List<Long> userIds = normalizedUserIds(requestedUserIds);
        if (userIds.isEmpty() || fromInclusive == null || toInclusive == null || toInclusive.isBefore(fromInclusive)) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userIds", userIds)
                .addValue("fromInclusive", fromInclusive)
                .addValue("toInclusive", toInclusive);
        Map<Long, PaymentTotal> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT payment.check_manager AS user_id,
                       COALESCE(SUM(payment.check_sum), 0) AS payment_sum,
                       COUNT(payment.check_id) AS payment_count
                FROM analytics_payment_source payment
                WHERE payment.check_active = 1
                  AND payment.check_manager IN (:userIds)
                  AND payment.check_date BETWEEN :fromInclusive AND :toInclusive
                GROUP BY payment.check_manager
                ORDER BY payment.check_manager
                """, params, rs -> {
            result.put(
                    rs.getLong("user_id"),
                    new PaymentTotal(defaultZero(rs.getBigDecimal("payment_sum")), rs.getLong("payment_count"))
            );
        });
        return result;
    }

    private DailyFinancial mapDailyFinancial(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DailyFinancial(
                rs.getDate("metric_date").toLocalDate(),
                defaultZero(rs.getBigDecimal("salary_sum")),
                rs.getLong("salary_entry_count"),
                rs.getLong("salary_review_count"),
                defaultZero(rs.getBigDecimal("payment_sum")),
                rs.getLong("payment_count")
        );
    }

    private Long ownerUserId(String scopeKey) {
        String normalized = scopeKey.trim();
        if (!normalized.startsWith(OWNER_SCOPE_PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(normalized.substring(OWNER_SCOPE_PREFIX.length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<Long> normalizedUserIds(Collection<Long> requestedUserIds) {
        if (requestedUserIds == null || requestedUserIds.isEmpty()) {
            return List.of();
        }
        return requestedUserIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record DailyFinancial(
            LocalDate metricDate,
            BigDecimal salarySum,
            long salaryEntryCount,
            long salaryReviewCount,
            BigDecimal paymentSum,
            long paymentCount
    ) {
    }

    public record PaymentTotal(BigDecimal paymentSum, long paymentCount) {
    }

    private static final String ADMIN_DAILY_SQL = """
            WITH metric_rows AS (
                SELECT salary.metric_date,
                       COALESCE(SUM(salary.salary_sum), 0) AS salary_sum,
                       COUNT(DISTINCT salary.source_zp_id) AS salary_entry_count,
                       COALESCE(SUM(salary.salary_review_count), 0) AS salary_review_count,
                       0 AS payment_sum,
                       0 AS payment_count
                FROM analytics_salary_source salary
                WHERE salary.metric_date BETWEEN :fromInclusive AND :toInclusive
                GROUP BY salary.metric_date

                UNION ALL

                SELECT payment.check_date AS metric_date,
                       0 AS salary_sum,
                       0 AS salary_entry_count,
                       0 AS salary_review_count,
                       COALESCE(SUM(payment.check_sum), 0) AS payment_sum,
                       COUNT(payment.check_id) AS payment_count
                FROM analytics_payment_source payment
                WHERE payment.check_active = 1
                  AND payment.check_date BETWEEN :fromInclusive AND :toInclusive
                GROUP BY payment.check_date
            )
            SELECT metric_date,
                   COALESCE(SUM(salary_sum), 0) AS salary_sum,
                   COALESCE(SUM(salary_entry_count), 0) AS salary_entry_count,
                   COALESCE(SUM(salary_review_count), 0) AS salary_review_count,
                   COALESCE(SUM(payment_sum), 0) AS payment_sum,
                   COALESCE(SUM(payment_count), 0) AS payment_count
            FROM metric_rows
            GROUP BY metric_date
            ORDER BY metric_date
            """;

    private static final String OWNER_DAILY_SQL = """
            WITH owner_managers AS (
                SELECT DISTINCT manager_profile.manager_id,
                                manager_profile.user_id AS manager_user_id
                FROM managers_users owner_manager
                JOIN managers manager_profile ON manager_profile.manager_id = owner_manager.manager_id
                WHERE owner_manager.user_id = :ownerUserId
                  AND manager_profile.user_id IS NOT NULL
            ),
            owner_visibility AS (
                SELECT manager_user_id AS visible_user_id FROM owner_managers
                UNION
                SELECT worker_profile.user_id
                FROM owner_managers
                JOIN workers_users manager_worker ON manager_worker.user_id = owner_managers.manager_user_id
                JOIN workers worker_profile ON worker_profile.worker_id = manager_worker.worker_id
                WHERE worker_profile.user_id IS NOT NULL
                UNION
                SELECT operator_profile.user_id
                FROM owner_managers
                JOIN operators_users manager_operator ON manager_operator.user_id = owner_managers.manager_user_id
                JOIN operators operator_profile ON operator_profile.operator_id = manager_operator.operator_id
                WHERE operator_profile.user_id IS NOT NULL
                UNION
                SELECT marketolog_profile.user_id
                FROM owner_managers
                JOIN marketologs_users manager_marketolog ON manager_marketolog.user_id = owner_managers.manager_user_id
                JOIN marketologs marketolog_profile ON marketolog_profile.marketolog_id = manager_marketolog.marketolog_id
                WHERE marketolog_profile.user_id IS NOT NULL
            ),
            metric_rows AS (
                SELECT salary.metric_date,
                       COALESCE(SUM(salary.salary_sum), 0) AS salary_sum,
                       COUNT(DISTINCT salary.source_zp_id) AS salary_entry_count,
                       COALESCE(SUM(salary.salary_review_count), 0) AS salary_review_count,
                       0 AS payment_sum,
                       0 AS payment_count
                FROM owner_visibility
                JOIN analytics_salary_source salary ON salary.user_id = owner_visibility.visible_user_id
                WHERE salary.metric_date BETWEEN :fromInclusive AND :toInclusive
                GROUP BY salary.metric_date

                UNION ALL

                SELECT payment.check_date AS metric_date,
                       0 AS salary_sum,
                       0 AS salary_entry_count,
                       0 AS salary_review_count,
                       COALESCE(SUM(payment.check_sum), 0) AS payment_sum,
                       COUNT(payment.check_id) AS payment_count
                FROM owner_managers
                JOIN analytics_payment_source payment
                  ON payment.check_manager = owner_managers.manager_user_id
                WHERE payment.check_active = 1
                  AND payment.check_date BETWEEN :fromInclusive AND :toInclusive
                GROUP BY payment.check_date
            )
            SELECT metric_date,
                   COALESCE(SUM(salary_sum), 0) AS salary_sum,
                   COALESCE(SUM(salary_entry_count), 0) AS salary_entry_count,
                   COALESCE(SUM(salary_review_count), 0) AS salary_review_count,
                   COALESCE(SUM(payment_sum), 0) AS payment_sum,
                   COALESCE(SUM(payment_count), 0) AS payment_count
            FROM metric_rows
            GROUP BY metric_date
            ORDER BY metric_date
            """;
}
