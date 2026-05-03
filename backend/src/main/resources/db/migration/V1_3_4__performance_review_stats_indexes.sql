-- Indexes for cabinet score/analyse unpublished review statistics.
-- Kept idempotent because local databases may already have similar manual indexes.

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_publish_date_worker_bot'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_publish_date_worker_bot (review_publish, review_publish_date, review_worker, review_bot)',
    'SELECT "idx_reviews_publish_date_worker_bot exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_publish_date_details_bot'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_publish_date_details_bot (review_publish, review_publish_date, review_order_details, review_bot)',
    'SELECT "idx_reviews_publish_date_details_bot exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
