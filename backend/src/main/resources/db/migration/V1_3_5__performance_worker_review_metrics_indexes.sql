-- Index for worker board review metric aggregation.
-- The query is scoped by worker and then checks publish/vigul/date/order-details predicates.

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_worker_metrics'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_worker_metrics (review_worker, review_publish, review_publish_date, review_vigul, review_order_details, review_bot)',
    'SELECT "idx_reviews_worker_metrics exists"');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
