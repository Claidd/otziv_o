SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'companies'
      AND index_name = 'idx_companies_archive_message_candidates'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE companies ADD INDEX idx_companies_archive_message_candidates (company_status, company_active, company_status_changed_at, company_id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT ''idx_companies_archive_message_candidates exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'archive_orders'
      AND index_name = 'idx_archive_orders_company_restore_latest'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE archive_orders ADD INDEX idx_archive_orders_company_restore_latest (order_company, restored_at, archived_at DESC, order_id DESC), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT ''idx_archive_orders_company_restore_latest exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_links'
      AND index_name = 'idx_payment_links_bank_init_reserved'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE payment_links ADD INDEX idx_payment_links_bank_init_reserved (bank_init_nonce, bank_init_lease_until, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT ''idx_payment_links_bank_init_reserved exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_links'
      AND index_name = 'idx_payment_links_cancel_reconciliation'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE payment_links ADD INDEX idx_payment_links_cancel_reconciliation (bank_cancel_origin_status, bank_reconciliation_attempted_at, updated_at, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT ''idx_payment_links_cancel_reconciliation exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
