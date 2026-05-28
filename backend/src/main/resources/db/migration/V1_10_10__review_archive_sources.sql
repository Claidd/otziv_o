SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews_archive'
      AND COLUMN_NAME = 'review_archive_source_review_id'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE reviews_archive ADD COLUMN review_archive_source_review_id BIGINT NULL',
    'SELECT ''review_archive_source_review_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews_archive'
      AND COLUMN_NAME = 'review_archive_source_order_id'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE reviews_archive ADD COLUMN review_archive_source_order_id BIGINT NULL',
    'SELECT ''review_archive_source_order_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews_archive'
      AND COLUMN_NAME = 'review_archive_source_reason'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE reviews_archive ADD COLUMN review_archive_source_reason VARCHAR(32) NULL',
    'SELECT ''review_archive_source_reason exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews_archive'
      AND INDEX_NAME = 'idx_reviews_archive_source_review'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews_archive ADD INDEX idx_reviews_archive_source_review (review_archive_source_review_id)',
    'SELECT ''idx_reviews_archive_source_review exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews_archive'
      AND INDEX_NAME = 'idx_reviews_archive_source_order'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews_archive ADD INDEX idx_reviews_archive_source_order (review_archive_source_order_id)',
    'SELECT ''idx_reviews_archive_source_order exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

DROP TEMPORARY TABLE IF EXISTS tmp_reviews_archive_sources;
CREATE TEMPORARY TABLE tmp_reviews_archive_sources AS
SELECT review_archive_id,
       review_id,
       order_id,
       CASE WHEN review_publish = 1 THEN 'PUBLISHED' ELSE 'BACKFILL' END AS source_reason
FROM (
    SELECT archive.review_archive_id,
           review.review_id,
           orders.order_id,
           review.review_publish,
           ROW_NUMBER() OVER (
               PARTITION BY archive.review_archive_id
               ORDER BY
                   review.review_publish DESC,
                   CASE WHEN orders.order_id IS NULL THEN 1 ELSE 0 END,
                   review.review_id
           ) AS source_rank
    FROM reviews_archive archive
    JOIN reviews review
      ON review.review_text_hash = archive.review_archive_text_hash
     AND review.review_text = archive.review_archive_text
    LEFT JOIN order_details details
      ON details.order_detail_id = review.review_order_details
    LEFT JOIN orders
      ON orders.order_id = details.order_detail_order
) ranked_sources
WHERE source_rank = 1;

ALTER TABLE tmp_reviews_archive_sources ADD INDEX idx_tmp_reviews_archive_sources_id (review_archive_id);

UPDATE reviews_archive archive
JOIN tmp_reviews_archive_sources source
  ON source.review_archive_id = archive.review_archive_id
SET archive.review_archive_source_review_id = COALESCE(archive.review_archive_source_review_id, source.review_id),
    archive.review_archive_source_order_id = COALESCE(archive.review_archive_source_order_id, source.order_id),
    archive.review_archive_source_reason = CASE
        WHEN archive.review_archive_source_reason IS NULL OR CHAR_LENGTH(TRIM(archive.review_archive_source_reason)) = 0
            THEN source.source_reason
        ELSE archive.review_archive_source_reason
    END;

DROP TEMPORARY TABLE IF EXISTS tmp_reviews_archive_sources;
