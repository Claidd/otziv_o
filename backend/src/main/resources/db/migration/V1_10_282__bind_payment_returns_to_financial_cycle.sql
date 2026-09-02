-- A payment return may only reverse the exact financial cycle that was
-- created by the returned link. Historical rows remain NULL deliberately:
-- their amount/source cannot be reconstructed safely from mutable order data.
-- Every DDL statement is independently guarded because MySQL auto-commits
-- ALTER TABLE; a repaired Flyway run must survive any partially applied state.

SET @v282_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'payment_check'
      AND COLUMN_NAME = 'check_paid_amount'
);
SET @v282_sql = IF(
    @v282_column_exists = 0,
    'ALTER TABLE payment_check ADD COLUMN check_paid_amount INT NULL AFTER check_sum',
    'SELECT 1'
);
PREPARE v282_stmt FROM @v282_sql; EXECUTE v282_stmt; DEALLOCATE PREPARE v282_stmt;

SET @v282_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'payment_check'
      AND COLUMN_NAME = 'check_payment_link'
);
SET @v282_sql = IF(
    @v282_column_exists = 0,
    'ALTER TABLE payment_check ADD COLUMN check_payment_link BIGINT NULL AFTER check_paid_amount',
    'SELECT 1'
);
PREPARE v282_stmt FROM @v282_sql; EXECUTE v282_stmt; DEALLOCATE PREPARE v282_stmt;

SET @v282_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_payment_check'
      AND COLUMN_NAME = 'check_paid_amount'
);
SET @v282_sql = IF(
    @v282_column_exists = 0,
    'ALTER TABLE archive_payment_check ADD COLUMN check_paid_amount INT NULL AFTER check_sum',
    'SELECT 1'
);
PREPARE v282_stmt FROM @v282_sql; EXECUTE v282_stmt; DEALLOCATE PREPARE v282_stmt;

SET @v282_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_payment_check'
      AND COLUMN_NAME = 'check_payment_link'
);
SET @v282_sql = IF(
    @v282_column_exists = 0,
    'ALTER TABLE archive_payment_check ADD COLUMN check_payment_link BIGINT NULL AFTER check_paid_amount',
    'SELECT 1'
);
PREPARE v282_stmt FROM @v282_sql; EXECUTE v282_stmt; DEALLOCATE PREPARE v282_stmt;

-- The marker is committed atomically with the financial rollback. It fences a
-- retrying outbox claim from touching a later payment cycle after post-commit
-- route/message delivery failed.
SET @v282_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'payment_links'
      AND COLUMN_NAME = 'return_recovery_processed_at'
);
SET @v282_sql = IF(
    @v282_column_exists = 0,
    'ALTER TABLE payment_links ADD COLUMN return_recovery_processed_at DATETIME(6) NULL AFTER provider_terminal_status',
    'SELECT 1'
);
PREPARE v282_stmt FROM @v282_sql; EXECUTE v282_stmt; DEALLOCATE PREPARE v282_stmt;

SET @v282_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'payment_links'
      AND COLUMN_NAME = 'return_recovery_payment_check_id'
);
SET @v282_sql = IF(
    @v282_column_exists = 0,
    'ALTER TABLE payment_links ADD COLUMN return_recovery_payment_check_id BIGINT NULL AFTER return_recovery_processed_at',
    'SELECT 1'
);
PREPARE v282_stmt FROM @v282_sql; EXECUTE v282_stmt; DEALLOCATE PREPARE v282_stmt;

SET @v282_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'payment_links'
      AND COLUMN_NAME = 'return_recovery_outcome'
);
SET @v282_sql = IF(
    @v282_column_exists = 0,
    'ALTER TABLE payment_links ADD COLUMN return_recovery_outcome VARCHAR(32) NULL AFTER return_recovery_payment_check_id',
    'SELECT 1'
);
PREPARE v282_stmt FROM @v282_sql; EXECUTE v282_stmt; DEALLOCATE PREPARE v282_stmt;

-- Historical archive schemas do not necessarily contain the later
-- provider_terminal_status column, so the first archive marker is appended.
SET @v282_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_payment_links'
      AND COLUMN_NAME = 'return_recovery_processed_at'
);
SET @v282_sql = IF(
    @v282_column_exists = 0,
    'ALTER TABLE archive_payment_links ADD COLUMN return_recovery_processed_at DATETIME(6) NULL',
    'SELECT 1'
);
PREPARE v282_stmt FROM @v282_sql; EXECUTE v282_stmt; DEALLOCATE PREPARE v282_stmt;

SET @v282_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_payment_links'
      AND COLUMN_NAME = 'return_recovery_payment_check_id'
);
SET @v282_sql = IF(
    @v282_column_exists = 0,
    'ALTER TABLE archive_payment_links ADD COLUMN return_recovery_payment_check_id BIGINT NULL AFTER return_recovery_processed_at',
    'SELECT 1'
);
PREPARE v282_stmt FROM @v282_sql; EXECUTE v282_stmt; DEALLOCATE PREPARE v282_stmt;

SET @v282_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_payment_links'
      AND COLUMN_NAME = 'return_recovery_outcome'
);
SET @v282_sql = IF(
    @v282_column_exists = 0,
    'ALTER TABLE archive_payment_links ADD COLUMN return_recovery_outcome VARCHAR(32) NULL AFTER return_recovery_payment_check_id',
    'SELECT 1'
);
PREPARE v282_stmt FROM @v282_sql; EXECUTE v282_stmt; DEALLOCATE PREPARE v282_stmt;
