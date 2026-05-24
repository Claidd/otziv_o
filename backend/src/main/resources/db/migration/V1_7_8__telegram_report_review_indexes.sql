-- Covering indexes for the monthly Telegram/personal report review aggregate.
-- They keep the unpublished month slice in index pages while the query counts
-- publication and vigul buckets by worker and manager ownership paths.

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_report_worker_publish_date_vigul'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_report_worker_publish_date_vigul (review_publish, review_publish_date, review_worker, review_bot, review_vigul)',
    'SELECT ''idx_reviews_report_worker_publish_date_vigul exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_report_details_publish_date_vigul'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_report_details_publish_date_vigul (review_publish, review_publish_date, review_order_details, review_bot, review_vigul)',
    'SELECT ''idx_reviews_report_details_publish_date_vigul exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ANALYZE TABLE reviews, bots, workers, managers, users, order_details, orders;
