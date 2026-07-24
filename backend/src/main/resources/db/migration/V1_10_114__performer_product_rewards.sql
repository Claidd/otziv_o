SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'products'
      AND COLUMN_NAME = 'product_performer_reward_percent'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE products ADD COLUMN product_performer_reward_percent DECIMAL(5, 2) NOT NULL DEFAULT 0',
    'SELECT ''products.product_performer_reward_percent exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'products'
      AND COLUMN_NAME = 'product_specialist_reward_percent'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE products ADD COLUMN product_specialist_reward_percent DECIMAL(5, 2) NOT NULL DEFAULT 0',
    'SELECT ''products.product_specialist_reward_percent exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'products'
      AND COLUMN_NAME = 'product_manager_reward_percent'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE products ADD COLUMN product_manager_reward_percent DECIMAL(5, 2) NOT NULL DEFAULT 0',
    'SELECT ''products.product_manager_reward_percent exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
