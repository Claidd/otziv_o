SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'common_invoice_payment_refs'
      AND COLUMN_NAME = 'cancel_attempts'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE common_invoice_payment_refs ADD COLUMN cancel_attempts INT NOT NULL DEFAULT 0',
    'SELECT ''cancel_attempts exists on common_invoice_payment_refs'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE common_invoice_payment_refs
SET status = 'CANCEL_PENDING'
WHERE status = 'INIT_CONFLICT'
  AND tbank_payment_id IS NOT NULL
  AND tbank_payment_id <> ''
  AND tbank_terminal_key IS NOT NULL
  AND tbank_terminal_key <> ''
  AND amount_kopecks IS NOT NULL
  AND amount_kopecks > 0;

UPDATE common_invoice_payment_refs
SET status = 'CANCEL_FAILED'
WHERE status = 'CANCELING'
  AND updated_at <= CURRENT_TIMESTAMP(6) - INTERVAL 30 MINUTE;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'common_invoice_payment_refs'
      AND INDEX_NAME = 'idx_common_invoice_payment_refs_cancel_queue'
);
SET @sql = IF(
    @index_exists = 0,
    'CREATE INDEX idx_common_invoice_payment_refs_cancel_queue ON common_invoice_payment_refs (status, updated_at, cancel_attempts)',
    'SELECT ''idx_common_invoice_payment_refs_cancel_queue exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
