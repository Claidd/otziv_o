-- Drop only indexes whose complete column signature is already covered by a
-- primary/unique index. Each statement is conditional so this cleanup is safe
-- on installations whose legacy schema has already been repaired manually.

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'workers_companies' AND index_name = 'company_id_idx'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE workers_companies DROP INDEX company_id_idx',
    'SELECT ''company_id_idx already absent''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'operators_users' AND index_name = 'user_id_idx'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE operators_users DROP INDEX user_id_idx',
    'SELECT ''user_id_idx already absent''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'managers_users' AND index_name = 'manager_user_id_idx'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE managers_users DROP INDEX manager_user_id_idx',
    'SELECT ''manager_user_id_idx already absent''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'workers_users' AND index_name = 'worker_user_id_idx'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE workers_users DROP INDEX worker_user_id_idx',
    'SELECT ''worker_user_id_idx already absent''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'marketologs_users' AND index_name = 'user_marketolog_id_idx'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE marketologs_users DROP INDEX user_marketolog_id_idx',
    'SELECT ''user_marketolog_id_idx already absent''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'reviews' AND index_name = 'idx_reviews_filial'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE reviews DROP INDEX idx_reviews_filial',
    'SELECT ''idx_reviews_filial already absent on reviews''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'archive_reviews' AND index_name = 'idx_reviews_filial'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE archive_reviews DROP INDEX idx_reviews_filial',
    'SELECT ''idx_reviews_filial already absent on archive_reviews''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'filial' AND index_name = 'idx_filial_url'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE filial DROP INDEX idx_filial_url',
    'SELECT ''idx_filial_url already absent''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'telephones' AND index_name = 'idx_telephone_number'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE telephones DROP INDEX idx_telephone_number',
    'SELECT ''idx_telephone_number already absent''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'users' AND index_name = 'email_UNIQUE'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE users DROP INDEX email_UNIQUE',
    'SELECT ''email_UNIQUE already absent''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'users' AND index_name = 'username_UNIQUE'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE users DROP INDEX username_UNIQUE',
    'SELECT ''username_UNIQUE already absent''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'users' AND index_name = 'id_UNIQUE'
);
SET @sql = IF(@index_exists > 0,
    'ALTER TABLE users DROP INDEX id_UNIQUE',
    'SELECT ''id_UNIQUE already absent''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
