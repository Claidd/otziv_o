-- Indexes for month-range lead analytics.
-- Queries use create_date >= :dateFrom AND create_date < :dateTo.

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_create_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_create_date (create_date, id)',
    'SELECT ''idx_leads_create_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_status_create_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_status_create_date (lid_status, create_date, id)',
    'SELECT ''idx_leads_status_create_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_manager_create_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_manager_create_date (manager_id, create_date, id)',
    'SELECT ''idx_leads_manager_create_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_manager_status_create_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_manager_status_create_date (manager_id, lid_status, create_date, id)',
    'SELECT ''idx_leads_manager_status_create_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_marketolog_create_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_marketolog_create_date (marketolog_id, create_date, id)',
    'SELECT ''idx_leads_marketolog_create_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_marketolog_status_create_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_marketolog_status_create_date (marketolog_id, lid_status, create_date, id)',
    'SELECT ''idx_leads_marketolog_status_create_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_operator_create_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_operator_create_date (operator_id, create_date, id)',
    'SELECT ''idx_leads_operator_create_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_operator_status_create_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_operator_status_create_date (operator_id, lid_status, create_date, id)',
    'SELECT ''idx_leads_operator_status_create_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
