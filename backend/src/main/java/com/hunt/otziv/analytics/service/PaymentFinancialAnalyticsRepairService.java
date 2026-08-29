package com.hunt.otziv.analytics.service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Rebuilds every analytics month touched by the V268 payment-check repair and
 * every month whose stored financial totals differ from the archive-aware
 * canonical sources. The durable pending flag is cleared only after each
 * rebuilt month matches, so a restart safely resumes failures.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentFinancialAnalyticsRepairService {

    static final String PENDING_SETTING = "financial-integrity.v268-analytics-rebuild-pending";

    private final JdbcTemplate jdbcTemplate;
    private final AnalyticsAggregateRebuildService rebuildService;
    private final AnalyticsAggregateVerificationService verificationService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(
            initialDelayString = "${otziv.analytics.payment-repair.initial-delay-ms:5000}",
            fixedDelayString = "${otziv.analytics.payment-repair.retry-delay-ms:21600000}"
    )
    public void repairPending() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!pending()) {
                return;
            }
            List<LocalDate> months = affectedMonths();
            for (LocalDate month : months) {
                boolean closed = month.isBefore(LocalDate.now().withDayOfMonth(1));
                rebuildService.rebuildMonth(month, closed);
                var comparison = verificationService.compareAdminMonth(month);
                if (!comparison.matches()) {
                    throw new IllegalStateException(
                            "Analytics still differs from canonical sources for month " + month
                    );
                }
            }
            jdbcTemplate.update("""
                    UPDATE app_settings
                    SET setting_value = 'false', updated_at = CURRENT_TIMESTAMP(6)
                    WHERE setting_key = ?
                    """, PENDING_SETTING);
            log.warn("Payment-check analytics repair completed for {} month(s): {}", months.size(), months);
        } catch (RuntimeException exception) {
            log.error("Payment-check analytics repair failed; durable pending flag retained", exception);
        } finally {
            running.set(false);
        }
    }

    private boolean pending() {
        List<String> values = jdbcTemplate.query(
                "SELECT setting_value FROM app_settings WHERE setting_key = ?",
                (rs, rowNum) -> rs.getString(1),
                PENDING_SETTING
        );
        return values != null
                && values.stream().anyMatch(value -> "true".equalsIgnoreCase(value == null ? "" : value.trim()));
    }

    private List<LocalDate> affectedMonths() {
        return jdbcTemplate.query("""
                WITH payment_history AS (
                    SELECT payment.check_id, payment.check_order, payment.check_date
                    FROM payment_check payment

                    UNION ALL

                    SELECT archived.check_id, archived.check_order, archived.check_date
                    FROM archive_payment_check archived
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM payment_check live_payment
                        WHERE live_payment.check_id = archived.check_id
                    )
                ),
                audit_months AS (
                    SELECT DISTINCT DATE_FORMAT(payment.check_date, '%Y-%m-01') AS month_start
                    FROM business_audit_events audit_event
                    JOIN payment_history payment
                      ON (audit_event.action = 'PAYMENT_CHECK_QUARANTINED'
                          AND payment.check_id = CAST(audit_event.entity_id AS UNSIGNED))
                      OR (audit_event.action = 'MISSING_PAYMENT_CHECK_RESTORED'
                          AND payment.check_order = audit_event.order_id)
                    WHERE audit_event.actor = 'system:flyway-v268'
                      AND audit_event.action IN (
                          'PAYMENT_CHECK_QUARANTINED',
                          'MISSING_PAYMENT_CHECK_RESTORED'
                      )
                ),
                source_payments AS (
                    SELECT DATE_FORMAT(payment.check_date, '%Y-%m-01') AS month_start,
                           COALESCE(SUM(payment.check_sum), 0) AS payment_sum,
                           COUNT(payment.check_id) AS payment_count
                    FROM analytics_payment_source payment
                    GROUP BY DATE_FORMAT(payment.check_date, '%Y-%m-01')
                ),
                source_salaries AS (
                    SELECT DATE_FORMAT(salary.metric_date, '%Y-%m-01') AS month_start,
                           COALESCE(SUM(salary.salary_sum), 0) AS salary_sum,
                           COUNT(DISTINCT salary.source_zp_id) AS salary_entry_count,
                           COALESCE(SUM(salary.salary_review_count), 0) AS salary_review_count
                    FROM analytics_salary_source salary
                    GROUP BY DATE_FORMAT(salary.metric_date, '%Y-%m-01')
                ),
                financial_months AS (
                    SELECT month_start FROM source_payments
                    UNION
                    SELECT month_start FROM source_salaries
                    UNION
                    SELECT DATE_FORMAT(monthly.month_start, '%Y-%m-01')
                    FROM analytics_monthly_total monthly
                    WHERE monthly.scope_key = 'ADMIN:ALL'
                      AND monthly.month_start <= DATE_FORMAT(CURRENT_DATE(), '%Y-%m-01')
                ),
                mismatch_months AS (
                    SELECT financial_months.month_start
                    FROM financial_months
                    LEFT JOIN source_payments
                      ON source_payments.month_start = financial_months.month_start
                    LEFT JOIN source_salaries
                      ON source_salaries.month_start = financial_months.month_start
                    LEFT JOIN analytics_monthly_total monthly
                      ON monthly.scope_key = 'ADMIN:ALL'
                     AND monthly.month_start = financial_months.month_start
                    WHERE monthly.analytics_monthly_total_id IS NULL
                       OR COALESCE(monthly.payment_sum, 0) <> COALESCE(source_payments.payment_sum, 0)
                       OR monthly.payment_count <> COALESCE(source_payments.payment_count, 0)
                       OR COALESCE(monthly.salary_sum, 0) <> COALESCE(source_salaries.salary_sum, 0)
                       OR monthly.salary_entry_count <> COALESCE(source_salaries.salary_entry_count, 0)
                       OR monthly.salary_review_count <> COALESCE(source_salaries.salary_review_count, 0)
                ),
                repair_months AS (
                    SELECT month_start FROM audit_months
                    UNION
                    SELECT month_start FROM mismatch_months
                )
                SELECT month_start
                FROM repair_months
                ORDER BY month_start DESC
                """, (rs, rowNum) -> Date.valueOf(rs.getString("month_start")).toLocalDate());
    }
}
