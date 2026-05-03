-- Indexes for cabinet profile payment and salary year-range statistics.
-- The related queries use date ranges, so plain date indexes are useful.

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_check' AND INDEX_NAME = 'idx_payment_check_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE payment_check ADD INDEX idx_payment_check_date (check_date)',
    'SELECT "idx_payment_check_date exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_check' AND INDEX_NAME = 'idx_payment_check_manager_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE payment_check ADD INDEX idx_payment_check_manager_date (check_manager, check_date)',
    'SELECT "idx_payment_check_manager_date exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'zp' AND INDEX_NAME = 'idx_zp_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE zp ADD INDEX idx_zp_date (zp_date)',
    'SELECT "idx_zp_date exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'zp' AND INDEX_NAME = 'idx_zp_user_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE zp ADD INDEX idx_zp_user_date (zp_user, zp_date)',
    'SELECT "idx_zp_user_date exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
