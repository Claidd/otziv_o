package com.hunt.otziv.notification_media.service;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Small, scoped reads. A client hint never grants access to another person's card. */
@Repository
@RequiredArgsConstructor
public class ContextualMediaFacts {
    private final NamedParameterJdbcTemplate jdbc;

    public Map<String, Object> recipient(long userId) {
        return one("""
                SELECT u.id, u.telegram_chat_id, u.worker_telegram_group_chat_id,
                       m.audit_telegram_group_chat_id
                FROM users u LEFT JOIN managers m ON m.user_id=u.id
                WHERE u.id=:userId AND u.active=b'1'
                """, userId, null);
    }

    public Map<String, Object> ownedCard(long userId, String type, Long id) {
        if (id == null || id <= 0 || type == null) return Map.of();
        String sql = switch (type) {
            case "review" -> """
                    SELECT r.review_id AS id, r.review_text AS text, r.review_vigul AS walked,
                           r.review_publish AS published, COALESCE(b.bot_counter,2) AS account_count
                    FROM reviews r JOIN workers w ON w.worker_id=r.review_worker
                    LEFT JOIN bots b ON b.bot_id=r.review_bot
                    WHERE r.review_id=:id AND w.user_id=:userId
                    """;
            case "recovery_task" -> """
                    SELECT t.review_recovery_task_id AS id, t.review_recovery_task_status AS status
                    FROM review_recovery_tasks t
                    JOIN workers w ON w.worker_id=t.review_recovery_task_worker
                    JOIN review_recovery_batches b ON b.review_recovery_batch_id=t.review_recovery_task_batch
                    LEFT JOIN orders o ON o.order_id=t.review_recovery_task_order
                    WHERE b.review_recovery_batch_status='OPEN'
                      AND (o.order_id IS NULL OR o.order_worker=t.review_recovery_task_worker) AND t.review_recovery_task_id=:id AND w.user_id=:userId
                    """;
            case "bad_review_task" -> """
                    SELECT t.bad_review_task_id AS id, t.bad_review_task_status AS status
                    FROM bad_review_tasks t JOIN workers w ON w.worker_id=t.bad_review_task_worker
                    JOIN orders o ON o.order_id=t.bad_review_task_order AND o.order_worker=t.bad_review_task_worker
                    WHERE t.bad_review_task_id=:id AND w.user_id=:userId
                    """;
            case "order" -> """
                    SELECT o.order_id AS id, s.order_status_title AS status
                    FROM orders o JOIN workers w ON w.worker_id=o.order_worker
                    JOIN order_statuses s ON s.order_status_id=o.order_status
                    WHERE o.order_id=:id AND w.user_id=:userId
                    """;
            default -> null;
        };
        return sql == null ? Map.of() : one(sql, userId, id);
    }

    public long publicationsToday(long userId) {
        return count("""
                SELECT COUNT(DISTINCT e.review_id) FROM worker_activity_events e
                JOIN reviews r ON r.review_id=e.review_id
                JOIN workers w ON w.worker_id=r.review_worker
                WHERE e.worker_user_id=:userId AND w.user_id=:userId
                  AND e.action='REVIEW_PUBLISH' AND r.review_publish=b'1'
                  AND e.created_at>=:dayStart AND e.created_at<:dayEnd
                """, userId);
    }

    public boolean networkBlocked(long userId) {
        return count("""
                SELECT COUNT(*) FROM worker_network_violation_episodes
                WHERE worker_user_id=:userId AND access_result='BLOCKED'
                  AND reason_code='NON_CELLULAR_NETWORK' AND last_seen_at>=:recent
                """, userId) > 0;
    }

    public boolean unansweredClientChat(long userId) {
        return count("""
                SELECT COUNT(*) FROM manager_daily_control_concrete_items i
                JOIN manager_daily_controls c ON c.daily_control_id=i.control_id
                JOIN client_chat_unanswered_items chat ON chat.id=i.entity_id
                JOIN managers m ON m.manager_id=chat.manager_id AND m.user_id=c.manager_user_id
                WHERE chat.platform='WHATSAPP' AND chat.status='OPEN' AND c.manager_user_id=:userId AND c.control_date=:today
                  AND i.entity_type='CLIENT_CHAT_UNANSWERED' AND i.item_status='OPEN'
                """, userId) > 0;
    }

    public boolean unpaidOrder(long userId) {
        return count("""
                SELECT COUNT(*) FROM orders o JOIN managers m ON m.manager_id=o.order_manager
                JOIN order_statuses s ON s.order_status_id=o.order_status
                WHERE m.user_id=:userId AND s.order_status_title='Оплата'
                  AND o.order_pay_day IS NULL
                """, userId) > 0;
    }

    private Map<String, Object> one(String sql, long userId, Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql,
                new MapSqlParameterSource("userId", userId).addValue("id", id));
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private long count(String sql, long userId) {
        var now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Irkutsk"));
        Number value = jdbc.queryForObject(sql, new MapSqlParameterSource("userId", userId)
                .addValue("today", now.toLocalDate()).addValue("dayStart", now.toLocalDate().atStartOfDay())
                .addValue("dayEnd", now.toLocalDate().plusDays(1).atStartOfDay())
                .addValue("recent", now.minusMinutes(5)), Long.class);
        return value == null ? 0 : value.longValue();
    }
}
