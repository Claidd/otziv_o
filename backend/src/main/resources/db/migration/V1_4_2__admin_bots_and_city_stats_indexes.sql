-- Indexes for admin bot dictionaries and city review statistics.

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bots' AND INDEX_NAME = 'idx_bots_city_active'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE bots ADD INDEX idx_bots_city_active (bot_city_id, bot_active, bot_id)',
    'SELECT ''idx_bots_city_active exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_filial_publish_details'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_filial_publish_details (review_filial, review_publish, review_order_details, review_id)',
    'SELECT ''idx_reviews_filial_publish_details exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
