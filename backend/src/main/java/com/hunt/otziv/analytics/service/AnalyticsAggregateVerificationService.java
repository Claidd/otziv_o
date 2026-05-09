package com.hunt.otziv.analytics.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsAggregateVerificationService {

    private static final List<String> ADMIN_COMPARE_METRICS = List.of(
            "salary_sum",
            "salary_entry_count",
            "salary_review_count",
            "payment_sum",
            "payment_count",
            "new_companies_count",
            "published_reviews_count",
            "leads_new_count",
            "leads_in_work_count"
    );

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsAdminMonthComparison compareAdminMonth(LocalDate anyDayInMonth) {
        LocalDate monthStart = AnalyticsAggregateReadService.monthStart(anyDayInMonth);
        LocalDate nextMonthStart = monthStart.plusMonths(1);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("monthStart", monthStart)
                .addValue("nextMonthStart", nextMonthStart)
                .addValue("adminScopeKey", AnalyticsAggregateReadService.SCOPE_ADMIN_ALL)
                .addValue("statusInWork", "В работе");

        Map<String, Object> aggregate = findAdminAggregate(params);
        Map<String, Object> source = rawAdminSource(params);
        List<MetricComparison> metrics = ADMIN_COMPARE_METRICS.stream()
                .map(metric -> compareMetric(metric, aggregate, source))
                .toList();

        return new AnalyticsAdminMonthComparison(
                monthStart,
                !aggregate.isEmpty(),
                metrics,
                metrics.stream().allMatch(MetricComparison::matches)
        );
    }

    private Map<String, Object> findAdminAggregate(MapSqlParameterSource params) {
        try {
            return jdbc.queryForMap("""
                    SELECT
                        salary_sum,
                        salary_entry_count,
                        salary_review_count,
                        payment_sum,
                        payment_count,
                        new_companies_count,
                        published_reviews_count,
                        leads_new_count,
                        leads_in_work_count
                    FROM analytics_monthly_total
                    WHERE month_start = :monthStart
                      AND scope_key = :adminScopeKey
                    """, params);
        } catch (EmptyResultDataAccessException ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> rawAdminSource(MapSqlParameterSource params) {
        return jdbc.queryForMap("""
                SELECT
                    (
                        SELECT COALESCE(SUM(z.zp_sum), 0)
                        FROM zp z
                        WHERE z.zp_date >= :monthStart
                          AND z.zp_date < :nextMonthStart
                    ) AS salary_sum,
                    (
                        SELECT COUNT(z.zp_id)
                        FROM zp z
                        WHERE z.zp_date >= :monthStart
                          AND z.zp_date < :nextMonthStart
                    ) AS salary_entry_count,
                    (
                        SELECT COALESCE(SUM(z.zp_amount), 0)
                        FROM zp z
                        WHERE z.zp_date >= :monthStart
                          AND z.zp_date < :nextMonthStart
                    ) AS salary_review_count,
                    (
                        SELECT COALESCE(SUM(pc.check_sum), 0)
                        FROM payment_check pc
                        WHERE pc.check_date >= :monthStart
                          AND pc.check_date < :nextMonthStart
                          AND pc.check_active = 1
                    ) AS payment_sum,
                    (
                        SELECT COUNT(pc.check_id)
                        FROM payment_check pc
                        WHERE pc.check_date >= :monthStart
                          AND pc.check_date < :nextMonthStart
                          AND pc.check_active = 1
                    ) AS payment_count,
                    (
                        SELECT COUNT(c.company_id)
                        FROM companies c
                        WHERE c.create_date >= :monthStart
                          AND c.create_date < :nextMonthStart
                    ) AS new_companies_count,
                    (
                        SELECT COUNT(r.review_id)
                        FROM reviews r
                        WHERE r.review_publish_date >= :monthStart
                          AND r.review_publish_date < :nextMonthStart
                          AND r.review_publish = 1
                    ) AS published_reviews_count,
                    (
                        SELECT COUNT(l.id)
                        FROM leads l
                        WHERE l.create_date >= :monthStart
                          AND l.create_date < :nextMonthStart
                    ) AS leads_new_count,
                    (
                        SELECT COUNT(l.id)
                        FROM leads l
                        WHERE l.create_date >= :monthStart
                          AND l.create_date < :nextMonthStart
                          AND l.lid_status = :statusInWork
                    ) AS leads_in_work_count
                """, params);
    }

    private MetricComparison compareMetric(String metric, Map<String, Object> aggregate, Map<String, Object> source) {
        BigDecimal aggregateValue = toDecimal(aggregate.get(metric));
        BigDecimal sourceValue = toDecimal(source.get(metric));
        return new MetricComparison(metric, aggregateValue, sourceValue, aggregateValue.subtract(sourceValue));
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    public record AnalyticsAdminMonthComparison(
            LocalDate monthStart,
            boolean aggregateExists,
            List<MetricComparison> metrics,
            boolean matches
    ) {
    }

    public record MetricComparison(
            String metric,
            BigDecimal aggregateValue,
            BigDecimal sourceValue,
            BigDecimal delta
    ) {
        boolean matches() {
            return delta.compareTo(BigDecimal.ZERO) == 0;
        }
    }
}
