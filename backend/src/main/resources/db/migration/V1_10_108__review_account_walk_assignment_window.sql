SET @column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND COLUMN_NAME = 'review_account_walk_delay_bot_id'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_account_walk_delay_bot_id BIGINT NULL AFTER review_account_walk_delay_days',
    'SELECT ''review_account_walk_delay_bot_id exists on reviews'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND COLUMN_NAME = 'review_account_walk_not_before'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_account_walk_not_before DATE NULL AFTER review_account_walk_delay_bot_id',
    'SELECT ''review_account_walk_not_before exists on reviews'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Legacy accumulated values are intentionally preserved for a safe rollout. New scheduling code
-- no longer uses them as an idempotency key; the next real account assignment establishes the
-- account-specific window without blindly adding the old value.
