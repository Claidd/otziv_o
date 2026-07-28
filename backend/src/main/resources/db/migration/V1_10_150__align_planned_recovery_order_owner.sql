INSERT INTO business_audit_events (
    created_at,
    actor,
    source,
    action,
    entity_type,
    entity_id,
    order_id,
    review_id,
    old_value,
    new_value,
    details
)
SELECT
    NOW(6),
    'system',
    'flyway',
    'RECOVERY_WORKER_REALIGNED',
    'RECOVERY_TASK',
    CAST(task.review_recovery_task_id AS CHAR),
    task.review_recovery_task_order,
    task.review_recovery_task_review,
    CAST(task.review_recovery_task_worker AS CHAR),
    CAST(orders.order_worker AS CHAR),
    'Незавершённое восстановление синхронизировано с текущим владельцем заказа'
FROM review_recovery_tasks task
JOIN review_recovery_batches batch
  ON batch.review_recovery_batch_id = task.review_recovery_task_batch
JOIN orders
  ON orders.order_id = task.review_recovery_task_order
WHERE task.review_recovery_task_status = 'PLANNED'
  AND batch.review_recovery_batch_status = 'OPEN'
  AND orders.order_worker IS NOT NULL
  AND (
      task.review_recovery_task_worker IS NULL
      OR task.review_recovery_task_worker <> orders.order_worker
  );

UPDATE review_recovery_tasks task
JOIN review_recovery_batches batch
  ON batch.review_recovery_batch_id = task.review_recovery_task_batch
JOIN orders
  ON orders.order_id = task.review_recovery_task_order
SET task.review_recovery_task_worker = orders.order_worker
WHERE task.review_recovery_task_status = 'PLANNED'
  AND batch.review_recovery_batch_status = 'OPEN'
  AND orders.order_worker IS NOT NULL
  AND (
      task.review_recovery_task_worker IS NULL
      OR task.review_recovery_task_worker <> orders.order_worker
  );
