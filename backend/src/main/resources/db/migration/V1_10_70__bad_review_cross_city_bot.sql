SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bad_review_tasks'
      AND COLUMN_NAME = 'bad_review_task_cross_city_bot'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE bad_review_tasks ADD COLUMN bad_review_task_cross_city_bot BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT ''bad_review_task_cross_city_bot exists on bad_review_tasks'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @archive_table_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_bad_review_tasks'
);
SET @archive_column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_bad_review_tasks'
      AND COLUMN_NAME = 'bad_review_task_cross_city_bot'
);
SET @sql = IF(
    @archive_table_exists > 0 AND @archive_column_exists = 0,
    'ALTER TABLE archive_bad_review_tasks ADD COLUMN bad_review_task_cross_city_bot BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT ''bad_review_task_cross_city_bot exists on archive_bad_review_tasks or table missing'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
