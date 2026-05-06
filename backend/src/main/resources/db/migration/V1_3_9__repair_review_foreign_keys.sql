-- Repair historical reviews foreign keys without deleting existing reviews.
-- Old V1_1_0 constraints pointed some columns at non-canonical parent columns.

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'rewiews_category'
);
SET @sql = IF(@fk_exists > 0,
    'ALTER TABLE reviews DROP FOREIGN KEY rewiews_category',
    'SELECT ''rewiews_category not present''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'rewiews_subcategory'
);
SET @sql = IF(@fk_exists > 0,
    'ALTER TABLE reviews DROP FOREIGN KEY rewiews_subcategory',
    'SELECT ''rewiews_subcategory not present''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'reviews_order_details'
);
SET @sql = IF(@fk_exists > 0,
    'ALTER TABLE reviews DROP FOREIGN KEY reviews_order_details',
    'SELECT ''reviews_order_details not present''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'reviews_bot'
);
SET @sql = IF(@fk_exists > 0,
    'ALTER TABLE reviews DROP FOREIGN KEY reviews_bot',
    'SELECT ''reviews_bot not present''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'review_filial'
);
SET @sql = IF(@fk_exists > 0,
    'ALTER TABLE reviews DROP FOREIGN KEY review_filial',
    'SELECT ''review_filial not present''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'review_worker'
);
SET @sql = IF(@fk_exists > 0,
    'ALTER TABLE reviews DROP FOREIGN KEY review_worker',
    'SELECT ''review_worker not present''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE reviews r
LEFT JOIN categorys c ON r.review_category = c.category_id
SET r.review_category = NULL
WHERE r.review_category IS NOT NULL AND c.category_id IS NULL;

UPDATE reviews r
LEFT JOIN subcategoryes s ON r.review_subcategory = s.subcategory_id
SET r.review_subcategory = NULL
WHERE r.review_subcategory IS NOT NULL AND s.subcategory_id IS NULL;

UPDATE reviews r
LEFT JOIN order_details od ON r.review_order_details = od.order_detail_id
SET r.review_order_details = NULL
WHERE r.review_order_details IS NOT NULL AND od.order_detail_id IS NULL;

UPDATE reviews r
LEFT JOIN bots b ON r.review_bot = b.bot_id
SET r.review_bot = NULL
WHERE r.review_bot IS NOT NULL AND b.bot_id IS NULL;

UPDATE reviews r
LEFT JOIN filial f ON r.review_filial = f.filial_id
SET r.review_filial = NULL
WHERE r.review_filial IS NOT NULL AND f.filial_id IS NULL;

UPDATE reviews r
LEFT JOIN workers w ON r.review_worker = w.worker_id
SET r.review_worker = NULL
WHERE r.review_worker IS NOT NULL AND w.worker_id IS NULL;

UPDATE reviews r
LEFT JOIN products p ON r.review_product = p.product_id
SET r.review_product = NULL
WHERE r.review_product IS NOT NULL AND p.product_id IS NULL;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND COLUMN_NAME = 'review_category' AND SEQ_IN_INDEX = 1
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_category_fk (review_category)',
    'SELECT ''review_category index exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND COLUMN_NAME = 'review_subcategory' AND SEQ_IN_INDEX = 1
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_subcategory_fk (review_subcategory)',
    'SELECT ''review_subcategory index exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND COLUMN_NAME = 'review_order_details' AND SEQ_IN_INDEX = 1
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_order_details_fk (review_order_details)',
    'SELECT ''review_order_details index exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND COLUMN_NAME = 'review_bot' AND SEQ_IN_INDEX = 1
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_bot_fk (review_bot)',
    'SELECT ''review_bot index exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND COLUMN_NAME = 'review_filial' AND SEQ_IN_INDEX = 1
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_filial_fk (review_filial)',
    'SELECT ''review_filial index exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND COLUMN_NAME = 'review_worker' AND SEQ_IN_INDEX = 1
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_worker_fk (review_worker)',
    'SELECT ''review_worker index exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND COLUMN_NAME = 'review_product' AND SEQ_IN_INDEX = 1
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_product_fk (review_product)',
    'SELECT ''review_product index exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_reviews_category'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE reviews ADD CONSTRAINT fk_reviews_category FOREIGN KEY (review_category) REFERENCES categorys (category_id) ON DELETE SET NULL ON UPDATE CASCADE',
    'SELECT ''fk_reviews_category exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_reviews_subcategory'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE reviews ADD CONSTRAINT fk_reviews_subcategory FOREIGN KEY (review_subcategory) REFERENCES subcategoryes (subcategory_id) ON DELETE SET NULL ON UPDATE CASCADE',
    'SELECT ''fk_reviews_subcategory exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_reviews_order_details'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE reviews ADD CONSTRAINT fk_reviews_order_details FOREIGN KEY (review_order_details) REFERENCES order_details (order_detail_id) ON DELETE SET NULL ON UPDATE CASCADE',
    'SELECT ''fk_reviews_order_details exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_reviews_bot'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE reviews ADD CONSTRAINT fk_reviews_bot FOREIGN KEY (review_bot) REFERENCES bots (bot_id) ON DELETE SET NULL ON UPDATE CASCADE',
    'SELECT ''fk_reviews_bot exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_reviews_filial'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE reviews ADD CONSTRAINT fk_reviews_filial FOREIGN KEY (review_filial) REFERENCES filial (filial_id) ON DELETE SET NULL ON UPDATE CASCADE',
    'SELECT ''fk_reviews_filial exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_reviews_worker'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE reviews ADD CONSTRAINT fk_reviews_worker FOREIGN KEY (review_worker) REFERENCES workers (worker_id) ON DELETE SET NULL ON UPDATE CASCADE',
    'SELECT ''fk_reviews_worker exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_review_product'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE reviews ADD CONSTRAINT fk_review_product FOREIGN KEY (review_product) REFERENCES products (product_id) ON DELETE SET NULL ON UPDATE CASCADE',
    'SELECT ''fk_review_product exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
