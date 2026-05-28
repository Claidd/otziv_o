CREATE TABLE IF NOT EXISTS archive_payment_links LIKE payment_links;

SET @archive_table = 'archive_payment_links';

SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archived_at');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_payment_links ADD COLUMN archived_at DATETIME(6) NULL', 'SELECT ''archived_at exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_reason');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_payment_links ADD COLUMN archive_reason VARCHAR(100) NULL', 'SELECT ''archive_reason exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'archive_batch_id');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_payment_links ADD COLUMN archive_batch_id BIGINT NULL', 'SELECT ''archive_batch_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'company_title_snapshot');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_payment_links ADD COLUMN company_title_snapshot VARCHAR(255) NULL', 'SELECT ''company_title_snapshot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'filial_title_snapshot');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_payment_links ADD COLUMN filial_title_snapshot VARCHAR(255) NULL', 'SELECT ''filial_title_snapshot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND COLUMN_NAME = 'manager_name_snapshot');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE archive_payment_links ADD COLUMN manager_name_snapshot VARCHAR(255) NULL', 'SELECT ''manager_name_snapshot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND INDEX_NAME = 'idx_archive_payment_links_archived');
SET @sql = IF(@index_exists = 0, 'ALTER TABLE archive_payment_links ADD INDEX idx_archive_payment_links_archived (archived_at, id)', 'SELECT ''idx_archive_payment_links_archived exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @archive_table AND INDEX_NAME = 'idx_archive_payment_links_status_created');
SET @sql = IF(@index_exists = 0, 'ALTER TABLE archive_payment_links ADD INDEX idx_archive_payment_links_status_created (status, created_at)', 'SELECT ''idx_archive_payment_links_status_created exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('payment.links.archive.enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('payment.links.archive.paid-retention-days', '90', CURRENT_TIMESTAMP(6)),
    ('payment.links.archive.final-retention-days', '60', CURRENT_TIMESTAMP(6)),
    ('payment.links.archive.batch-size', '500', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
