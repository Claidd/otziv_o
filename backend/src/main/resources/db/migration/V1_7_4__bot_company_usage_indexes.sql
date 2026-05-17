-- Supporting indexes for company-wide bot reuse checks.
-- The query is split by direct company predicates so these indexes stay usable.

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_filial_bot_details'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_filial_bot_details (review_filial, review_bot, review_order_details)',
    'SELECT ''idx_reviews_filial_bot_details exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_order_details_bot_filial'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_order_details_bot_filial (review_order_details, review_bot, review_filial)',
    'SELECT ''idx_reviews_order_details_bot_filial exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'archive_reviews' AND INDEX_NAME = 'idx_archive_reviews_filial_bot_details'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE archive_reviews ADD INDEX idx_archive_reviews_filial_bot_details (review_filial, review_bot, review_order_details)',
    'SELECT ''idx_archive_reviews_filial_bot_details exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'archive_reviews' AND INDEX_NAME = 'idx_archive_reviews_order_details_bot_filial'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE archive_reviews ADD INDEX idx_archive_reviews_order_details_bot_filial (review_order_details, review_bot, review_filial)',
    'SELECT ''idx_archive_reviews_order_details_bot_filial exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'archive_orders' AND INDEX_NAME = 'idx_archive_orders_company_restored_id'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE archive_orders ADD INDEX idx_archive_orders_company_restored_id (order_company, restored_at, order_id)',
    'SELECT ''idx_archive_orders_company_restored_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ANALYZE TABLE reviews, archive_reviews, archive_orders;
