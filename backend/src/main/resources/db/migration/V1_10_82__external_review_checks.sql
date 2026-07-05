SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND COLUMN_NAME = 'review_published_marked_at'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_published_marked_at DATETIME(6) NULL',
    'SELECT ''reviews.review_published_marked_at exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND COLUMN_NAME = 'review_external_confirm_status'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_external_confirm_status VARCHAR(32) NOT NULL DEFAULT ''PENDING''',
    'SELECT ''reviews.review_external_confirm_status exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND COLUMN_NAME = 'review_external_confirmed_at'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_external_confirmed_at DATETIME(6) NULL',
    'SELECT ''reviews.review_external_confirmed_at exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND COLUMN_NAME = 'review_external_confirm_screenshot_url'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_external_confirm_screenshot_url VARCHAR(1024) NULL',
    'SELECT ''reviews.review_external_confirm_screenshot_url exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE reviews
SET review_published_marked_at = COALESCE(CAST(review_changed AS DATETIME(6)), CURRENT_TIMESTAMP(6))
WHERE review_publish = 1
  AND review_published_marked_at IS NULL;

UPDATE reviews
SET review_external_confirm_status = 'PENDING'
WHERE review_external_confirm_status IS NULL
   OR TRIM(review_external_confirm_status) = '';

CREATE TABLE IF NOT EXISTS review_external_checks (
    review_external_check_id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    filial_id BIGINT NULL,
    platform VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    source VARCHAR(32) NOT NULL DEFAULT 'AUTO_SCREENSHOT',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    confidence DECIMAL(5, 4) NULL,
    check_after DATETIME(6) NULL,
    checked_at DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    filial_url VARCHAR(1000) NULL,
    screenshot_url VARCHAR(1024) NULL,
    screenshot_key VARCHAR(1024) NULL,
    matched_text_excerpt VARCHAR(1000) NULL,
    error_message VARCHAR(1000) NULL,
    worker_trace_id VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (review_external_check_id),
    INDEX idx_review_external_checks_review_status (review_id, status),
    INDEX idx_review_external_checks_status_after (status, check_after),
    INDEX idx_review_external_checks_review_created (review_id, created_at),
    CONSTRAINT fk_review_external_checks_review
        FOREIGN KEY (review_id) REFERENCES reviews (review_id)
);

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND INDEX_NAME = 'idx_reviews_external_confirm_candidates'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_external_confirm_candidates (review_publish, review_external_confirm_status, review_published_marked_at, review_id)',
    'SELECT ''idx_reviews_external_confirm_candidates exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
