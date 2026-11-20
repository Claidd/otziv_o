package com.hunt.otziv.worker_performance.service;

/** A handoff counts once per recorded work episode; archiving is not completed writing. */
final class OrderCompletionQuery {
    private OrderCompletionQuery() {}
    static final String SQL = """
        SELECT o.order_worker AS worker_id, 'order' AS item_type, e.order_id AS item_id,
               COALESCE((SELECT MAX(started.created_at) FROM business_audit_events started
                         WHERE started.order_id=e.order_id AND started.action='order_status_changed'
                           AND started.new_value IN ('Новый','Коррекция') AND started.created_at<e.created_at),
                        TIMESTAMP(o.order_created) + INTERVAL 10 HOUR) AS opened_at,
               e.created_at AS done_at
        FROM business_audit_events e JOIN orders o ON o.order_id=e.order_id
        WHERE o.order_worker IN (:workerIds) AND e.action='order_status_changed'
          AND e.created_at>=:from AND e.created_at<:to
          AND e.old_value IN ('Новый','Коррекция')
          AND e.new_value IN ('В проверку','На проверке','Публикация','Опубликовано')
          AND o.order_waiting_for_client=FALSE
          AND NOT EXISTS (
              SELECT 1 FROM business_audit_events done
              WHERE done.order_id=e.order_id AND done.action='order_status_changed'
                AND done.old_value IN ('Новый','Коррекция')
                AND done.new_value IN ('В проверку','На проверке','Публикация','Опубликовано')
                AND (done.created_at<e.created_at OR (done.created_at=e.created_at AND done.event_id<e.event_id))
                AND NOT EXISTS (
                    SELECT 1 FROM business_audit_events reopened
                    WHERE reopened.order_id=e.order_id AND reopened.action='order_status_changed'
                      AND reopened.new_value IN ('Новый','Коррекция')
                      AND reopened.created_at>done.created_at AND reopened.created_at<e.created_at))
        """;
}
