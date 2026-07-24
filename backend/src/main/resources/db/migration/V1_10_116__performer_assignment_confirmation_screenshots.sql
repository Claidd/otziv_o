SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_performer_assignments'
      AND COLUMN_NAME = 'performer_publication_screenshot_url'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_performer_assignments ADD COLUMN performer_publication_screenshot_url VARCHAR(1000) NULL',
    'SELECT ''review_performer_assignments.performer_publication_screenshot_url exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_performer_assignments'
      AND COLUMN_NAME = 'manager_confirmation_screenshot_url'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_performer_assignments ADD COLUMN manager_confirmation_screenshot_url VARCHAR(1000) NULL',
    'SELECT ''review_performer_assignments.manager_confirmation_screenshot_url exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
