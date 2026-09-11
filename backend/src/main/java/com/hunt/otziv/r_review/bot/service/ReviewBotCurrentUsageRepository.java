package com.hunt.otziv.r_review.bot.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Current reads, under the account's write lock; ordinary SELECT can use an old RR snapshot. */
@Repository
@RequiredArgsConstructor
public class ReviewBotCurrentUsageRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isUsed(Long botId, Set<Long> companyIds, ReviewBotAssignmentGuardService.AssignmentScope scope) {
        entityManager.flush();
        var params = new MapSqlParameterSource("bot", botId).addValue("companies", companyIds)
                .addValue("review", scope.excludedReviewId()).addValue("bad", scope.excludedBadTaskId())
                .addValue("recovery", scope.excludedRecoveryTaskId());
        return exists("""
                SELECT r.review_id FROM reviews r
                LEFT JOIN filial f ON f.filial_id = r.review_filial
                LEFT JOIN order_details d ON d.order_detail_id = r.review_order_details
                LEFT JOIN orders o ON o.order_id = d.order_detail_order
                WHERE r.review_bot = :bot AND (
                    COALESCE(f.company_id, o.order_company) IN (:companies)
                    OR (r.review_publish = FALSE AND (:review IS NULL OR r.review_id <> :review)))
                LIMIT 1 FOR SHARE
                """, params)
                || exists("""
                SELECT r.review_id FROM archive_reviews r
                LEFT JOIN filial f ON f.filial_id = r.review_filial
                LEFT JOIN archive_order_details d ON d.order_detail_id = r.review_order_details
                LEFT JOIN archive_orders o ON o.order_id = d.order_detail_order
                WHERE r.review_bot = :bot AND o.restored_at IS NULL
                  AND COALESCE(f.company_id, o.order_company) IN (:companies)
                LIMIT 1 FOR SHARE
                """, params)
                || exists("""
                SELECT t.bad_review_task_id FROM bad_review_tasks t
                LEFT JOIN orders o ON o.order_id = t.bad_review_task_order
                WHERE t.bad_review_task_bot = :bot
                  AND (:bad IS NULL OR t.bad_review_task_id <> :bad)
                  AND (t.bad_review_task_status = 'NEW'
                    OR (t.bad_review_task_status = 'DONE' AND o.order_company IN (:companies)))
                LIMIT 1 FOR SHARE
                """, params)
                || exists("""
                SELECT t.review_recovery_task_id FROM review_recovery_tasks t
                LEFT JOIN orders o ON o.order_id = t.review_recovery_task_order
                WHERE t.review_recovery_task_bot = :bot
                  AND (:recovery IS NULL OR t.review_recovery_task_id <> :recovery)
                  AND (t.review_recovery_task_status = 'PLANNED'
                    OR (t.review_recovery_task_status = 'DONE' AND (
                        o.order_company IN (:companies)
                        OR t.review_recovery_task_archive_company_id IN (:companies))))
                LIMIT 1 FOR SHARE
                """, params);
    }

    private boolean exists(String sql, MapSqlParameterSource params) {
        return !jdbc.query(sql, params, (rs, row) -> rs.getLong(1)).isEmpty();
    }
}
