-- First live-slice/archive preparation.
-- This migration is intentionally idempotent at index/column level because prod
-- may already have some indexes created by earlier performance migrations.

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_worker_waiting_status_changed'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_worker_waiting_status_changed (order_worker, order_waiting_for_client, order_status, order_changed, order_id)',
    'SELECT ''idx_orders_worker_waiting_status_changed exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_status_worker_waiting_changed'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_status_worker_waiting_changed (order_status, order_worker, order_waiting_for_client, order_changed, order_id)',
    'SELECT ''idx_orders_status_worker_waiting_changed exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_manager_complete_changed_status'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_manager_complete_changed_status (order_manager, order_complete, order_changed, order_status, order_id)',
    'SELECT ''idx_orders_manager_complete_changed_status exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_worker_complete_changed_status'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_worker_complete_changed_status (order_worker, order_complete, order_changed, order_status, order_id)',
    'SELECT ''idx_orders_worker_complete_changed_status exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_status_payday_changed'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_status_payday_changed (order_status, order_pay_day, order_changed, order_id)',
    'SELECT ''idx_orders_status_payday_changed exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_worker_publish_vigul_date_bot'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_worker_publish_vigul_date_bot (review_worker, review_publish, review_vigul, review_publish_date, review_bot, review_id)',
    'SELECT ''idx_reviews_worker_publish_vigul_date_bot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_publish_vigul_date_bot_worker'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_publish_vigul_date_bot_worker (review_publish, review_vigul, review_publish_date, review_bot, review_worker, review_id)',
    'SELECT ''idx_reviews_publish_vigul_date_bot_worker exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_worker_order_details'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_worker_order_details (review_worker, review_order_details, review_id)',
    'SELECT ''idx_reviews_worker_order_details exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_order_details_worker'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_order_details_worker (review_order_details, review_worker, review_id)',
    'SELECT ''idx_reviews_order_details_worker exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_filial_publish_bot'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_filial_publish_bot (review_filial, review_publish, review_bot, review_id)',
    'SELECT ''idx_reviews_filial_publish_bot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bots' AND INDEX_NAME = 'idx_bots_active_status'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE bots ADD INDEX idx_bots_active_status (bot_active, bot_status, bot_id)',
    'SELECT ''idx_bots_active_status exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bots' AND INDEX_NAME = 'idx_bots_active_id'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE bots ADD INDEX idx_bots_active_id (bot_active, bot_id)',
    'SELECT ''idx_bots_active_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'companies' AND INDEX_NAME = 'idx_companies_update_status'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE companies ADD INDEX idx_companies_update_status (update_status, company_status, company_id)',
    'SELECT ''idx_companies_update_status exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'companies' AND INDEX_NAME = 'idx_companies_manager_update_status'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE companies ADD INDEX idx_companies_manager_update_status (company_manager, update_status, company_status, company_id)',
    'SELECT ''idx_companies_manager_update_status exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS archive_batches (
    archive_batch_id BIGINT NOT NULL AUTO_INCREMENT,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    dry_run BIT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'STARTED',
    archive_reason VARCHAR(100) NULL,
    retention_days INT NOT NULL DEFAULT 60,
    orders_selected BIGINT NOT NULL DEFAULT 0,
    orders_archived BIGINT NOT NULL DEFAULT 0,
    order_details_archived BIGINT NOT NULL DEFAULT 0,
    reviews_archived BIGINT NOT NULL DEFAULT 0,
    bad_review_tasks_archived BIGINT NOT NULL DEFAULT 0,
    next_order_requests_archived BIGINT NOT NULL DEFAULT 0,
    zp_archived BIGINT NOT NULL DEFAULT 0,
    payment_check_archived BIGINT NOT NULL DEFAULT 0,
    message VARCHAR(1000) NULL,
    PRIMARY KEY (archive_batch_id),
    INDEX idx_archive_batches_started_at (started_at),
    INDEX idx_archive_batches_status (status)
);

CREATE TABLE IF NOT EXISTS archive_orders LIKE orders;
CREATE TABLE IF NOT EXISTS archive_order_details LIKE order_details;
CREATE TABLE IF NOT EXISTS archive_reviews LIKE reviews;
CREATE TABLE IF NOT EXISTS archive_bad_review_tasks LIKE bad_review_tasks;
CREATE TABLE IF NOT EXISTS archive_next_order_requests LIKE next_order_requests;
CREATE TABLE IF NOT EXISTS archive_zp LIKE zp;
CREATE TABLE IF NOT EXISTS archive_payment_check LIKE payment_check;

SET @sql = 'ALTER TABLE archive_orders MODIFY order_id BIGINT NOT NULL';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = 'ALTER TABLE archive_order_details MODIFY order_detail_id BINARY(16) NOT NULL';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = 'ALTER TABLE archive_reviews MODIFY review_id BIGINT NOT NULL';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = 'ALTER TABLE archive_zp MODIFY zp_id BIGINT NOT NULL';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = 'ALTER TABLE archive_payment_check MODIFY check_id BIGINT NOT NULL';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @archive_table = 'archive_orders';
SET @id_column = 'order_id';
SET @prefix = 'order';

SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archived_at');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archived_at DATETIME(6) NULL'), 'SELECT ''archived_at exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_reason');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_reason VARCHAR(100) NULL'), 'SELECT ''archive_reason exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_batch_id');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_batch_id BIGINT NULL'), 'SELECT ''archive_batch_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'company_title_snapshot');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_orders ADD COLUMN company_title_snapshot VARCHAR(255) NULL', 'SELECT ''company_title_snapshot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'company_phone_snapshot');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_orders ADD COLUMN company_phone_snapshot VARCHAR(32) NULL', 'SELECT ''company_phone_snapshot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'company_city_snapshot');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_orders ADD COLUMN company_city_snapshot VARCHAR(100) NULL', 'SELECT ''company_city_snapshot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'filial_title_snapshot');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_orders ADD COLUMN filial_title_snapshot VARCHAR(255) NULL', 'SELECT ''filial_title_snapshot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'manager_name_snapshot');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_orders ADD COLUMN manager_name_snapshot VARCHAR(255) NULL', 'SELECT ''manager_name_snapshot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'worker_name_snapshot');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_orders ADD COLUMN worker_name_snapshot VARCHAR(255) NULL', 'SELECT ''worker_name_snapshot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @archive_table = 'archive_order_details';
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archived_at');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archived_at DATETIME(6) NULL'), 'SELECT ''archived_at exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_reason');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_reason VARCHAR(100) NULL'), 'SELECT ''archive_reason exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_batch_id');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_batch_id BIGINT NULL'), 'SELECT ''archive_batch_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @archive_table = 'archive_reviews';
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archived_at');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archived_at DATETIME(6) NULL'), 'SELECT ''archived_at exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_reason');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_reason VARCHAR(100) NULL'), 'SELECT ''archive_reason exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_batch_id');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_batch_id BIGINT NULL'), 'SELECT ''archive_batch_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @archive_table = 'archive_bad_review_tasks';
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archived_at');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archived_at DATETIME(6) NULL'), 'SELECT ''archived_at exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_reason');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_reason VARCHAR(100) NULL'), 'SELECT ''archive_reason exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_batch_id');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_batch_id BIGINT NULL'), 'SELECT ''archive_batch_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @archive_table = 'archive_next_order_requests';
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archived_at');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archived_at DATETIME(6) NULL'), 'SELECT ''archived_at exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_reason');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_reason VARCHAR(100) NULL'), 'SELECT ''archive_reason exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_batch_id');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_batch_id BIGINT NULL'), 'SELECT ''archive_batch_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @archive_table = 'archive_zp';
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archived_at');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archived_at DATETIME(6) NULL'), 'SELECT ''archived_at exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_reason');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_reason VARCHAR(100) NULL'), 'SELECT ''archive_reason exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_batch_id');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_batch_id BIGINT NULL'), 'SELECT ''archive_batch_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @archive_table = 'archive_payment_check';
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archived_at');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archived_at DATETIME(6) NULL'), 'SELECT ''archived_at exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_reason');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_reason VARCHAR(100) NULL'), 'SELECT ''archive_reason exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_batch_id');
SET @sql = IF(@column_exists = 0, CONCAT('ALTER TABLE ', @archive_table, ' ADD COLUMN archive_batch_id BIGINT NULL'), 'SELECT ''archive_batch_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'archive_orders' AND INDEX_NAME = 'idx_archive_orders_batch');
SET @sql = IF(@index_exists = 0, 'ALTER TABLE archive_orders ADD INDEX idx_archive_orders_batch (archive_batch_id, archived_at)', 'SELECT ''idx_archive_orders_batch exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'archive_reviews' AND INDEX_NAME = 'idx_archive_reviews_order_details');
SET @sql = IF(@index_exists = 0, 'ALTER TABLE archive_reviews ADD INDEX idx_archive_reviews_order_details (review_order_details, review_id)', 'SELECT ''idx_archive_reviews_order_details exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'zp' AND INDEX_NAME = 'idx_zp_order_date');
SET @sql = IF(@index_exists = 0, 'ALTER TABLE zp ADD INDEX idx_zp_order_date (zp_order, zp_date, zp_id)', 'SELECT ''idx_zp_order_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_check' AND INDEX_NAME = 'idx_payment_check_order_date');
SET @sql = IF(@index_exists = 0, 'ALTER TABLE payment_check ADD INDEX idx_payment_check_order_date (check_order, check_date, check_id)', 'SELECT ''idx_payment_check_order_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ANALYZE TABLE orders, reviews, bots, companies, zp, payment_check;
