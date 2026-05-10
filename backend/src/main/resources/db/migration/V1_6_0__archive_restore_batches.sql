CREATE TABLE IF NOT EXISTS archive_restore_batches (
    restore_batch_id BIGINT NOT NULL AUTO_INCREMENT,
    archive_order_id BIGINT NOT NULL,
    restored_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    restored_by VARCHAR(255) NULL,
    target_status VARCHAR(100) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    orders_restored BIGINT NOT NULL DEFAULT 0,
    order_details_restored BIGINT NOT NULL DEFAULT 0,
    reviews_restored BIGINT NOT NULL DEFAULT 0,
    bad_review_tasks_restored BIGINT NOT NULL DEFAULT 0,
    next_order_requests_restored BIGINT NOT NULL DEFAULT 0,
    zp_restored BIGINT NOT NULL DEFAULT 0,
    payment_check_restored BIGINT NOT NULL DEFAULT 0,
    message VARCHAR(1000) NULL,
    PRIMARY KEY (restore_batch_id),
    INDEX idx_archive_restore_batches_order (archive_order_id),
    INDEX idx_archive_restore_batches_restored_at (restored_at),
    INDEX idx_archive_restore_batches_status (status)
);

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_orders'
      AND COLUMN_NAME = 'restored_at'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE archive_orders ADD COLUMN restored_at DATETIME(6) NULL',
    'SELECT ''restored_at exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_orders'
      AND COLUMN_NAME = 'restored_by'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE archive_orders ADD COLUMN restored_by VARCHAR(255) NULL',
    'SELECT ''restored_by exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_orders'
      AND COLUMN_NAME = 'restore_batch_id'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE archive_orders ADD COLUMN restore_batch_id BIGINT NULL',
    'SELECT ''restore_batch_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_orders'
      AND INDEX_NAME = 'idx_archive_orders_restored'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE archive_orders ADD INDEX idx_archive_orders_restored (restored_at, restore_batch_id)',
    'SELECT ''idx_archive_orders_restored exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
