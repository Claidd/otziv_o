-- Indexes for lead sending queues, bad-review task queues, and unpublished review health metrics.

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leads' AND INDEX_NAME = 'idx_leads_telephone_status_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE leads ADD INDEX idx_leads_telephone_status_date (telephone_id, lid_status, create_date, id)',
    'SELECT ''idx_leads_telephone_status_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bad_review_tasks' AND INDEX_NAME = 'idx_bad_review_tasks_order_review_status'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE bad_review_tasks ADD INDEX idx_bad_review_tasks_order_review_status (bad_review_task_order, bad_review_task_review, bad_review_task_status)',
    'SELECT ''idx_bad_review_tasks_order_review_status exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bad_review_tasks' AND INDEX_NAME = 'idx_bad_review_tasks_order_status_date'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE bad_review_tasks ADD INDEX idx_bad_review_tasks_order_status_date (bad_review_task_order, bad_review_task_status, bad_review_task_scheduled_date)',
    'SELECT ''idx_bad_review_tasks_order_status_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_publish_order_details'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_publish_order_details (review_publish, review_order_details, review_id)',
    'SELECT ''idx_reviews_publish_order_details exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
