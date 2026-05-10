-- Keep reviews_archive as a unique archive of real review texts:
-- 1. remove empty/template archive rows left from old behavior;
-- 2. collapse duplicate archive rows, preserving the row with the richest metadata;
-- 3. fill missing archive metadata from reviews where possible;
-- 4. backfill missing unique review texts from reviews;
-- 5. enforce uniqueness by generated SHA-256 text hash.

DELETE FROM reviews_archive
WHERE review_archive_text IS NULL
   OR CHAR_LENGTH(TRIM(review_archive_text)) = 0
   OR LOWER(TRIM(review_archive_text)) = 'текст отзыва';

CREATE TEMPORARY TABLE tmp_reviews_archive_duplicate_ids AS
SELECT review_archive_id
FROM (
    SELECT review_archive_id,
           ROW_NUMBER() OVER (
               PARTITION BY review_archive_text_hash, review_archive_text
               ORDER BY
                   CASE WHEN review_archive_category IS NULL THEN 1 ELSE 0 END,
                   CASE WHEN review_archive_subcategory IS NULL THEN 1 ELSE 0 END,
                   CASE WHEN review_archive_answer IS NULL OR CHAR_LENGTH(TRIM(review_archive_answer)) = 0 THEN 1 ELSE 0 END,
                   review_archive_id
           ) AS text_rank
    FROM reviews_archive
) ranked_archive
WHERE text_rank > 1;
ALTER TABLE tmp_reviews_archive_duplicate_ids ADD INDEX idx_tmp_reviews_archive_duplicate_id (review_archive_id);

DELETE archive
FROM reviews_archive archive
JOIN tmp_reviews_archive_duplicate_ids duplicate_archive
  ON duplicate_archive.review_archive_id = archive.review_archive_id;

CREATE TEMPORARY TABLE tmp_reviews_archive_source AS
SELECT review_text_hash,
       review_text,
       review_category,
       review_subcategory,
       review_answer
FROM (
    SELECT r.review_text_hash,
           r.review_text,
           r.review_category,
           r.review_subcategory,
           r.review_answer,
           ROW_NUMBER() OVER (
               PARTITION BY r.review_text_hash, r.review_text
               ORDER BY
                   CASE WHEN r.review_category IS NULL THEN 1 ELSE 0 END,
                   CASE WHEN r.review_subcategory IS NULL THEN 1 ELSE 0 END,
                   CASE WHEN r.review_answer IS NULL OR CHAR_LENGTH(TRIM(r.review_answer)) = 0 THEN 1 ELSE 0 END,
                   r.review_publish DESC,
                   r.review_id
           ) AS text_rank
    FROM reviews r
    WHERE r.review_text IS NOT NULL
      AND CHAR_LENGTH(TRIM(r.review_text)) > 0
      AND LOWER(TRIM(r.review_text)) <> 'текст отзыва'
) ranked_reviews
WHERE text_rank = 1;
ALTER TABLE tmp_reviews_archive_source ADD INDEX idx_tmp_reviews_archive_source_hash (review_text_hash);

UPDATE reviews_archive archive
JOIN tmp_reviews_archive_source source
  ON source.review_text_hash = archive.review_archive_text_hash
 AND source.review_text = archive.review_archive_text
SET archive.review_archive_category = COALESCE(archive.review_archive_category, source.review_category),
    archive.review_archive_subcategory = COALESCE(archive.review_archive_subcategory, source.review_subcategory),
    archive.review_archive_answer = CASE
        WHEN archive.review_archive_answer IS NULL OR CHAR_LENGTH(TRIM(archive.review_archive_answer)) = 0
            THEN source.review_answer
        ELSE archive.review_archive_answer
    END;

INSERT INTO reviews_archive (
    review_archive_text,
    review_archive_category,
    review_archive_subcategory,
    review_archive_answer
)
SELECT source.review_text,
       source.review_category,
       source.review_subcategory,
       source.review_answer
FROM tmp_reviews_archive_source source
WHERE NOT EXISTS (
      SELECT 1
      FROM reviews_archive archive
      WHERE archive.review_archive_text_hash = source.review_text_hash
        AND archive.review_archive_text = source.review_text
      LIMIT 1
  );

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews_archive'
      AND INDEX_NAME = 'uk_reviews_archive_text_hash'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews_archive ADD UNIQUE INDEX uk_reviews_archive_text_hash (review_archive_text_hash)',
    'SELECT ''uk_reviews_archive_text_hash exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

DROP TEMPORARY TABLE IF EXISTS tmp_reviews_archive_source;
DROP TEMPORARY TABLE IF EXISTS tmp_reviews_archive_duplicate_ids;
