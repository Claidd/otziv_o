INSERT IGNORE INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('zp.product-reward-percent.enabled', 'false', CURRENT_TIMESTAMP);

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'zp'
      AND COLUMN_NAME = 'zp_source'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE zp ADD COLUMN zp_source VARCHAR(64) NULL',
    'SELECT ''zp.zp_source exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'zp'
      AND INDEX_NAME = 'idx_zp_order_source_active'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE zp ADD INDEX idx_zp_order_source_active (zp_order, zp_source, zp_active)',
    'SELECT ''idx_zp_order_source_active exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
