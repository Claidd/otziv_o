package com.hunt.otziv.analytics.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class AnalyticsAggregateRebuildService {

    private static final String STATUS_LEAD_IN_WORK = "В работе";

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public AnalyticsAggregateRebuildResult rebuildMonth(LocalDate anyDayInMonth, boolean periodClosed) {
        LocalDate monthStart = AnalyticsAggregateReadService.monthStart(anyDayInMonth);
        LocalDate nextMonthStart = monthStart.plusMonths(1);
        LocalDate monthEnd = nextMonthStart.minusDays(1);
        int sourceDaysCount = (int) ChronoUnit.DAYS.between(monthStart, nextMonthStart);

        int deletedDailyRows = deleteDailyRows(monthStart, monthEnd);
        int salaryRows = upsertDailySalary(monthStart, monthEnd);
        int paymentRows = upsertDailyPayments(monthStart, monthEnd);
        int companyRows = upsertDailyNewCompanies(monthStart, monthEnd);
        int leadRows = upsertDailyLeads(monthStart, monthEnd);
        int publishedReviewRows = upsertDailyPublishedReviews(monthStart, monthEnd);
        int adminDailyTotalRows = rebuildAdminDailyTotals(monthStart, monthEnd, periodClosed);
        int ownerDailyTotalRows = rebuildOwnerDailyTotals(monthStart, monthEnd, periodClosed);
        int monthlyUserRows = rebuildMonthlyUsers(monthStart, nextMonthStart, periodClosed, sourceDaysCount);
        int adminTotalRows = rebuildAdminMonthlyTotal(monthStart, nextMonthStart, periodClosed, sourceDaysCount);
        int ownerTotalRows = rebuildOwnerMonthlyTotals(monthStart, nextMonthStart, periodClosed, sourceDaysCount);

        return new AnalyticsAggregateRebuildResult(
                monthStart,
                periodClosed,
                deletedDailyRows,
                salaryRows,
                paymentRows,
                companyRows,
                leadRows,
                publishedReviewRows,
                adminDailyTotalRows,
                ownerDailyTotalRows,
                monthlyUserRows,
                adminTotalRows,
                ownerTotalRows
        );
    }

    @Transactional
    public int rebuildDailyUsers(LocalDate fromInclusive, LocalDate toInclusive) {
        requirePeriod(fromInclusive, toInclusive);
        int deletedRows = deleteDailyRows(fromInclusive, toInclusive);
        upsertDailySalary(fromInclusive, toInclusive);
        upsertDailyPayments(fromInclusive, toInclusive);
        upsertDailyNewCompanies(fromInclusive, toInclusive);
        upsertDailyLeads(fromInclusive, toInclusive);
        upsertDailyPublishedReviews(fromInclusive, toInclusive);
        return deletedRows;
    }

    private int deleteDailyRows(LocalDate fromInclusive, LocalDate toInclusive) {
        return jdbc.update("""
                DELETE FROM analytics_daily_user
                WHERE metric_date BETWEEN :fromInclusive AND :toInclusive
                """, periodParams(fromInclusive, toInclusive));
    }

    private int upsertDailySalary(LocalDate fromInclusive, LocalDate toInclusive) {
        return jdbc.update("""
                INSERT INTO analytics_daily_user (
                    metric_date,
                    user_id,
                    role_name,
                    salary_sum,
                    salary_entry_count,
                    salary_review_count,
                    created_at,
                    updated_at
                )
                SELECT
                    z.zp_date AS metric_date,
                    z.zp_user AS user_id,
                    COALESCE(role_map.role_name, 'ROLE_UNKNOWN') AS role_name,
                    COALESCE(SUM(z.zp_sum), 0) AS salary_sum,
                    COUNT(DISTINCT z.zp_id) AS salary_entry_count,
                    COALESCE(SUM(z.zp_amount), 0) AS salary_review_count,
                    :now AS created_at,
                    :now AS updated_at
                FROM zp z
                LEFT JOIN (
                    SELECT ur.user_id, MIN(r.name) AS role_name
                    FROM users_roles ur
                    JOIN roles r ON r.id = ur.role_id
                    GROUP BY ur.user_id
                ) role_map ON role_map.user_id = z.zp_user
                WHERE z.zp_date BETWEEN :fromInclusive AND :toInclusive
                  AND z.zp_user IS NOT NULL
                GROUP BY z.zp_date, z.zp_user, role_map.role_name
                ON DUPLICATE KEY UPDATE
                    salary_sum = VALUES(salary_sum),
                    salary_entry_count = VALUES(salary_entry_count),
                    salary_review_count = VALUES(salary_review_count),
                    updated_at = VALUES(updated_at)
                """, periodParams(fromInclusive, toInclusive));
    }

    private int upsertDailyPayments(LocalDate fromInclusive, LocalDate toInclusive) {
        return jdbc.update("""
                INSERT INTO analytics_daily_user (
                    metric_date,
                    user_id,
                    role_name,
                    payment_sum,
                    payment_count,
                    created_at,
                    updated_at
                )
                SELECT
                    payment.metric_date,
                    payment.user_id,
                    COALESCE(role_map.role_name, 'ROLE_UNKNOWN') AS role_name,
                    COALESCE(SUM(payment.check_sum), 0) AS payment_sum,
                    COUNT(DISTINCT payment.check_id) AS payment_count,
                    :now AS created_at,
                    :now AS updated_at
                FROM (
                    SELECT
                        pc.check_id,
                        pc.check_date AS metric_date,
                        pc.check_sum,
                        pc.check_manager AS user_id
                    FROM payment_check pc
                    WHERE pc.check_date BETWEEN :fromInclusive AND :toInclusive
                      AND pc.check_active = 1
                      AND pc.check_manager IS NOT NULL

                    UNION ALL

                    SELECT
                        pc.check_id,
                        pc.check_date AS metric_date,
                        pc.check_sum,
                        pc.check_worker AS user_id
                    FROM payment_check pc
                    WHERE pc.check_date BETWEEN :fromInclusive AND :toInclusive
                      AND pc.check_active = 1
                      AND pc.check_worker IS NOT NULL
                      AND (pc.check_manager IS NULL OR pc.check_worker <> pc.check_manager)
                ) payment
                LEFT JOIN (
                    SELECT ur.user_id, MIN(r.name) AS role_name
                    FROM users_roles ur
                    JOIN roles r ON r.id = ur.role_id
                    GROUP BY ur.user_id
                ) role_map ON role_map.user_id = payment.user_id
                GROUP BY payment.metric_date, payment.user_id, role_map.role_name
                ON DUPLICATE KEY UPDATE
                    payment_sum = VALUES(payment_sum),
                    payment_count = VALUES(payment_count),
                    updated_at = VALUES(updated_at)
                """, periodParams(fromInclusive, toInclusive));
    }

    private int upsertDailyNewCompanies(LocalDate fromInclusive, LocalDate toInclusive) {
        return jdbc.update("""
                INSERT INTO analytics_daily_user (
                    metric_date,
                    user_id,
                    role_name,
                    new_companies_count,
                    created_at,
                    updated_at
                )
                SELECT
                    c.create_date AS metric_date,
                    m.user_id AS user_id,
                    COALESCE(role_map.role_name, 'ROLE_UNKNOWN') AS role_name,
                    COUNT(c.company_id) AS new_companies_count,
                    :now AS created_at,
                    :now AS updated_at
                FROM companies c
                JOIN managers m ON m.manager_id = c.company_manager
                LEFT JOIN (
                    SELECT ur.user_id, MIN(r.name) AS role_name
                    FROM users_roles ur
                    JOIN roles r ON r.id = ur.role_id
                    GROUP BY ur.user_id
                ) role_map ON role_map.user_id = m.user_id
                WHERE c.create_date BETWEEN :fromInclusive AND :toInclusive
                  AND m.user_id IS NOT NULL
                GROUP BY c.create_date, m.user_id, role_map.role_name
                ON DUPLICATE KEY UPDATE
                    new_companies_count = VALUES(new_companies_count),
                    updated_at = VALUES(updated_at)
                """, periodParams(fromInclusive, toInclusive));
    }

    private int upsertDailyLeads(LocalDate fromInclusive, LocalDate toInclusive) {
        MapSqlParameterSource params = periodParams(fromInclusive, toInclusive)
                .addValue("statusInWork", STATUS_LEAD_IN_WORK);

        return jdbc.update("""
                INSERT INTO analytics_daily_user (
                    metric_date,
                    user_id,
                    role_name,
                    leads_new_count,
                    leads_in_work_count,
                    created_at,
                    updated_at
                )
                SELECT
                    lead_metric.metric_date,
                    lead_metric.user_id,
                    COALESCE(role_map.role_name, 'ROLE_UNKNOWN') AS role_name,
                    COUNT(DISTINCT lead_metric.lead_id) AS leads_new_count,
                    SUM(CASE WHEN lead_metric.lid_status = :statusInWork THEN 1 ELSE 0 END) AS leads_in_work_count,
                    :now AS created_at,
                    :now AS updated_at
                FROM (
                    SELECT DISTINCT l.id AS lead_id, l.create_date AS metric_date, o.user_id, l.lid_status
                    FROM leads l
                    JOIN operators o ON o.operator_id = l.operator_id
                    WHERE l.create_date BETWEEN :fromInclusive AND :toInclusive
                      AND o.user_id IS NOT NULL

                    UNION

                    SELECT DISTINCT l.id AS lead_id, l.create_date AS metric_date, mk.user_id, l.lid_status
                    FROM leads l
                    JOIN marketologs mk ON mk.marketolog_id = l.marketolog_id
                    WHERE l.create_date BETWEEN :fromInclusive AND :toInclusive
                      AND mk.user_id IS NOT NULL

                    UNION

                    SELECT DISTINCT l.id AS lead_id, l.create_date AS metric_date, m.user_id, l.lid_status
                    FROM leads l
                    JOIN managers m ON m.manager_id = l.manager_id
                    WHERE l.create_date BETWEEN :fromInclusive AND :toInclusive
                      AND m.user_id IS NOT NULL
                ) lead_metric
                LEFT JOIN (
                    SELECT ur.user_id, MIN(r.name) AS role_name
                    FROM users_roles ur
                    JOIN roles r ON r.id = ur.role_id
                    GROUP BY ur.user_id
                ) role_map ON role_map.user_id = lead_metric.user_id
                GROUP BY lead_metric.metric_date, lead_metric.user_id, role_map.role_name
                ON DUPLICATE KEY UPDATE
                    leads_new_count = VALUES(leads_new_count),
                    leads_in_work_count = VALUES(leads_in_work_count),
                    updated_at = VALUES(updated_at)
                """, params);
    }

    private int upsertDailyPublishedReviews(LocalDate fromInclusive, LocalDate toInclusive) {
        return jdbc.update("""
                INSERT INTO analytics_daily_user (
                    metric_date,
                    user_id,
                    role_name,
                    published_reviews_count,
                    created_at,
                    updated_at
                )
                SELECT
                    review_metric.metric_date,
                    review_metric.user_id,
                    COALESCE(role_map.role_name, 'ROLE_UNKNOWN') AS role_name,
                    COUNT(DISTINCT review_metric.review_id) AS published_reviews_count,
                    :now AS created_at,
                    :now AS updated_at
                FROM (
                    SELECT r.review_id, r.review_publish_date AS metric_date, w.user_id
                    FROM reviews r
                    JOIN workers w ON w.worker_id = r.review_worker
                    WHERE r.review_publish_date BETWEEN :fromInclusive AND :toInclusive
                      AND r.review_publish = 1
                      AND w.user_id IS NOT NULL

                    UNION

                    SELECT r.review_id, r.review_publish_date AS metric_date, m.user_id
                    FROM reviews r
                    JOIN order_details od ON od.order_detail_id = r.review_order_details
                    JOIN orders o ON o.order_id = od.order_detail_order
                    JOIN managers m ON m.manager_id = o.order_manager
                    WHERE r.review_publish_date BETWEEN :fromInclusive AND :toInclusive
                      AND r.review_publish = 1
                      AND m.user_id IS NOT NULL
                ) review_metric
                LEFT JOIN (
                    SELECT ur.user_id, MIN(r.name) AS role_name
                    FROM users_roles ur
                    JOIN roles r ON r.id = ur.role_id
                    GROUP BY ur.user_id
                ) role_map ON role_map.user_id = review_metric.user_id
                GROUP BY review_metric.metric_date, review_metric.user_id, role_map.role_name
                ON DUPLICATE KEY UPDATE
                    published_reviews_count = VALUES(published_reviews_count),
                    updated_at = VALUES(updated_at)
                """, periodParams(fromInclusive, toInclusive));
    }

    private int rebuildAdminDailyTotals(LocalDate fromInclusive, LocalDate toInclusive, boolean periodClosed) {
        MapSqlParameterSource params = periodParams(fromInclusive, toInclusive)
                .addValue("periodClosed", periodClosed)
                .addValue("adminScopeKey", AnalyticsAggregateReadService.SCOPE_ADMIN_ALL)
                .addValue("adminScopeType", AnalyticsAggregateReadService.SCOPE_TYPE_ADMIN)
                .addValue("statusInWork", STATUS_LEAD_IN_WORK);

        jdbc.update("""
                DELETE FROM analytics_daily_total
                WHERE metric_date BETWEEN :fromInclusive AND :toInclusive
                  AND scope_key = :adminScopeKey
                """, params);

        return jdbc.update("""
                INSERT INTO analytics_daily_total (
                    metric_date,
                    scope_key,
                    scope_type,
                    scope_user_id,
                    period_closed,
                    source_user_count,
                    salary_sum,
                    salary_entry_count,
                    salary_review_count,
                    payment_sum,
                    payment_count,
                    new_companies_count,
                    published_reviews_count,
                    leads_new_count,
                    leads_in_work_count,
                    last_rebuilt_at,
                    created_at,
                    updated_at
                )
                WITH
                metric_rows AS (
                    SELECT
                        z.zp_date AS metric_date,
                        COALESCE(SUM(z.zp_sum), 0) AS salary_sum,
                        COUNT(z.zp_id) AS salary_entry_count,
                        COALESCE(SUM(z.zp_amount), 0) AS salary_review_count,
                        0 AS payment_sum,
                        0 AS payment_count,
                        0 AS new_companies_count,
                        0 AS published_reviews_count,
                        0 AS leads_new_count,
                        0 AS leads_in_work_count
                    FROM zp z
                    WHERE z.zp_date BETWEEN :fromInclusive AND :toInclusive
                    GROUP BY z.zp_date

                    UNION ALL

                    SELECT
                        payment_check.check_date AS metric_date,
                        0 AS salary_sum,
                        0 AS salary_entry_count,
                        0 AS salary_review_count,
                        COALESCE(SUM(payment_check.check_sum), 0) AS payment_sum,
                        COUNT(payment_check.check_id) AS payment_count,
                        0 AS new_companies_count,
                        0 AS published_reviews_count,
                        0 AS leads_new_count,
                        0 AS leads_in_work_count
                    FROM payment_check
                    WHERE payment_check.check_date BETWEEN :fromInclusive AND :toInclusive
                      AND payment_check.check_active = 1
                    GROUP BY payment_check.check_date

                    UNION ALL

                    SELECT
                        companies.create_date AS metric_date,
                        0 AS salary_sum,
                        0 AS salary_entry_count,
                        0 AS salary_review_count,
                        0 AS payment_sum,
                        0 AS payment_count,
                        COUNT(companies.company_id) AS new_companies_count,
                        0 AS published_reviews_count,
                        0 AS leads_new_count,
                        0 AS leads_in_work_count
                    FROM companies
                    WHERE companies.create_date BETWEEN :fromInclusive AND :toInclusive
                    GROUP BY companies.create_date

                    UNION ALL

                    SELECT
                        reviews.review_publish_date AS metric_date,
                        0 AS salary_sum,
                        0 AS salary_entry_count,
                        0 AS salary_review_count,
                        0 AS payment_sum,
                        0 AS payment_count,
                        0 AS new_companies_count,
                        COUNT(reviews.review_id) AS published_reviews_count,
                        0 AS leads_new_count,
                        0 AS leads_in_work_count
                    FROM reviews
                    WHERE reviews.review_publish_date BETWEEN :fromInclusive AND :toInclusive
                      AND reviews.review_publish = 1
                    GROUP BY reviews.review_publish_date

                    UNION ALL

                    SELECT
                        leads.create_date AS metric_date,
                        0 AS salary_sum,
                        0 AS salary_entry_count,
                        0 AS salary_review_count,
                        0 AS payment_sum,
                        0 AS payment_count,
                        0 AS new_companies_count,
                        0 AS published_reviews_count,
                        COUNT(leads.id) AS leads_new_count,
                        COUNT(CASE WHEN leads.lid_status = :statusInWork THEN leads.id END) AS leads_in_work_count
                    FROM leads
                    WHERE leads.create_date BETWEEN :fromInclusive AND :toInclusive
                    GROUP BY leads.create_date
                ),
                source_users AS (
                    SELECT
                        daily.metric_date,
                        COUNT(DISTINCT daily.user_id) AS source_user_count
                    FROM analytics_daily_user daily
                    WHERE daily.metric_date BETWEEN :fromInclusive AND :toInclusive
                      AND (
                          daily.salary_entry_count > 0
                          OR daily.payment_count > 0
                          OR daily.new_companies_count > 0
                          OR daily.published_reviews_count > 0
                          OR daily.leads_new_count > 0
                          OR daily.leads_in_work_count > 0
                      )
                    GROUP BY daily.metric_date
                )
                SELECT
                    totals.metric_date,
                    :adminScopeKey AS scope_key,
                    :adminScopeType AS scope_type,
                    NULL AS scope_user_id,
                    :periodClosed AS period_closed,
                    COALESCE(source_users.source_user_count, 0) AS source_user_count,
                    COALESCE(SUM(totals.salary_sum), 0) AS salary_sum,
                    COALESCE(SUM(totals.salary_entry_count), 0) AS salary_entry_count,
                    COALESCE(SUM(totals.salary_review_count), 0) AS salary_review_count,
                    COALESCE(SUM(totals.payment_sum), 0) AS payment_sum,
                    COALESCE(SUM(totals.payment_count), 0) AS payment_count,
                    COALESCE(SUM(totals.new_companies_count), 0) AS new_companies_count,
                    COALESCE(SUM(totals.published_reviews_count), 0) AS published_reviews_count,
                    COALESCE(SUM(totals.leads_new_count), 0) AS leads_new_count,
                    COALESCE(SUM(totals.leads_in_work_count), 0) AS leads_in_work_count,
                    :now AS last_rebuilt_at,
                    :now AS created_at,
                    :now AS updated_at
                FROM metric_rows totals
                LEFT JOIN source_users ON source_users.metric_date = totals.metric_date
                GROUP BY totals.metric_date, source_users.source_user_count
                """, params);
    }

    private int rebuildOwnerDailyTotals(LocalDate fromInclusive, LocalDate toInclusive, boolean periodClosed) {
        MapSqlParameterSource params = periodParams(fromInclusive, toInclusive)
                .addValue("periodClosed", periodClosed)
                .addValue("ownerScopeType", AnalyticsAggregateReadService.SCOPE_TYPE_OWNER)
                .addValue("ownerScopeRole", "ROLE_OWNER")
                .addValue("statusInWork", STATUS_LEAD_IN_WORK);

        jdbc.update("""
                DELETE FROM analytics_daily_total
                WHERE metric_date BETWEEN :fromInclusive AND :toInclusive
                  AND scope_type = :ownerScopeType
                """, params);

        return jdbc.update("""
                INSERT INTO analytics_daily_total (
                    metric_date,
                    scope_key,
                    scope_type,
                    scope_user_id,
                    period_closed,
                    source_user_count,
                    salary_sum,
                    salary_entry_count,
                    salary_review_count,
                    payment_sum,
                    payment_count,
                    new_companies_count,
                    published_reviews_count,
                    leads_new_count,
                    leads_in_work_count,
                    last_rebuilt_at,
                    created_at,
                    updated_at
                )
                WITH
                owners AS (
                    SELECT DISTINCT owner.id AS owner_user_id
                    FROM users owner
                    JOIN users_roles owner_role ON owner_role.user_id = owner.id
                    JOIN roles role ON role.id = owner_role.role_id
                    WHERE role.name = :ownerScopeRole
                      AND owner.active = 1
                ),
                owner_managers AS (
                    SELECT DISTINCT
                        owners.owner_user_id,
                        manager_profile.manager_id,
                        manager_profile.user_id AS manager_user_id
                    FROM owners
                    JOIN managers_users owner_manager ON owner_manager.user_id = owners.owner_user_id
                    JOIN managers manager_profile ON manager_profile.manager_id = owner_manager.manager_id
                    WHERE manager_profile.user_id IS NOT NULL
                ),
                owner_visibility AS (
                    SELECT owner_user_id, manager_user_id AS visible_user_id
                    FROM owner_managers

                    UNION

                    SELECT owner_managers.owner_user_id, worker_profile.user_id AS visible_user_id
                    FROM owner_managers
                    JOIN workers_users manager_worker ON manager_worker.user_id = owner_managers.manager_user_id
                    JOIN workers worker_profile ON worker_profile.worker_id = manager_worker.worker_id
                    WHERE worker_profile.user_id IS NOT NULL

                    UNION

                    SELECT owner_managers.owner_user_id, operator_profile.user_id AS visible_user_id
                    FROM owner_managers
                    JOIN operators_users manager_operator ON manager_operator.user_id = owner_managers.manager_user_id
                    JOIN operators operator_profile ON operator_profile.operator_id = manager_operator.operator_id
                    WHERE operator_profile.user_id IS NOT NULL

                    UNION

                    SELECT owner_managers.owner_user_id, marketolog_profile.user_id AS visible_user_id
                    FROM owner_managers
                    JOIN marketologs_users manager_marketolog ON manager_marketolog.user_id = owner_managers.manager_user_id
                    JOIN marketologs marketolog_profile ON marketolog_profile.marketolog_id = manager_marketolog.marketolog_id
                    WHERE marketolog_profile.user_id IS NOT NULL
                ),
                owner_review_ids AS (
                    SELECT owner_visibility.owner_user_id, reviews.review_id, reviews.review_publish_date AS metric_date
                    FROM owner_visibility
                    JOIN workers worker_profile ON worker_profile.user_id = owner_visibility.visible_user_id
                    JOIN reviews ON reviews.review_worker = worker_profile.worker_id
                    WHERE reviews.review_publish_date BETWEEN :fromInclusive AND :toInclusive
                      AND reviews.review_publish = 1

                    UNION

                    SELECT owner_managers.owner_user_id, reviews.review_id, reviews.review_publish_date AS metric_date
                    FROM owner_managers
                    JOIN orders ON orders.order_manager = owner_managers.manager_id
                    JOIN order_details ON order_details.order_detail_order = orders.order_id
                    JOIN reviews ON reviews.review_order_details = order_details.order_detail_id
                    WHERE reviews.review_publish_date BETWEEN :fromInclusive AND :toInclusive
                      AND reviews.review_publish = 1
                ),
                owner_lead_ids AS (
                    SELECT owner_visibility.owner_user_id, leads.id AS lead_id, leads.create_date AS metric_date, leads.lid_status
                    FROM owner_visibility
                    JOIN operators operator_profile ON operator_profile.user_id = owner_visibility.visible_user_id
                    JOIN leads ON leads.operator_id = operator_profile.operator_id
                    WHERE leads.create_date BETWEEN :fromInclusive AND :toInclusive

                    UNION

                    SELECT owner_visibility.owner_user_id, leads.id AS lead_id, leads.create_date AS metric_date, leads.lid_status
                    FROM owner_visibility
                    JOIN marketologs marketolog_profile ON marketolog_profile.user_id = owner_visibility.visible_user_id
                    JOIN leads ON leads.marketolog_id = marketolog_profile.marketolog_id
                    WHERE leads.create_date BETWEEN :fromInclusive AND :toInclusive

                    UNION

                    SELECT owner_managers.owner_user_id, leads.id AS lead_id, leads.create_date AS metric_date, leads.lid_status
                    FROM owner_managers
                    JOIN leads ON leads.manager_id = owner_managers.manager_id
                    WHERE leads.create_date BETWEEN :fromInclusive AND :toInclusive
                ),
                metric_rows AS (
                    SELECT
                        owner_visibility.owner_user_id,
                        z.zp_date AS metric_date,
                        COALESCE(SUM(z.zp_sum), 0) AS salary_sum,
                        COUNT(z.zp_id) AS salary_entry_count,
                        COALESCE(SUM(z.zp_amount), 0) AS salary_review_count,
                        0 AS payment_sum,
                        0 AS payment_count,
                        0 AS new_companies_count,
                        0 AS published_reviews_count,
                        0 AS leads_new_count,
                        0 AS leads_in_work_count
                    FROM owner_visibility
                    JOIN zp z ON z.zp_user = owner_visibility.visible_user_id
                    WHERE z.zp_date BETWEEN :fromInclusive AND :toInclusive
                    GROUP BY owner_visibility.owner_user_id, z.zp_date

                    UNION ALL

                    SELECT
                        owner_managers.owner_user_id,
                        payment_check.check_date AS metric_date,
                        0 AS salary_sum,
                        0 AS salary_entry_count,
                        0 AS salary_review_count,
                        COALESCE(SUM(payment_check.check_sum), 0) AS payment_sum,
                        COUNT(payment_check.check_id) AS payment_count,
                        0 AS new_companies_count,
                        0 AS published_reviews_count,
                        0 AS leads_new_count,
                        0 AS leads_in_work_count
                    FROM owner_managers
                    JOIN payment_check ON payment_check.check_manager = owner_managers.manager_user_id
                    WHERE payment_check.check_date BETWEEN :fromInclusive AND :toInclusive
                      AND payment_check.check_active = 1
                    GROUP BY owner_managers.owner_user_id, payment_check.check_date

                    UNION ALL

                    SELECT
                        owner_managers.owner_user_id,
                        companies.create_date AS metric_date,
                        0 AS salary_sum,
                        0 AS salary_entry_count,
                        0 AS salary_review_count,
                        0 AS payment_sum,
                        0 AS payment_count,
                        COUNT(companies.company_id) AS new_companies_count,
                        0 AS published_reviews_count,
                        0 AS leads_new_count,
                        0 AS leads_in_work_count
                    FROM owner_managers
                    JOIN companies ON companies.company_manager = owner_managers.manager_id
                    WHERE companies.create_date BETWEEN :fromInclusive AND :toInclusive
                    GROUP BY owner_managers.owner_user_id, companies.create_date

                    UNION ALL

                    SELECT
                        owner_review_ids.owner_user_id,
                        owner_review_ids.metric_date,
                        0 AS salary_sum,
                        0 AS salary_entry_count,
                        0 AS salary_review_count,
                        0 AS payment_sum,
                        0 AS payment_count,
                        0 AS new_companies_count,
                        COUNT(DISTINCT owner_review_ids.review_id) AS published_reviews_count,
                        0 AS leads_new_count,
                        0 AS leads_in_work_count
                    FROM owner_review_ids
                    GROUP BY owner_review_ids.owner_user_id, owner_review_ids.metric_date

                    UNION ALL

                    SELECT
                        owner_lead_ids.owner_user_id,
                        owner_lead_ids.metric_date,
                        0 AS salary_sum,
                        0 AS salary_entry_count,
                        0 AS salary_review_count,
                        0 AS payment_sum,
                        0 AS payment_count,
                        0 AS new_companies_count,
                        0 AS published_reviews_count,
                        COUNT(DISTINCT owner_lead_ids.lead_id) AS leads_new_count,
                        COUNT(DISTINCT CASE WHEN owner_lead_ids.lid_status = :statusInWork THEN owner_lead_ids.lead_id END) AS leads_in_work_count
                    FROM owner_lead_ids
                    GROUP BY owner_lead_ids.owner_user_id, owner_lead_ids.metric_date
                ),
                source_users AS (
                    SELECT
                        owner_visibility.owner_user_id,
                        daily.metric_date,
                        COUNT(DISTINCT daily.user_id) AS source_user_count
                    FROM owner_visibility
                    JOIN analytics_daily_user daily
                      ON daily.user_id = owner_visibility.visible_user_id
                     AND daily.metric_date BETWEEN :fromInclusive AND :toInclusive
                    WHERE daily.salary_entry_count > 0
                       OR daily.payment_count > 0
                       OR daily.new_companies_count > 0
                       OR daily.published_reviews_count > 0
                       OR daily.leads_new_count > 0
                       OR daily.leads_in_work_count > 0
                    GROUP BY owner_visibility.owner_user_id, daily.metric_date
                )
                SELECT
                    totals.metric_date,
                    CONCAT(:ownerScopeType, ':', totals.owner_user_id) AS scope_key,
                    :ownerScopeType AS scope_type,
                    totals.owner_user_id AS scope_user_id,
                    :periodClosed AS period_closed,
                    COALESCE(source_users.source_user_count, 0) AS source_user_count,
                    COALESCE(SUM(totals.salary_sum), 0) AS salary_sum,
                    COALESCE(SUM(totals.salary_entry_count), 0) AS salary_entry_count,
                    COALESCE(SUM(totals.salary_review_count), 0) AS salary_review_count,
                    COALESCE(SUM(totals.payment_sum), 0) AS payment_sum,
                    COALESCE(SUM(totals.payment_count), 0) AS payment_count,
                    COALESCE(SUM(totals.new_companies_count), 0) AS new_companies_count,
                    COALESCE(SUM(totals.published_reviews_count), 0) AS published_reviews_count,
                    COALESCE(SUM(totals.leads_new_count), 0) AS leads_new_count,
                    COALESCE(SUM(totals.leads_in_work_count), 0) AS leads_in_work_count,
                    :now AS last_rebuilt_at,
                    :now AS created_at,
                    :now AS updated_at
                FROM metric_rows totals
                LEFT JOIN source_users
                  ON source_users.owner_user_id = totals.owner_user_id
                 AND source_users.metric_date = totals.metric_date
                GROUP BY totals.owner_user_id, totals.metric_date, source_users.source_user_count
                """, params);
    }

    private int rebuildMonthlyUsers(
            LocalDate monthStart,
            LocalDate nextMonthStart,
            boolean periodClosed,
            int sourceDaysCount
    ) {
        MapSqlParameterSource params = monthParams(monthStart, periodClosed, sourceDaysCount)
                .addValue("nextMonthStart", nextMonthStart);

        jdbc.update("""
                DELETE FROM analytics_monthly_user
                WHERE month_start = :monthStart
                """, params);

        return jdbc.update("""
                INSERT INTO analytics_monthly_user (
                    month_start,
                    user_id,
                    role_name,
                    manager_id,
                    manager_user_id,
                    period_closed,
                    source_days_count,
                    salary_sum,
                    salary_entry_count,
                    salary_review_count,
                    payment_sum,
                    payment_count,
                    new_companies_count,
                    published_reviews_count,
                    leads_new_count,
                    leads_in_work_count,
                    last_rebuilt_at,
                    created_at,
                    updated_at
                )
                SELECT
                    :monthStart AS month_start,
                    u.id AS user_id,
                    COALESCE(role_map.role_name, 'ROLE_UNKNOWN') AS role_name,
                    manager_map.manager_id,
                    manager_map.manager_user_id,
                    :periodClosed AS period_closed,
                    :sourceDaysCount AS source_days_count,
                    COALESCE(daily.salary_sum, 0) AS salary_sum,
                    COALESCE(daily.salary_entry_count, 0) AS salary_entry_count,
                    COALESCE(daily.salary_review_count, 0) AS salary_review_count,
                    COALESCE(daily.payment_sum, 0) AS payment_sum,
                    COALESCE(daily.payment_count, 0) AS payment_count,
                    COALESCE(daily.new_companies_count, 0) AS new_companies_count,
                    COALESCE(daily.published_reviews_count, 0) AS published_reviews_count,
                    COALESCE(daily.leads_new_count, 0) AS leads_new_count,
                    COALESCE(daily.leads_in_work_count, 0) AS leads_in_work_count,
                    :now AS last_rebuilt_at,
                    :now AS created_at,
                    :now AS updated_at
                FROM users u
                LEFT JOIN (
                    SELECT ur.user_id, MIN(r.name) AS role_name
                    FROM users_roles ur
                    JOIN roles r ON r.id = ur.role_id
                    GROUP BY ur.user_id
                ) role_map ON role_map.user_id = u.id
                LEFT JOIN (
                    SELECT visible_user_id, MIN(manager_id) AS manager_id, MIN(manager_user_id) AS manager_user_id
                    FROM (
                        SELECT m.user_id AS visible_user_id, m.manager_id, m.user_id AS manager_user_id
                        FROM managers m
                        WHERE m.user_id IS NOT NULL

                        UNION ALL

                        SELECT w.user_id AS visible_user_id, m.manager_id, m.user_id AS manager_user_id
                        FROM managers m
                        JOIN workers_users wu ON wu.user_id = m.user_id
                        JOIN workers w ON w.worker_id = wu.worker_id
                        WHERE m.user_id IS NOT NULL
                          AND w.user_id IS NOT NULL

                        UNION ALL

                        SELECT o.user_id AS visible_user_id, m.manager_id, m.user_id AS manager_user_id
                        FROM managers m
                        JOIN operators_users ou ON ou.user_id = m.user_id
                        JOIN operators o ON o.operator_id = ou.operator_id
                        WHERE m.user_id IS NOT NULL
                          AND o.user_id IS NOT NULL

                        UNION ALL

                        SELECT mk.user_id AS visible_user_id, m.manager_id, m.user_id AS manager_user_id
                        FROM managers m
                        JOIN marketologs_users mu ON mu.user_id = m.user_id
                        JOIN marketologs mk ON mk.marketolog_id = mu.marketolog_id
                        WHERE m.user_id IS NOT NULL
                          AND mk.user_id IS NOT NULL
                    ) manager_relations
                    GROUP BY visible_user_id
                ) manager_map ON manager_map.visible_user_id = u.id
                LEFT JOIN (
                    SELECT
                        user_id,
                        SUM(salary_sum) AS salary_sum,
                        SUM(salary_entry_count) AS salary_entry_count,
                        SUM(salary_review_count) AS salary_review_count,
                        SUM(payment_sum) AS payment_sum,
                        SUM(payment_count) AS payment_count,
                        SUM(new_companies_count) AS new_companies_count,
                        SUM(published_reviews_count) AS published_reviews_count,
                        SUM(leads_new_count) AS leads_new_count,
                        SUM(leads_in_work_count) AS leads_in_work_count
                    FROM analytics_daily_user
                    WHERE metric_date >= :monthStart
                      AND metric_date < :nextMonthStart
                    GROUP BY user_id
                ) daily ON daily.user_id = u.id
                """, params);
    }

    private int rebuildAdminMonthlyTotal(
            LocalDate monthStart,
            LocalDate nextMonthStart,
            boolean periodClosed,
            int sourceDaysCount
    ) {
        MapSqlParameterSource params = monthParams(monthStart, periodClosed, sourceDaysCount)
                .addValue("nextMonthStart", nextMonthStart)
                .addValue("statusInWork", STATUS_LEAD_IN_WORK);

        jdbc.update("""
                DELETE FROM analytics_monthly_total
                WHERE month_start = :monthStart
                  AND scope_key = :adminScopeKey
                """, params);

        return jdbc.update("""
                INSERT INTO analytics_monthly_total (
                    month_start,
                    scope_key,
                    scope_type,
                    scope_user_id,
                    period_closed,
                    source_user_count,
                    source_days_count,
                    salary_sum,
                    salary_entry_count,
                    salary_review_count,
                    payment_sum,
                    payment_count,
                    new_companies_count,
                    published_reviews_count,
                    leads_new_count,
                    leads_in_work_count,
                    last_rebuilt_at,
                    created_at,
                    updated_at
                )
                SELECT
                    :monthStart AS month_start,
                    :adminScopeKey AS scope_key,
                    :adminScopeType AS scope_type,
                    NULL AS scope_user_id,
                    :periodClosed AS period_closed,
                    (
                        SELECT COUNT(*)
                        FROM analytics_monthly_user monthly
                        WHERE monthly.month_start = :monthStart
                          AND (
                              monthly.salary_entry_count > 0
                              OR monthly.payment_count > 0
                              OR monthly.new_companies_count > 0
                              OR monthly.published_reviews_count > 0
                              OR monthly.leads_new_count > 0
                              OR monthly.leads_in_work_count > 0
                          )
                    ) AS source_user_count,
                    :sourceDaysCount AS source_days_count,
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
                    ) AS leads_in_work_count,
                    :now AS last_rebuilt_at,
                    :now AS created_at,
                    :now AS updated_at
                """, params);
    }

    private int rebuildOwnerMonthlyTotals(
            LocalDate monthStart,
            LocalDate nextMonthStart,
            boolean periodClosed,
            int sourceDaysCount
    ) {
        MapSqlParameterSource params = monthParams(monthStart, periodClosed, sourceDaysCount)
                .addValue("nextMonthStart", nextMonthStart)
                .addValue("statusInWork", STATUS_LEAD_IN_WORK);

        jdbc.update("""
                DELETE FROM analytics_monthly_total
                WHERE month_start = :monthStart
                  AND scope_type = :ownerScopeType
                """, params);

        return jdbc.update("""
                INSERT INTO analytics_monthly_total (
                    month_start,
                    scope_key,
                    scope_type,
                    scope_user_id,
                    period_closed,
                    source_user_count,
                    source_days_count,
                    salary_sum,
                    salary_entry_count,
                    salary_review_count,
                    payment_sum,
                    payment_count,
                    new_companies_count,
                    published_reviews_count,
                    leads_new_count,
                    leads_in_work_count,
                    last_rebuilt_at,
                    created_at,
                    updated_at
                )
                WITH
                owners AS (
                    SELECT DISTINCT owner.id AS owner_user_id
                    FROM users owner
                    JOIN users_roles owner_role ON owner_role.user_id = owner.id
                    JOIN roles role ON role.id = owner_role.role_id
                    WHERE role.name = :ownerScopeRole
                      AND owner.active = 1
                ),
                owner_managers AS (
                    SELECT DISTINCT
                        owners.owner_user_id,
                        manager_profile.manager_id,
                        manager_profile.user_id AS manager_user_id
                    FROM owners
                    JOIN managers_users owner_manager ON owner_manager.user_id = owners.owner_user_id
                    JOIN managers manager_profile ON manager_profile.manager_id = owner_manager.manager_id
                    WHERE manager_profile.user_id IS NOT NULL
                ),
                owner_visibility AS (
                    SELECT owner_user_id, manager_user_id AS visible_user_id
                    FROM owner_managers

                    UNION

                    SELECT owner_managers.owner_user_id, worker_profile.user_id AS visible_user_id
                    FROM owner_managers
                    JOIN workers_users manager_worker ON manager_worker.user_id = owner_managers.manager_user_id
                    JOIN workers worker_profile ON worker_profile.worker_id = manager_worker.worker_id
                    WHERE worker_profile.user_id IS NOT NULL

                    UNION

                    SELECT owner_managers.owner_user_id, operator_profile.user_id AS visible_user_id
                    FROM owner_managers
                    JOIN operators_users manager_operator ON manager_operator.user_id = owner_managers.manager_user_id
                    JOIN operators operator_profile ON operator_profile.operator_id = manager_operator.operator_id
                    WHERE operator_profile.user_id IS NOT NULL

                    UNION

                    SELECT owner_managers.owner_user_id, marketolog_profile.user_id AS visible_user_id
                    FROM owner_managers
                    JOIN marketologs_users manager_marketolog ON manager_marketolog.user_id = owner_managers.manager_user_id
                    JOIN marketologs marketolog_profile ON marketolog_profile.marketolog_id = manager_marketolog.marketolog_id
                    WHERE marketolog_profile.user_id IS NOT NULL
                ),
                user_metrics AS (
                    SELECT
                        owner_visibility.owner_user_id,
                        COUNT(DISTINCT CASE
                            WHEN monthly.salary_entry_count > 0
                              OR monthly.payment_count > 0
                              OR monthly.new_companies_count > 0
                              OR monthly.published_reviews_count > 0
                              OR monthly.leads_new_count > 0
                              OR monthly.leads_in_work_count > 0
                            THEN monthly.user_id
                        END) AS source_user_count,
                        COALESCE(SUM(monthly.salary_sum), 0) AS salary_sum,
                        COALESCE(SUM(monthly.salary_entry_count), 0) AS salary_entry_count,
                        COALESCE(SUM(monthly.salary_review_count), 0) AS salary_review_count
                    FROM owner_visibility
                    JOIN analytics_monthly_user monthly
                      ON monthly.user_id = owner_visibility.visible_user_id
                     AND monthly.month_start = :monthStart
                    GROUP BY owner_visibility.owner_user_id
                ),
                owner_payments AS (
                    SELECT
                        owner_managers.owner_user_id,
                        COALESCE(SUM(payment_check.check_sum), 0) AS payment_sum,
                        COUNT(payment_check.check_id) AS payment_count
                    FROM owner_managers
                    JOIN payment_check
                      ON payment_check.check_manager = owner_managers.manager_user_id
                    WHERE payment_check.check_date >= :monthStart
                      AND payment_check.check_date < :nextMonthStart
                      AND payment_check.check_active = 1
                    GROUP BY owner_managers.owner_user_id
                ),
                owner_companies AS (
                    SELECT
                        owner_managers.owner_user_id,
                        COUNT(companies.company_id) AS new_companies_count
                    FROM owner_managers
                    JOIN companies
                      ON companies.company_manager = owner_managers.manager_id
                    WHERE companies.create_date >= :monthStart
                      AND companies.create_date < :nextMonthStart
                    GROUP BY owner_managers.owner_user_id
                ),
                owner_review_ids AS (
                    SELECT owner_visibility.owner_user_id, reviews.review_id
                    FROM owner_visibility
                    JOIN workers worker_profile ON worker_profile.user_id = owner_visibility.visible_user_id
                    JOIN reviews ON reviews.review_worker = worker_profile.worker_id
                    WHERE reviews.review_publish_date >= :monthStart
                      AND reviews.review_publish_date < :nextMonthStart
                      AND reviews.review_publish = 1

                    UNION

                    SELECT owner_managers.owner_user_id, reviews.review_id
                    FROM owner_managers
                    JOIN orders ON orders.order_manager = owner_managers.manager_id
                    JOIN order_details ON order_details.order_detail_order = orders.order_id
                    JOIN reviews ON reviews.review_order_details = order_details.order_detail_id
                    WHERE reviews.review_publish_date >= :monthStart
                      AND reviews.review_publish_date < :nextMonthStart
                      AND reviews.review_publish = 1
                ),
                owner_reviews AS (
                    SELECT owner_user_id, COUNT(DISTINCT review_id) AS published_reviews_count
                    FROM owner_review_ids
                    GROUP BY owner_user_id
                ),
                owner_lead_ids AS (
                    SELECT owner_visibility.owner_user_id, leads.id AS lead_id, leads.lid_status
                    FROM owner_visibility
                    JOIN operators operator_profile ON operator_profile.user_id = owner_visibility.visible_user_id
                    JOIN leads ON leads.operator_id = operator_profile.operator_id
                    WHERE leads.create_date >= :monthStart
                      AND leads.create_date < :nextMonthStart

                    UNION

                    SELECT owner_visibility.owner_user_id, leads.id AS lead_id, leads.lid_status
                    FROM owner_visibility
                    JOIN marketologs marketolog_profile ON marketolog_profile.user_id = owner_visibility.visible_user_id
                    JOIN leads ON leads.marketolog_id = marketolog_profile.marketolog_id
                    WHERE leads.create_date >= :monthStart
                      AND leads.create_date < :nextMonthStart

                    UNION

                    SELECT owner_managers.owner_user_id, leads.id AS lead_id, leads.lid_status
                    FROM owner_managers
                    JOIN leads ON leads.manager_id = owner_managers.manager_id
                    WHERE leads.create_date >= :monthStart
                      AND leads.create_date < :nextMonthStart
                ),
                owner_leads AS (
                    SELECT
                        owner_user_id,
                        COUNT(DISTINCT lead_id) AS leads_new_count,
                        COUNT(DISTINCT CASE WHEN lid_status = :statusInWork THEN lead_id END) AS leads_in_work_count
                    FROM owner_lead_ids
                    GROUP BY owner_user_id
                )
                SELECT
                    :monthStart AS month_start,
                    CONCAT(:ownerScopeType, ':', owners.owner_user_id) AS scope_key,
                    :ownerScopeType AS scope_type,
                    owners.owner_user_id AS scope_user_id,
                    :periodClosed AS period_closed,
                    COALESCE(user_metrics.source_user_count, 0) AS source_user_count,
                    :sourceDaysCount AS source_days_count,
                    COALESCE(user_metrics.salary_sum, 0) AS salary_sum,
                    COALESCE(user_metrics.salary_entry_count, 0) AS salary_entry_count,
                    COALESCE(user_metrics.salary_review_count, 0) AS salary_review_count,
                    COALESCE(owner_payments.payment_sum, 0) AS payment_sum,
                    COALESCE(owner_payments.payment_count, 0) AS payment_count,
                    COALESCE(owner_companies.new_companies_count, 0) AS new_companies_count,
                    COALESCE(owner_reviews.published_reviews_count, 0) AS published_reviews_count,
                    COALESCE(owner_leads.leads_new_count, 0) AS leads_new_count,
                    COALESCE(owner_leads.leads_in_work_count, 0) AS leads_in_work_count,
                    :now AS last_rebuilt_at,
                    :now AS created_at,
                    :now AS updated_at
                FROM owners
                LEFT JOIN user_metrics ON user_metrics.owner_user_id = owners.owner_user_id
                LEFT JOIN owner_payments ON owner_payments.owner_user_id = owners.owner_user_id
                LEFT JOIN owner_companies ON owner_companies.owner_user_id = owners.owner_user_id
                LEFT JOIN owner_reviews ON owner_reviews.owner_user_id = owners.owner_user_id
                LEFT JOIN owner_leads ON owner_leads.owner_user_id = owners.owner_user_id
                """, params);
    }

    private MapSqlParameterSource periodParams(LocalDate fromInclusive, LocalDate toInclusive) {
        return new MapSqlParameterSource()
                .addValue("fromInclusive", fromInclusive)
                .addValue("toInclusive", toInclusive)
                .addValue("now", Instant.now());
    }

    private MapSqlParameterSource monthParams(LocalDate monthStart, boolean periodClosed, int sourceDaysCount) {
        return new MapSqlParameterSource()
                .addValue("monthStart", monthStart)
                .addValue("periodClosed", periodClosed)
                .addValue("sourceDaysCount", sourceDaysCount)
                .addValue("adminScopeKey", AnalyticsAggregateReadService.SCOPE_ADMIN_ALL)
                .addValue("adminScopeType", AnalyticsAggregateReadService.SCOPE_TYPE_ADMIN)
                .addValue("ownerScopeType", AnalyticsAggregateReadService.SCOPE_TYPE_OWNER)
                .addValue("ownerScopeRole", "ROLE_OWNER")
                .addValue("now", Instant.now());
    }

    private static void requirePeriod(LocalDate fromInclusive, LocalDate toInclusive) {
        if (fromInclusive == null || toInclusive == null) {
            throw new IllegalArgumentException("period dates must not be null");
        }
        if (fromInclusive.isAfter(toInclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before or equal to toInclusive");
        }
    }

    public record AnalyticsAggregateRebuildResult(
            LocalDate monthStart,
            boolean periodClosed,
            int deletedDailyRows,
            int salaryRows,
            int paymentRows,
            int companyRows,
            int leadRows,
            int publishedReviewRows,
            int adminDailyTotalRows,
            int ownerDailyTotalRows,
            int monthlyUserRows,
            int adminTotalRows,
            int ownerTotalRows
    ) {
    }
}
