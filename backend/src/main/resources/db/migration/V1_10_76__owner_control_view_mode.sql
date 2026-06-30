SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'users'
      AND COLUMN_NAME = 'owner_control_view_mode'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE users ADD COLUMN owner_control_view_mode VARCHAR(32) NOT NULL DEFAULT ''OWN_MANAGERS''',
    'SELECT ''owner_control_view_mode exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE users
SET owner_control_view_mode = 'OWN_MANAGERS'
WHERE owner_control_view_mode IS NULL
   OR owner_control_view_mode NOT IN ('OWN_MANAGERS', 'ALL_MANAGERS');
