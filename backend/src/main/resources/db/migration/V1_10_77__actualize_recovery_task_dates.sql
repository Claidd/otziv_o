UPDATE review_recovery_tasks task
JOIN review_recovery_batches batch
  ON batch.review_recovery_batch_id = task.review_recovery_task_batch
SET task.review_recovery_task_scheduled_date = CURRENT_DATE,
    task.review_recovery_task_updated_at = CURRENT_TIMESTAMP(6)
WHERE task.review_recovery_task_status = 'PLANNED'
  AND batch.review_recovery_batch_status = 'OPEN'
  AND task.review_recovery_task_scheduled_date < CURRENT_DATE;
