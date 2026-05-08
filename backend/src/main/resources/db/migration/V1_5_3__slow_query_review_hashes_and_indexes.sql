-- Indexes and generated hashes for the slow queries observed in Hibernate SQL_SLOW logs.

SET @column_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND COLUMN_NAME = 'review_text_hash'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_text_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(COALESCE(review_text, ''''), 256))) STORED',
    'SELECT ''review_text_hash exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_text_hash_id'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_text_hash_id (review_text_hash, review_id)',
    'SELECT ''idx_reviews_text_hash_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews_archive' AND COLUMN_NAME = 'review_archive_text_hash'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE reviews_archive ADD COLUMN review_archive_text_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(COALESCE(review_archive_text, ''''), 256))) STORED',
    'SELECT ''review_archive_text_hash exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews_archive' AND INDEX_NAME = 'idx_reviews_archive_text_hash_id'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews_archive ADD INDEX idx_reviews_archive_text_hash_id (review_archive_text_hash, review_archive_id)',
    'SELECT ''idx_reviews_archive_text_hash_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_publish_filial_bot'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_publish_filial_bot (review_publish, review_filial, review_bot, review_id)',
    'SELECT ''idx_reviews_publish_filial_bot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_manager_status_waiting_changed'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_manager_status_waiting_changed (order_manager, order_status, order_waiting_for_client, order_changed, order_id)',
    'SELECT ''idx_orders_manager_status_waiting_changed exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_manager_waiting_status'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_manager_waiting_status (order_manager, order_waiting_for_client, order_status, order_id)',
    'SELECT ''idx_orders_manager_waiting_status exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_worker_waiting_changed'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_worker_waiting_changed (order_worker, order_waiting_for_client, order_changed, order_id)',
    'SELECT ''idx_orders_worker_waiting_changed exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_worker_status_waiting_changed'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_worker_status_waiting_changed (order_worker, order_status, order_waiting_for_client, order_changed, order_id)',
    'SELECT ''idx_orders_worker_status_waiting_changed exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'zp' AND INDEX_NAME = 'idx_zp_date_user_totals'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE zp ADD INDEX idx_zp_date_user_totals (zp_date, zp_user, zp_id, zp_sum, zp_amount)',
    'SELECT ''idx_zp_date_user_totals exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ANALYZE TABLE reviews, reviews_archive, orders, zp;
