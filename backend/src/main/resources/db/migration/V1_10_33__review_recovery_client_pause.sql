ALTER TABLE review_recovery_batches
    ADD COLUMN review_recovery_batch_hold_started_at DATETIME(6) NULL,
    ADD COLUMN review_recovery_batch_hold_released_at DATETIME(6) NULL,
    ADD COLUMN review_recovery_batch_deadline_shift_applied_at DATETIME(6) NULL,
    ADD COLUMN review_recovery_batch_deadline_shift_seconds BIGINT NOT NULL DEFAULT 0;

UPDATE review_recovery_batches
SET review_recovery_batch_hold_started_at = review_recovery_batch_created_at
WHERE review_recovery_batch_hold_started_at IS NULL
  AND review_recovery_batch_status IN ('OPEN', 'COMPLETED');

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_batches'
      AND INDEX_NAME = 'idx_review_recovery_batches_hold'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE review_recovery_batches ADD INDEX idx_review_recovery_batches_hold (review_recovery_batch_order, review_recovery_batch_status, review_recovery_batch_hold_released_at)',
    'SELECT ''idx_review_recovery_batches_hold exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('client.messages.review-recovery-notice.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('client.messages.review-recovery-notice.retry-delay-hours', '2', CURRENT_TIMESTAMP(6)),
    ('client.messages.review-recovery-notice-text', '{companyAndFilial}\n\nВсе отзывы по заказу №{orderId} восстановлены. Продолжаем работу.', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;
