SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_batches'
      AND COLUMN_NAME = 'review_recovery_batch_archive_order_id'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_batches ADD COLUMN review_recovery_batch_archive_order_id BIGINT NULL',
    'SELECT ''review_recovery_batch_archive_order_id exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_batches'
      AND COLUMN_NAME = 'review_recovery_batch_archive_company_title'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_batches ADD COLUMN review_recovery_batch_archive_company_title VARCHAR(255) NULL',
    'SELECT ''review_recovery_batch_archive_company_title exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_batches'
      AND COLUMN_NAME = 'review_recovery_batch_archive_chat_url'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_batches ADD COLUMN review_recovery_batch_archive_chat_url VARCHAR(1000) NULL',
    'SELECT ''review_recovery_batch_archive_chat_url exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_batches'
      AND COLUMN_NAME = 'review_recovery_batch_archive_order_status'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_batches ADD COLUMN review_recovery_batch_archive_order_status VARCHAR(100) NULL',
    'SELECT ''review_recovery_batch_archive_order_status exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_order_id'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_order_id BIGINT NULL',
    'SELECT ''review_recovery_task_archive_order_id exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_review_id'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_review_id BIGINT NULL',
    'SELECT ''review_recovery_task_archive_review_id exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_company_id'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_company_id BIGINT NULL',
    'SELECT ''review_recovery_task_archive_company_id exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_order_details_id'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_order_details_id BINARY(16) NULL',
    'SELECT ''review_recovery_task_archive_order_details_id exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_order_status'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_order_status VARCHAR(100) NULL',
    'SELECT ''review_recovery_task_archive_order_status exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_company_title'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_company_title VARCHAR(255) NULL',
    'SELECT ''review_recovery_task_archive_company_title exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_company_note'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_company_note TEXT NULL',
    'SELECT ''review_recovery_task_archive_company_note exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_order_note'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_order_note TEXT NULL',
    'SELECT ''review_recovery_task_archive_order_note exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_filial_city'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_filial_city VARCHAR(100) NULL',
    'SELECT ''review_recovery_task_archive_filial_city exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_filial_city_id'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_filial_city_id BIGINT NULL',
    'SELECT ''review_recovery_task_archive_filial_city_id exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_filial_title'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_filial_title VARCHAR(255) NULL',
    'SELECT ''review_recovery_task_archive_filial_title exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_filial_url'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_filial_url VARCHAR(1000) NULL',
    'SELECT ''review_recovery_task_archive_filial_url exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_category'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_category VARCHAR(255) NULL',
    'SELECT ''review_recovery_task_archive_category exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_subcategory'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_subcategory VARCHAR(255) NULL',
    'SELECT ''review_recovery_task_archive_subcategory exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_product_id'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_product_id BIGINT NULL',
    'SELECT ''review_recovery_task_archive_product_id exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_product_title'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_product_title VARCHAR(255) NULL',
    'SELECT ''review_recovery_task_archive_product_title exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_review_created'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_review_created DATE NULL',
    'SELECT ''review_recovery_task_archive_review_created exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_review_changed'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_review_changed DATE NULL',
    'SELECT ''review_recovery_task_archive_review_changed exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_review_published_date'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_review_published_date DATE NULL',
    'SELECT ''review_recovery_task_archive_review_published_date exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_review_publish'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_review_publish BIT(1) NOT NULL DEFAULT b''0''',
    'SELECT ''review_recovery_task_archive_review_publish exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_review_vigul'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_review_vigul BIT(1) NOT NULL DEFAULT b''0''',
    'SELECT ''review_recovery_task_archive_review_vigul exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_review_price'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_review_price DECIMAL(38,2) NULL',
    'SELECT ''review_recovery_task_archive_review_price exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND COLUMN_NAME = 'review_recovery_task_archive_review_url'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD COLUMN review_recovery_task_archive_review_url VARCHAR(1000) NULL',
    'SELECT ''review_recovery_task_archive_review_url exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_batches'
      AND CONSTRAINT_NAME = 'fk_review_recovery_batches_order'
);
SET @sql = IF(@fk_exists = 1,
    'ALTER TABLE review_recovery_batches DROP FOREIGN KEY fk_review_recovery_batches_order',
    'SELECT ''fk_review_recovery_batches_order missing''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND CONSTRAINT_NAME = 'fk_review_recovery_tasks_order'
);
SET @sql = IF(@fk_exists = 1,
    'ALTER TABLE review_recovery_tasks DROP FOREIGN KEY fk_review_recovery_tasks_order',
    'SELECT ''fk_review_recovery_tasks_order missing''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND CONSTRAINT_NAME = 'fk_review_recovery_tasks_review'
);
SET @sql = IF(@fk_exists = 1,
    'ALTER TABLE review_recovery_tasks DROP FOREIGN KEY fk_review_recovery_tasks_review',
    'SELECT ''fk_review_recovery_tasks_review missing''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE review_recovery_batches MODIFY review_recovery_batch_order BIGINT NULL;
ALTER TABLE review_recovery_tasks MODIFY review_recovery_task_order BIGINT NULL;
ALTER TABLE review_recovery_tasks MODIFY review_recovery_task_review BIGINT NULL;

SET @fk_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_batches'
      AND CONSTRAINT_NAME = 'fk_review_recovery_batches_order'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE review_recovery_batches ADD CONSTRAINT fk_review_recovery_batches_order FOREIGN KEY (review_recovery_batch_order) REFERENCES orders (order_id) ON DELETE CASCADE ON UPDATE CASCADE',
    'SELECT ''fk_review_recovery_batches_order exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND CONSTRAINT_NAME = 'fk_review_recovery_tasks_order'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD CONSTRAINT fk_review_recovery_tasks_order FOREIGN KEY (review_recovery_task_order) REFERENCES orders (order_id) ON DELETE CASCADE ON UPDATE CASCADE',
    'SELECT ''fk_review_recovery_tasks_order exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND CONSTRAINT_NAME = 'fk_review_recovery_tasks_review'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD CONSTRAINT fk_review_recovery_tasks_review FOREIGN KEY (review_recovery_task_review) REFERENCES reviews (review_id) ON DELETE CASCADE ON UPDATE CASCADE',
    'SELECT ''fk_review_recovery_tasks_review exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_batches'
      AND INDEX_NAME = 'idx_review_recovery_batches_archive_order_status'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE review_recovery_batches ADD INDEX idx_review_recovery_batches_archive_order_status (review_recovery_batch_archive_order_id, review_recovery_batch_status)',
    'SELECT ''idx_review_recovery_batches_archive_order_status exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND INDEX_NAME = 'idx_review_recovery_tasks_archive_review_status'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD INDEX idx_review_recovery_tasks_archive_review_status (review_recovery_task_archive_review_id, review_recovery_task_status)',
    'SELECT ''idx_review_recovery_tasks_archive_review_status exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
