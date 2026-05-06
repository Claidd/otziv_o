SET @sql = IF(
    (SELECT COUNT(1)
     FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_publish_filial_details') = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_publish_filial_details (review_publish, review_filial, review_order_details, review_id)',
    'SELECT ''idx_reviews_publish_filial_details exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(1)
     FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_statuses' AND INDEX_NAME = 'idx_order_statuses_title_id') = 0,
    'ALTER TABLE order_statuses ADD INDEX idx_order_statuses_title_id (order_status_title, order_status_id)',
    'SELECT ''idx_order_statuses_title_id exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
