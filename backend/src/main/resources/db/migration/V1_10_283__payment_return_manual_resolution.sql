-- Manual reconciliation is a terminal, audited operation. MySQL DDL is not
-- transactional, therefore every column/constraint is independently guarded.

SET @v283_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_links'
      AND COLUMN_NAME = 'return_recovery_resolved_at'
);
SET @v283_sql = IF(@v283_column_exists = 0,
    'ALTER TABLE payment_links ADD COLUMN return_recovery_resolved_at DATETIME(6) NULL AFTER return_recovery_outcome',
    'SELECT 1');
PREPARE v283_stmt FROM @v283_sql; EXECUTE v283_stmt; DEALLOCATE PREPARE v283_stmt;

SET @v283_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_links'
      AND COLUMN_NAME = 'return_recovery_resolved_by'
);
SET @v283_sql = IF(@v283_column_exists = 0,
    'ALTER TABLE payment_links ADD COLUMN return_recovery_resolved_by VARCHAR(150) NULL AFTER return_recovery_resolved_at',
    'SELECT 1');
PREPARE v283_stmt FROM @v283_sql; EXECUTE v283_stmt; DEALLOCATE PREPARE v283_stmt;

SET @v283_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_links'
      AND COLUMN_NAME = 'return_recovery_resolution_reason'
);
SET @v283_sql = IF(@v283_column_exists = 0,
    'ALTER TABLE payment_links ADD COLUMN return_recovery_resolution_reason VARCHAR(512) NULL AFTER return_recovery_resolved_by',
    'SELECT 1');
PREPARE v283_stmt FROM @v283_sql; EXECUTE v283_stmt; DEALLOCATE PREPARE v283_stmt;

-- V255 deliberately predated CANCELED return semantics, while full returns
-- that its old worker marked SUCCEEDED were never financially attributed.
-- Re-drive only the current full-return observation while the V282 tuple is
-- still empty.  The new recovery is idempotent and sends legacy/null-source
-- checks to durable manual reconciliation rather than guessing.
UPDATE payment_link_return_reconciliation_outbox return_outbox
JOIN payment_links link
  ON link.id = return_outbox.payment_link_id
 AND return_outbox.source_version = COALESCE(link.row_version, 0)
 AND return_outbox.observed_status = link.status
SET return_outbox.status = 'PENDING',
    return_outbox.claim_token = NULL,
    return_outbox.lease_until = NULL,
    return_outbox.next_attempt_at = CURRENT_TIMESTAMP(6),
    return_outbox.last_error = 'V283 financial-cycle attribution replay',
    return_outbox.processed_at = NULL
WHERE return_outbox.status = 'SUCCEEDED'
  AND link.return_recovery_processed_at IS NULL
  AND link.return_recovery_payment_check_id IS NULL
  AND link.return_recovery_outcome IS NULL
  AND link.return_recovery_resolved_at IS NULL
  AND link.return_recovery_resolved_by IS NULL
  AND link.return_recovery_resolution_reason IS NULL
  AND (
        link.status IN ('REVERSED', 'REFUNDED')
        OR (
            link.status = 'CANCELED'
            AND (
                COALESCE(link.confirmed_amount_kopecks, 0) > 0
                OR link.paid_at IS NOT NULL
                OR link.manual_confirmed_at IS NOT NULL
                OR link.bank_cancel_origin_status IN (
                    'MANUAL_REPORTED', 'TEST_CONFIRMED', 'CONFIRMED',
                    'AMOUNT_MISMATCH', 'NEEDS_RECONCILIATION'
                )
            )
        )
  );

INSERT IGNORE INTO payment_link_return_reconciliation_outbox (
    payment_link_id,
    source_version,
    observed_status
)
SELECT link.id,
       COALESCE(link.row_version, 0),
       link.status
FROM payment_links link
WHERE link.return_recovery_processed_at IS NULL
  AND link.return_recovery_payment_check_id IS NULL
  AND link.return_recovery_outcome IS NULL
  AND link.return_recovery_resolved_at IS NULL
  AND link.return_recovery_resolved_by IS NULL
  AND link.return_recovery_resolution_reason IS NULL
  AND (
        link.status IN ('REVERSED', 'REFUNDED')
        OR (
            link.status = 'CANCELED'
            AND (
                COALESCE(link.confirmed_amount_kopecks, 0) > 0
                OR link.paid_at IS NOT NULL
                OR link.manual_confirmed_at IS NOT NULL
                OR link.bank_cancel_origin_status IN (
                    'MANUAL_REPORTED', 'TEST_CONFIRMED', 'CONFIRMED',
                    'AMOUNT_MISMATCH', 'NEEDS_RECONCILIATION'
                )
            )
        )
  );

SET @v283_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'archive_payment_links'
      AND COLUMN_NAME = 'return_recovery_resolved_at'
);
SET @v283_sql = IF(@v283_column_exists = 0,
    'ALTER TABLE archive_payment_links ADD COLUMN return_recovery_resolved_at DATETIME(6) NULL AFTER return_recovery_outcome',
    'SELECT 1');
PREPARE v283_stmt FROM @v283_sql; EXECUTE v283_stmt; DEALLOCATE PREPARE v283_stmt;

SET @v283_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'archive_payment_links'
      AND COLUMN_NAME = 'return_recovery_resolved_by'
);
SET @v283_sql = IF(@v283_column_exists = 0,
    'ALTER TABLE archive_payment_links ADD COLUMN return_recovery_resolved_by VARCHAR(150) NULL AFTER return_recovery_resolved_at',
    'SELECT 1');
PREPARE v283_stmt FROM @v283_sql; EXECUTE v283_stmt; DEALLOCATE PREPARE v283_stmt;

SET @v283_column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'archive_payment_links'
      AND COLUMN_NAME = 'return_recovery_resolution_reason'
);
SET @v283_sql = IF(@v283_column_exists = 0,
    'ALTER TABLE archive_payment_links ADD COLUMN return_recovery_resolution_reason VARCHAR(512) NULL AFTER return_recovery_resolved_by',
    'SELECT 1');
PREPARE v283_stmt FROM @v283_sql; EXECUTE v283_stmt; DEALLOCATE PREPARE v283_stmt;

-- Historical rows that were archived before the exact-cycle attribution fix
-- cannot be safely auto-restored or rolled back.  Put them into a valid,
-- visible terminal-manual state and leave an idempotent audit breadcrumb.
UPDATE archive_payment_links archived_link
SET archived_link.return_recovery_processed_at = CURRENT_TIMESTAMP(6),
    archived_link.return_recovery_outcome = 'MANUAL_RECONCILIATION',
    archived_link.last_error =
        'archived_payment_return_manual_reconciliation:v283_missing_financial_cycle_attribution'
WHERE archived_link.return_recovery_processed_at IS NULL
  AND archived_link.return_recovery_payment_check_id IS NULL
  AND archived_link.return_recovery_outcome IS NULL
  AND archived_link.return_recovery_resolved_at IS NULL
  AND archived_link.return_recovery_resolved_by IS NULL
  AND archived_link.return_recovery_resolution_reason IS NULL
  AND (
        archived_link.status IN ('REVERSED', 'REFUNDED')
        OR (
            archived_link.status = 'CANCELED'
            AND (
                COALESCE(archived_link.confirmed_amount_kopecks, 0) > 0
                OR archived_link.paid_at IS NOT NULL
                OR archived_link.manual_confirmed_at IS NOT NULL
            )
        )
  );

INSERT INTO business_audit_events (
    created_at,
    actor,
    source,
    action,
    entity_type,
    entity_id,
    order_id,
    old_value,
    new_value,
    details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:migration:v283',
       'FLYWAY',
       'ARCHIVED_PAYMENT_RETURN_RECONCILIATION_REQUIRED',
       'PAYMENT_LINK',
       CAST(archived_link.id AS CHAR),
       archived_link.order_id,
       'UNATTRIBUTED_ARCHIVED_FULL_RETURN',
       'MANUAL_RECONCILIATION',
       CONCAT('status=', COALESCE(archived_link.status, 'NULL'),
              '; no financial mutation was attempted')
FROM archive_payment_links archived_link
WHERE archived_link.return_recovery_outcome = 'MANUAL_RECONCILIATION'
  AND archived_link.last_error =
      'archived_payment_return_manual_reconciliation:v283_missing_financial_cycle_attribution'
  AND NOT EXISTS (
      SELECT 1
      FROM business_audit_events existing
      WHERE existing.action = 'ARCHIVED_PAYMENT_RETURN_RECONCILIATION_REQUIRED'
        AND existing.entity_type = 'PAYMENT_LINK'
        AND existing.entity_id COLLATE utf8mb4_unicode_ci =
            CAST(archived_link.id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci
  );

-- Legacy checks may have no snapshot/source. New sourced checks must carry a
-- non-negative immutable amount snapshot.
SET @v283_check_snapshot_expression = 'CHECK ((check_paid_amount IS NULL OR check_paid_amount >= 0) AND (check_payment_link IS NULL OR check_paid_amount IS NOT NULL))';

SET @v283_constraint_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_check'
      AND CONSTRAINT_NAME = 'ck_payment_check_return_snapshot'
);
SET @v283_sql = IF(@v283_constraint_exists = 0,
    CONCAT('ALTER TABLE payment_check ADD CONSTRAINT ck_payment_check_return_snapshot ', @v283_check_snapshot_expression),
    'SELECT 1');
PREPARE v283_stmt FROM @v283_sql; EXECUTE v283_stmt; DEALLOCATE PREPARE v283_stmt;

SET @v283_constraint_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'archive_payment_check'
      AND CONSTRAINT_NAME = 'ck_archive_payment_check_return_snapshot'
);
SET @v283_sql = IF(@v283_constraint_exists = 0,
    CONCAT('ALTER TABLE archive_payment_check ADD CONSTRAINT ck_archive_payment_check_return_snapshot ', @v283_check_snapshot_expression),
    'SELECT 1');
PREPARE v283_stmt FROM @v283_sql; EXECUTE v283_stmt; DEALLOCATE PREPARE v283_stmt;

-- Empty marker tuple is the only unprocessed state. Every processed outcome is
-- known; APPLIED identifies the exact check; manually resolved outcomes carry
-- complete audit evidence. Unknown/partial tuples are rejected by MySQL.
SET @v283_marker_expression = 'CHECK (((return_recovery_processed_at IS NULL AND return_recovery_payment_check_id IS NULL AND return_recovery_outcome IS NULL AND return_recovery_resolved_at IS NULL AND return_recovery_resolved_by IS NULL AND return_recovery_resolution_reason IS NULL)) OR (return_recovery_processed_at IS NOT NULL AND (return_recovery_payment_check_id IS NULL OR return_recovery_payment_check_id > 0) AND (((COALESCE(return_recovery_outcome, '''') = ''APPLIED'' AND return_recovery_payment_check_id IS NOT NULL AND return_recovery_resolved_at IS NULL AND return_recovery_resolved_by IS NULL AND return_recovery_resolution_reason IS NULL)) OR ((COALESCE(return_recovery_outcome, '''') IN (''STALE_PAYMENT_CYCLE'', ''MANUAL_RECONCILIATION'') AND return_recovery_resolved_at IS NULL AND return_recovery_resolved_by IS NULL AND return_recovery_resolution_reason IS NULL)) OR ((COALESCE(return_recovery_outcome, '''') IN (''APPLIED_MANUALLY'', ''ACCEPTED_NOOP'') AND return_recovery_resolved_at IS NOT NULL AND NULLIF(TRIM(return_recovery_resolved_by), '''') IS NOT NULL AND NULLIF(TRIM(return_recovery_resolution_reason), '''') IS NOT NULL)))))';

SET @v283_constraint_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_links'
      AND CONSTRAINT_NAME = 'ck_payment_links_return_recovery_tuple'
);
SET @v283_sql = IF(@v283_constraint_exists = 0,
    CONCAT('ALTER TABLE payment_links ADD CONSTRAINT ck_payment_links_return_recovery_tuple ', @v283_marker_expression),
    'SELECT 1');
PREPARE v283_stmt FROM @v283_sql; EXECUTE v283_stmt; DEALLOCATE PREPARE v283_stmt;

SET @v283_constraint_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'archive_payment_links'
      AND CONSTRAINT_NAME = 'ck_archive_payment_links_return_recovery_tuple'
);
SET @v283_sql = IF(@v283_constraint_exists = 0,
    CONCAT('ALTER TABLE archive_payment_links ADD CONSTRAINT ck_archive_payment_links_return_recovery_tuple ', @v283_marker_expression),
    'SELECT 1');
PREPARE v283_stmt FROM @v283_sql; EXECUTE v283_stmt; DEALLOCATE PREPARE v283_stmt;
