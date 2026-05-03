-- Indexes for the Angular leads board.
-- The board filters by role/status and sorts by update_status or date_new_try.

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_status_update'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_status_update (lid_status, update_status, id)',
    'SELECT "idx_leads_status_update exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_manager_status_update'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_manager_status_update (manager_id, lid_status, update_status, id)',
    'SELECT "idx_leads_manager_status_update exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_marketolog_status_update'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_marketolog_status_update (marketolog_id, lid_status, update_status, id)',
    'SELECT "idx_leads_marketolog_status_update exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_update'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_update (update_status, id)',
    'SELECT "idx_leads_update exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_manager_update'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_manager_update (manager_id, update_status, id)',
    'SELECT "idx_leads_manager_update exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_status_retry_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_status_retry_date (lid_status, date_new_try, id)',
    'SELECT "idx_leads_status_retry_date exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_manager_status_retry_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_manager_status_retry_date (manager_id, lid_status, date_new_try, id)',
    'SELECT "idx_leads_manager_status_retry_date exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
