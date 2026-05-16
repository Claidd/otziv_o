SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bad_review_tasks'
      AND COLUMN_NAME = 'bad_review_task_bot_login_snapshot'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE bad_review_tasks ADD COLUMN bad_review_task_bot_login_snapshot VARCHAR(255) NULL',
    'SELECT ''bad_review_task_bot_login_snapshot exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bad_review_tasks'
      AND COLUMN_NAME = 'bad_review_task_bot_password_snapshot'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE bad_review_tasks ADD COLUMN bad_review_task_bot_password_snapshot VARCHAR(255) NULL',
    'SELECT ''bad_review_task_bot_password_snapshot exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bad_review_tasks'
      AND COLUMN_NAME = 'bad_review_task_bot_fio_snapshot'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE bad_review_tasks ADD COLUMN bad_review_task_bot_fio_snapshot VARCHAR(255) NULL',
    'SELECT ''bad_review_task_bot_fio_snapshot exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE bad_review_tasks t
LEFT JOIN bots b ON b.bot_id = t.bad_review_task_bot
SET
    t.bad_review_task_bot_login_snapshot = COALESCE(NULLIF(t.bad_review_task_bot_login_snapshot, ''), b.bot_login),
    t.bad_review_task_bot_password_snapshot = COALESCE(NULLIF(t.bad_review_task_bot_password_snapshot, ''), b.bot_password),
    t.bad_review_task_bot_fio_snapshot = COALESCE(NULLIF(t.bad_review_task_bot_fio_snapshot, ''), b.bot_fio);

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
      AND COLUMN_NAME = 'bad_review_task_bot_login_snapshot'
);
SET @sql = IF(
    @archive_table_exists > 0 AND @column_exists = 0,
    'ALTER TABLE archive_bad_review_tasks ADD COLUMN bad_review_task_bot_login_snapshot VARCHAR(255) NULL',
    'SELECT ''archive bad_review_task_bot_login_snapshot exists or table missing'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_bad_review_tasks'
      AND COLUMN_NAME = 'bad_review_task_bot_password_snapshot'
);
SET @sql = IF(
    @archive_table_exists > 0 AND @column_exists = 0,
    'ALTER TABLE archive_bad_review_tasks ADD COLUMN bad_review_task_bot_password_snapshot VARCHAR(255) NULL',
    'SELECT ''archive bad_review_task_bot_password_snapshot exists or table missing'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_bad_review_tasks'
      AND COLUMN_NAME = 'bad_review_task_bot_fio_snapshot'
);
SET @sql = IF(
    @archive_table_exists > 0 AND @column_exists = 0,
    'ALTER TABLE archive_bad_review_tasks ADD COLUMN bad_review_task_bot_fio_snapshot VARCHAR(255) NULL',
    'SELECT ''archive bad_review_task_bot_fio_snapshot exists or table missing'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    @archive_table_exists > 0,
    'UPDATE archive_bad_review_tasks t
     LEFT JOIN bots b ON b.bot_id = t.bad_review_task_bot
     SET
         t.bad_review_task_bot_login_snapshot = COALESCE(NULLIF(t.bad_review_task_bot_login_snapshot, ''''), b.bot_login),
         t.bad_review_task_bot_password_snapshot = COALESCE(NULLIF(t.bad_review_task_bot_password_snapshot, ''''), b.bot_password),
         t.bad_review_task_bot_fio_snapshot = COALESCE(NULLIF(t.bad_review_task_bot_fio_snapshot, ''''), b.bot_fio)',
    'SELECT ''archive_bad_review_tasks missing'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
