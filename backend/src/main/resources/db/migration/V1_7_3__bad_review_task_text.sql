SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bad_review_tasks'
      AND COLUMN_NAME = 'bad_review_task_text'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE bad_review_tasks ADD COLUMN bad_review_task_text LONGTEXT NULL',
    'SELECT ''bad_review_task_text exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE bad_review_tasks t
LEFT JOIN reviews r ON r.review_id = t.bad_review_task_review
SET t.bad_review_task_text = COALESCE(NULLIF(t.bad_review_task_text, ''), r.review_text);

SET @archive_table_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_bad_review_tasks'
);

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_bad_review_tasks'
      AND COLUMN_NAME = 'bad_review_task_text'
);
SET @sql = IF(
    @archive_table_exists > 0 AND @column_exists = 0,
    'ALTER TABLE archive_bad_review_tasks ADD COLUMN bad_review_task_text LONGTEXT NULL',
    'SELECT ''archive bad_review_task_text exists or table missing'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    @archive_table_exists > 0,
    'UPDATE archive_bad_review_tasks t
     LEFT JOIN reviews r ON r.review_id = t.bad_review_task_review
     SET t.bad_review_task_text = COALESCE(NULLIF(t.bad_review_task_text, ''''), r.review_text)',
    'SELECT ''archive_bad_review_tasks missing'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
