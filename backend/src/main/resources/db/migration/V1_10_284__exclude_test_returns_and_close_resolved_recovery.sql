-- V283 intentionally replayed historical full-return observations so every
-- real payment cycle would receive a durable attribution marker. It also
-- surfaced test-provider rows and one return that had already been completed
-- and audited. Resolve only those two evidence-backed classes. No order,
-- payment_check, company or reward-ledger row is changed by this migration.

SET @v284_now = CURRENT_TIMESTAMP(6);

CREATE TEMPORARY TABLE v284_resolved_return_recovery (
    payment_link_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    resolution_kind VARCHAR(40) NOT NULL,
    previous_row_version BIGINT NOT NULL,
    previous_processed_at DATETIME(6) NOT NULL,
    previous_payment_check_id BIGINT NULL,
    previous_outcome VARCHAR(32) NOT NULL,
    previous_error VARCHAR(512) NULL,
    prior_audit_event_id BIGINT NULL,
    resolution_reason VARCHAR(512) NOT NULL,
    open_reminder_count INT NOT NULL,
    PRIMARY KEY (payment_link_id)
) ENGINE=InnoDB;

CREATE TEMPORARY TABLE v284_resolved_archived_return_recovery (
    payment_link_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    previous_processed_at DATETIME(6) NOT NULL,
    previous_payment_check_id BIGINT NULL,
    previous_outcome VARCHAR(32) NOT NULL,
    previous_error VARCHAR(512) NOT NULL,
    resolution_reason VARCHAR(512) NOT NULL,
    PRIMARY KEY (payment_link_id)
) ENGINE=InnoDB;

-- Flyway owns the transaction boundary for this TEMPORARY-table + DML-only
-- migration, so the business writes and the success row in schema history are
-- committed atomically. Explicit locking clauses below keep inspected source
-- rows stable until Flyway commits.

INSERT INTO v284_resolved_return_recovery (
    payment_link_id,
    order_id,
    resolution_kind,
    previous_row_version,
    previous_processed_at,
    previous_payment_check_id,
    previous_outcome,
    previous_error,
    prior_audit_event_id,
    resolution_reason,
    open_reminder_count
)
SELECT link.id,
       link.order_id,
       CASE
           WHEN RIGHT(UPPER(TRIM(COALESCE(link.tbank_terminal_key, ''))), 4) = 'DEMO'
                OR link.bank_cancel_origin_status = 'TEST_CONFIRMED'
               THEN 'TEST_PAYMENT'
           ELSE 'PRIOR_COMPLETED_REFUND'
       END,
       link.row_version,
       link.return_recovery_processed_at,
       link.return_recovery_payment_check_id,
       link.return_recovery_outcome,
       link.last_error,
       (
           SELECT MIN(proof.event_id)
           FROM business_audit_events proof
           WHERE link.id = 3918
             AND link.order_id = 24378
             AND proof.created_at = TIMESTAMP('2026-08-29 10:52:48.613881')
             AND proof.actor = 'owner:hunt'
             AND proof.source = 'owner_confirmed_tbank_refund'
             AND proof.action = 'ORDER_DUPLICATE_PAYMENT_REFUNDED'
             AND proof.entity_type = 'PAYMENT_LINK'
             AND proof.entity_id = '3918'
             AND proof.order_id = 24378
             AND proof.old_value =
                 'cash=200000;check=200000;adjustment=-100000;link=AMOUNT_MISMATCH'
             AND proof.new_value =
                 'cash=100000;check=100000;adjustment=inactive;link=REFUNDED'
       ),
       CASE
           WHEN RIGHT(UPPER(TRIM(COALESCE(link.tbank_terminal_key, ''))), 4) = 'DEMO'
                OR link.bank_cancel_origin_status = 'TEST_CONFIRMED'
               THEN 'Тестовый платеж исключен из финансового recovery; откат заказа, чека и итогов компании не выполнялся'
           ELSE 'Возврат дублирующего платежа уже завершен и подтвержден аудитом ORDER_DUPLICATE_PAYMENT_REFUNDED; повторный финансовый откат не выполнялся'
       END,
       (
           SELECT COUNT(*)
           FROM personal_reminders reminder
           WHERE reminder.source_type = 'PAYMENT_RETURN_RECONCILIATION'
             AND reminder.source_id = link.id
             AND reminder.source_order_id = link.order_id
             AND reminder.completed_at IS NULL
       )
FROM payment_links link
WHERE link.status IN ('CANCELED', 'REVERSED', 'REFUNDED')
  AND link.payment_method IN ('BANK_FORM', 'SBP_QR')
  AND link.return_recovery_processed_at IS NOT NULL
  AND link.return_recovery_outcome = 'MANUAL_RECONCILIATION'
  AND link.return_recovery_resolved_at IS NULL
  AND link.return_recovery_resolved_by IS NULL
  AND link.return_recovery_resolution_reason IS NULL
  AND (link.return_recovery_payment_check_id IS NULL
       OR link.return_recovery_payment_check_id > 0)
  AND link.last_error LIKE 'payment_return_manual_reconciliation:%'
  AND (
        RIGHT(UPPER(TRIM(COALESCE(link.tbank_terminal_key, ''))), 4) = 'DEMO'
        OR link.bank_cancel_origin_status = 'TEST_CONFIRMED'
        OR (
          link.id = 3918
          AND link.order_id = 24378
          AND EXISTS (
            SELECT 1
            FROM business_audit_events proof
            WHERE proof.created_at = TIMESTAMP('2026-08-29 10:52:48.613881')
              AND proof.actor = 'owner:hunt'
              AND proof.source = 'owner_confirmed_tbank_refund'
              AND proof.action = 'ORDER_DUPLICATE_PAYMENT_REFUNDED'
              AND proof.entity_type = 'PAYMENT_LINK'
              AND proof.entity_id = '3918'
              AND proof.order_id = 24378
              AND proof.old_value =
                  'cash=200000;check=200000;adjustment=-100000;link=AMOUNT_MISMATCH'
              AND proof.new_value =
                  'cash=100000;check=100000;adjustment=inactive;link=REFUNDED'
            FOR SHARE
          )
        )
  )
FOR UPDATE;

-- V283 also marked old archived returns for manual reconciliation. Archived
-- test-provider rows have no real financial cycle to recover, and keeping the
-- marker open would unnecessarily block a future archive restore.
INSERT INTO v284_resolved_archived_return_recovery (
    payment_link_id,
    order_id,
    previous_processed_at,
    previous_payment_check_id,
    previous_outcome,
    previous_error,
    resolution_reason
)
SELECT archived_link.id,
       archived_link.order_id,
       archived_link.return_recovery_processed_at,
       archived_link.return_recovery_payment_check_id,
       archived_link.return_recovery_outcome,
       archived_link.last_error,
       'Архивный тестовый платеж исключен из финансового recovery; финансовые данные не изменялись'
FROM archive_payment_links archived_link
WHERE archived_link.status IN ('CANCELED', 'REVERSED', 'REFUNDED')
  AND archived_link.payment_method IN ('BANK_FORM', 'SBP_QR')
  AND archived_link.return_recovery_processed_at IS NOT NULL
  AND archived_link.return_recovery_outcome = 'MANUAL_RECONCILIATION'
  AND archived_link.return_recovery_resolved_at IS NULL
  AND archived_link.return_recovery_resolved_by IS NULL
  AND archived_link.return_recovery_resolution_reason IS NULL
  AND (archived_link.return_recovery_payment_check_id IS NULL
       OR archived_link.return_recovery_payment_check_id > 0)
  AND archived_link.last_error =
      'archived_payment_return_manual_reconciliation:v283_missing_financial_cycle_attribution'
  AND RIGHT(UPPER(TRIM(COALESCE(archived_link.tbank_terminal_key, ''))), 4) = 'DEMO'
  AND EXISTS (
      SELECT 1
      FROM business_audit_events v283_audit
      WHERE v283_audit.actor = 'system:migration:v283'
        AND v283_audit.source = 'FLYWAY'
        AND v283_audit.action = 'ARCHIVED_PAYMENT_RETURN_RECONCILIATION_REQUIRED'
        AND v283_audit.entity_type = 'PAYMENT_LINK'
        AND v283_audit.entity_id COLLATE utf8mb4_unicode_ci =
            CAST(archived_link.id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci
        AND v283_audit.order_id = archived_link.order_id
        AND v283_audit.old_value = 'UNATTRIBUTED_ARCHIVED_FULL_RETURN'
        AND v283_audit.new_value = 'MANUAL_RECONCILIATION'
      FOR SHARE
  )
FOR UPDATE;

-- The known live refund is accepted only while every post-refund financial
-- fact still matches the inspected production state. A clean installation has
-- no exact owner audit event and therefore passes this production-only guard.
CREATE TEMPORARY TABLE v284_production_refund_guard (
    ok TINYINT NOT NULL
) ENGINE=InnoDB;

INSERT INTO v284_production_refund_guard (ok)
SELECT CASE
    WHEN NOT EXISTS (
        SELECT 1
        FROM business_audit_events proof
        WHERE proof.created_at = TIMESTAMP('2026-08-29 10:52:48.613881')
          AND proof.actor = 'owner:hunt'
          AND proof.source = 'owner_confirmed_tbank_refund'
          AND proof.action = 'ORDER_DUPLICATE_PAYMENT_REFUNDED'
          AND proof.entity_type = 'PAYMENT_LINK'
          AND proof.entity_id = '3918'
          AND proof.order_id = 24378
          AND proof.old_value =
              'cash=200000;check=200000;adjustment=-100000;link=AMOUNT_MISMATCH'
          AND proof.new_value =
              'cash=100000;check=100000;adjustment=inactive;link=REFUNDED'
        FOR SHARE
    ) THEN 1
    WHEN EXISTS (
        SELECT 1
        FROM orders base_order
        JOIN order_statuses order_status
          ON order_status.order_status_id = base_order.order_status
        JOIN payment_check payment
          ON payment.check_id = 20240
         AND payment.check_order = base_order.order_id
         AND payment.check_active = 1
        JOIN payment_links primary_payment
          ON primary_payment.id = 3815
         AND primary_payment.order_id = base_order.order_id
        JOIN payment_links returned_payment
          ON returned_payment.id = 3918
         AND returned_payment.order_id = base_order.order_id
        JOIN order_payment_reconciliations reconciliation
          ON reconciliation.reconciliation_key =
             'V275:ORDER:24378:CLIENT-OVERPAYMENT'
         AND reconciliation.order_id = base_order.order_id
        LEFT JOIN v284_resolved_return_recovery candidate
          ON candidate.payment_link_id = returned_payment.id
        WHERE base_order.order_id = 24378
          AND base_order.order_status = 10
          AND HEX(order_status.order_status_title) =
              'D09ED0BFD0BBD0B0D187D0B5D0BDD0BE'
          AND base_order.order_sum = 1000.00
          AND payment.check_sum = 1000.00
          AND payment.check_payment_link IS NULL
          AND primary_payment.status = 'CONFIRMED'
          AND primary_payment.confirmed_amount_kopecks = 100000
          AND returned_payment.status = 'REFUNDED'
          AND returned_payment.confirmed_amount_kopecks = 100000
          AND returned_payment.return_recovery_payment_check_id = 20240
          AND (
              candidate.resolution_kind = 'PRIOR_COMPLETED_REFUND'
              OR (
                  returned_payment.return_recovery_outcome = 'ACCEPTED_NOOP'
                  AND returned_payment.return_recovery_resolved_at IS NOT NULL
                  AND returned_payment.return_recovery_resolved_by =
                      'system:migration:v284'
                  AND NULLIF(TRIM(
                      returned_payment.return_recovery_resolution_reason
                  ), '') IS NOT NULL
              )
          )
          AND NULLIF(TRIM(returned_payment.tbank_terminal_key), '') IS NOT NULL
          AND RIGHT(UPPER(TRIM(returned_payment.tbank_terminal_key)), 4) <> 'DEMO'
          AND reconciliation.adjustment_kopecks = -100000
          AND reconciliation.active = 0
        FOR SHARE
    ) THEN 1
    ELSE NULL
END;

-- Lock the exact audit evidence and every currently open recipient reminder.
-- The later row-count guard rejects any reminder drift before Flyway commits.
SELECT proof.event_id
FROM business_audit_events proof
JOIN v284_resolved_return_recovery candidate
  ON candidate.prior_audit_event_id = proof.event_id
WHERE candidate.resolution_kind = 'PRIOR_COMPLETED_REFUND'
FOR SHARE;

SELECT proof.event_id
FROM business_audit_events proof
JOIN v284_resolved_archived_return_recovery candidate
  ON proof.entity_id COLLATE utf8mb4_unicode_ci =
     CAST(candidate.payment_link_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci
 AND proof.order_id = candidate.order_id
WHERE proof.actor = 'system:migration:v283'
  AND proof.source = 'FLYWAY'
  AND proof.action = 'ARCHIVED_PAYMENT_RETURN_RECONCILIATION_REQUIRED'
  AND proof.entity_type = 'PAYMENT_LINK'
  AND proof.old_value = 'UNATTRIBUTED_ARCHIVED_FULL_RETURN'
  AND proof.new_value = 'MANUAL_RECONCILIATION'
FOR SHARE;

SELECT reminder.personal_reminder_id
FROM personal_reminders reminder
JOIN v284_resolved_return_recovery candidate
  ON candidate.payment_link_id = reminder.source_id
 AND candidate.order_id = reminder.source_order_id
WHERE reminder.source_type = 'PAYMENT_RETURN_RECONCILIATION'
  AND reminder.completed_at IS NULL
FOR UPDATE;

CREATE TEMPORARY TABLE v284_update_guard (
    ok TINYINT NOT NULL
) ENGINE=InnoDB;

-- Write the durable explanation before changing the recovery marker. Every
-- following statement is idempotently restricted to the captured link ids.
INSERT INTO business_audit_events (
    created_at,
    actor,
    source,
    action,
    entity_type,
    entity_id,
    order_id,
    review_id,
    old_value,
    new_value,
    details
)
SELECT @v284_now,
       'system:migration:v284',
       'FLYWAY',
       CASE candidate.resolution_kind
           WHEN 'TEST_PAYMENT' THEN 'PAYMENT_RETURN_TEST_RECOVERY_IGNORED'
           ELSE 'PAYMENT_RETURN_MANUAL_RECONCILIATION_RESOLVED'
       END,
       'PAYMENT_LINK',
       CAST(candidate.payment_link_id AS CHAR),
       candidate.order_id,
       NULL,
       CONCAT(
           'outcome=', candidate.previous_outcome,
           '; lastError=', COALESCE(candidate.previous_error, 'NULL')
       ),
       CONCAT(
           'outcome=ACCEPTED_NOOP; resolutionKind=', candidate.resolution_kind
       ),
       CONCAT(
           candidate.resolution_reason,
           '; priorAuditEventId=', COALESCE(CAST(candidate.prior_audit_event_id AS CHAR), 'NULL'),
           '; originalProcessedAt=', candidate.previous_processed_at,
           '; originalPaymentCheckId=', COALESCE(
               CAST(candidate.previous_payment_check_id AS CHAR),
               'NULL'
           ),
           '; openRemindersCompleted=', candidate.open_reminder_count,
           '; no order, payment_check, company or reward-ledger mutation was performed'
       )
FROM v284_resolved_return_recovery candidate
WHERE NOT EXISTS (
    SELECT 1
    FROM business_audit_events existing
    WHERE (
          (
              candidate.resolution_kind = 'TEST_PAYMENT'
              AND existing.action = 'PAYMENT_RETURN_TEST_RECOVERY_IGNORED'
          )
          OR (
              candidate.resolution_kind = 'PRIOR_COMPLETED_REFUND'
              AND existing.action = 'PAYMENT_RETURN_MANUAL_RECONCILIATION_RESOLVED'
          )
      )
      AND existing.entity_type = 'PAYMENT_LINK'
      AND existing.entity_id COLLATE utf8mb4_unicode_ci =
          CAST(candidate.payment_link_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci
      AND existing.order_id = candidate.order_id
      AND existing.actor = 'system:migration:v284'
      AND existing.source = 'FLYWAY'
      AND CONVERT(existing.new_value USING utf8mb4) COLLATE utf8mb4_unicode_ci =
          CONVERT(CONCAT(
              'outcome=ACCEPTED_NOOP; resolutionKind=', candidate.resolution_kind
          ) USING utf8mb4) COLLATE utf8mb4_unicode_ci
);

INSERT INTO business_audit_events (
    created_at,
    actor,
    source,
    action,
    entity_type,
    entity_id,
    order_id,
    review_id,
    old_value,
    new_value,
    details
)
SELECT @v284_now,
       'system:migration:v284',
       'FLYWAY',
       'ARCHIVED_PAYMENT_RETURN_TEST_RECOVERY_IGNORED',
       'PAYMENT_LINK',
       CAST(candidate.payment_link_id AS CHAR),
       candidate.order_id,
       NULL,
       CONCAT(
           'outcome=', candidate.previous_outcome,
           '; lastError=', candidate.previous_error
       ),
       'outcome=ACCEPTED_NOOP; resolutionKind=ARCHIVED_TEST_PAYMENT',
       CONCAT(
           candidate.resolution_reason,
           '; originalProcessedAt=', candidate.previous_processed_at,
           '; originalPaymentCheckId=', COALESCE(
               CAST(candidate.previous_payment_check_id AS CHAR),
               'NULL'
           ),
           '; no financial mutation was attempted'
       )
FROM v284_resolved_archived_return_recovery candidate
WHERE NOT EXISTS (
    SELECT 1
    FROM business_audit_events existing
    WHERE existing.action = 'ARCHIVED_PAYMENT_RETURN_TEST_RECOVERY_IGNORED'
      AND existing.entity_type = 'PAYMENT_LINK'
      AND existing.entity_id COLLATE utf8mb4_unicode_ci =
          CAST(candidate.payment_link_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci
      AND existing.order_id = candidate.order_id
      AND existing.actor = 'system:migration:v284'
      AND existing.source = 'FLYWAY'
      AND existing.new_value =
          'outcome=ACCEPTED_NOOP; resolutionKind=ARCHIVED_TEST_PAYMENT'
);

-- Preserve the cards in reminder history while removing them from the active
-- list. There can be several recipient rows for one logical source card.
UPDATE personal_reminders reminder
JOIN v284_resolved_return_recovery candidate
  ON candidate.payment_link_id = reminder.source_id
 AND candidate.order_id = reminder.source_order_id
SET reminder.completed_at = COALESCE(reminder.completed_at, @v284_now),
    reminder.updated_at = @v284_now
WHERE reminder.source_type = 'PAYMENT_RETURN_RECONCILIATION'
  AND reminder.completed_at IS NULL;

SET @v284_completed_reminder_count = ROW_COUNT();

-- ACCEPTED_NOOP is a terminal valid marker tuple. Keep the exact check id, if
-- V283 captured one, as historical attribution evidence.
UPDATE payment_links link
JOIN v284_resolved_return_recovery candidate
  ON candidate.payment_link_id = link.id
SET link.return_recovery_outcome = 'ACCEPTED_NOOP',
    link.return_recovery_resolved_at = @v284_now,
    link.return_recovery_resolved_by = 'system:migration:v284',
    link.return_recovery_resolution_reason = candidate.resolution_reason,
    link.last_error = LEFT(CONCAT(
        'payment_return_manual_resolution_resolved: outcome=ACCEPTED_NOOP; reason=',
        candidate.resolution_reason
    ), 512),
    link.row_version = link.row_version + 1,
    link.updated_at = @v284_now
WHERE link.return_recovery_outcome = 'MANUAL_RECONCILIATION'
  AND link.row_version = candidate.previous_row_version
  AND link.return_recovery_processed_at = candidate.previous_processed_at
  AND link.return_recovery_payment_check_id <=> candidate.previous_payment_check_id
  AND link.last_error <=> candidate.previous_error
  AND link.return_recovery_resolved_at IS NULL
  AND link.return_recovery_resolved_by IS NULL
  AND link.return_recovery_resolution_reason IS NULL;

SET @v284_updated_live_link_count = ROW_COUNT();

UPDATE archive_payment_links archived_link
JOIN v284_resolved_archived_return_recovery candidate
  ON candidate.payment_link_id = archived_link.id
SET archived_link.return_recovery_outcome = 'ACCEPTED_NOOP',
    archived_link.return_recovery_resolved_at = @v284_now,
    archived_link.return_recovery_resolved_by = 'system:migration:v284',
    archived_link.return_recovery_resolution_reason = candidate.resolution_reason,
    archived_link.last_error = LEFT(CONCAT(
        'payment_return_manual_resolution_resolved: outcome=ACCEPTED_NOOP; reason=',
        candidate.resolution_reason
    ), 512),
    archived_link.updated_at = @v284_now
WHERE archived_link.return_recovery_outcome = 'MANUAL_RECONCILIATION'
  AND archived_link.status IN ('CANCELED', 'REVERSED', 'REFUNDED')
  AND archived_link.payment_method IN ('BANK_FORM', 'SBP_QR')
  AND RIGHT(UPPER(TRIM(COALESCE(archived_link.tbank_terminal_key, ''))), 4) = 'DEMO'
  AND archived_link.return_recovery_processed_at = candidate.previous_processed_at
  AND archived_link.return_recovery_payment_check_id <=> candidate.previous_payment_check_id
  AND archived_link.last_error = candidate.previous_error
  AND archived_link.return_recovery_resolved_at IS NULL
  AND archived_link.return_recovery_resolved_by IS NULL
  AND archived_link.return_recovery_resolution_reason IS NULL;

SET @v284_updated_archive_link_count = ROW_COUNT();

SET @v284_expected_live_link_count = (
    SELECT COUNT(*) FROM v284_resolved_return_recovery
);
SET @v284_expected_archive_link_count = (
    SELECT COUNT(*) FROM v284_resolved_archived_return_recovery
);
SET @v284_expected_reminder_count = COALESCE((
    SELECT SUM(candidate.open_reminder_count)
    FROM v284_resolved_return_recovery candidate
), 0);

INSERT INTO v284_update_guard (ok)
SELECT CASE
    WHEN @v284_updated_live_link_count = @v284_expected_live_link_count
    AND @v284_updated_archive_link_count = @v284_expected_archive_link_count
    AND @v284_completed_reminder_count = @v284_expected_reminder_count THEN 1
    ELSE NULL
END;

DROP TEMPORARY TABLE v284_update_guard;
DROP TEMPORARY TABLE v284_production_refund_guard;
DROP TEMPORARY TABLE v284_resolved_archived_return_recovery;
DROP TEMPORARY TABLE v284_resolved_return_recovery;
