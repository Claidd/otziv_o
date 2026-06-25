SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND INDEX_NAME = 'idx_reviews_publish_bot'
);

SET @sql = IF(
    @index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_publish_bot (review_publish, review_bot, review_id)',
    'SELECT ''idx_reviews_publish_bot exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND INDEX_NAME = 'idx_reviews_bot_publish'
);

SET @sql = IF(
    @index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_bot_publish (review_bot, review_publish, review_id)',
    'SELECT ''idx_reviews_bot_publish exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ANALYZE TABLE reviews;
