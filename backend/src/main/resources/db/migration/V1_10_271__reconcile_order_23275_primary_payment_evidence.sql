-- Primary evidence supplied by the client for order 23275 confirms one
-- transfer of 2,000.00 RUB for the final eight reviews. On 2026-08-13 an
-- operator correction incorrectly replaced the original 2,000.00 payment
-- check with the amount of an older 2,750.00 route. Preserve that issued-route
-- history in the audit log, but return every active financial source to the
-- evidenced 2,000.00 amount. Salary rows and company totals were already
-- correct and must not be changed.

CREATE TEMPORARY TABLE v271_preflight_guard (
    ok TINYINT NOT NULL
);

-- A clean installation has no production order 23275 and therefore needs no
-- data repair. If the order exists, accept only the audited pre-repair state
-- or the exact final state so a drift cannot be silently overwritten.
INSERT INTO v271_preflight_guard (ok)
SELECT CASE
    WHEN NOT EXISTS (
        SELECT 1 FROM orders base_order WHERE base_order.order_id = 23275
    ) THEN 1
    WHEN EXISTS (
        SELECT 1
        FROM orders base_order
        JOIN order_statuses order_status
          ON order_status.order_status_id = base_order.order_status
        JOIN payment_check payment
          ON payment.check_id = 20116
         AND payment.check_order = base_order.order_id
         AND payment.check_active = 1
        JOIN payment_links payment_link
          ON payment_link.id = 2520
         AND payment_link.order_id = base_order.order_id
        JOIN manual_payment_task_ledger_entries baseline
          ON baseline.id = 179
         AND baseline.task_id = 2
         AND baseline.source_kind = 'PAYMENT_LINK'
         AND baseline.source_id = payment_link.id
         AND baseline.source_generation = 'LEGACY-2520'
         AND baseline.event_type = 'LEGACY_BASELINE'
         AND baseline.confirmed_delta_kopecks = 275000
        WHERE base_order.order_id = 23275
          AND order_status.order_status_title = 'Оплачено'
          AND base_order.order_amount = 8
          AND base_order.order_sum = 2000.00
          AND (
              (
                  payment.check_sum = 2750.00
                  AND payment_link.status = 'AMOUNT_MISMATCH'
                  AND payment_link.amount_kopecks = 275000
                  AND payment_link.reserved_amount_kopecks = 275000
                  AND payment_link.confirmed_amount_kopecks = 275000
                  AND NOT EXISTS (
                      SELECT 1
                      FROM manual_payment_task_ledger_entries correction
                      WHERE correction.operation_key =
                          'V271:CORRECTION:PAYMENT_LINK:2520:PRIMARY-RECEIPT'
                  )
              )
              OR
              (
                  payment.check_sum = 2000.00
                  AND payment_link.status = 'CONFIRMED'
                  AND payment_link.amount_kopecks = 200000
                  AND payment_link.reserved_amount_kopecks = 200000
                  AND payment_link.confirmed_amount_kopecks = 200000
                  AND EXISTS (
                      SELECT 1
                      FROM manual_payment_task_ledger_entries correction
                      WHERE correction.operation_key =
                          'V271:CORRECTION:PAYMENT_LINK:2520:PRIMARY-RECEIPT'
                        AND correction.source_kind = 'PAYMENT_LINK'
                        AND correction.source_id = 2520
                        AND correction.event_type = 'CORRECTION'
                        AND correction.confirmed_delta_kopecks = -75000
                        AND correction.correction_of_id = 179
                  )
              )
          )
    ) THEN 1
    ELSE NULL
END;

CREATE TEMPORARY TABLE v271_order_23275_repair AS
SELECT payment.check_id,
       payment_link.id AS payment_link_id,
       baseline.id AS baseline_ledger_id
FROM orders base_order
JOIN order_statuses order_status
  ON order_status.order_status_id = base_order.order_status
JOIN payment_check payment
  ON payment.check_id = 20116
 AND payment.check_order = base_order.order_id
 AND payment.check_active = 1
JOIN payment_links payment_link
  ON payment_link.id = 2520
 AND payment_link.order_id = base_order.order_id
JOIN manual_payment_task_ledger_entries baseline
  ON baseline.id = 179
 AND baseline.task_id = 2
 AND baseline.source_kind = 'PAYMENT_LINK'
 AND baseline.source_id = payment_link.id
 AND baseline.source_generation = 'LEGACY-2520'
 AND baseline.event_type = 'LEGACY_BASELINE'
 AND baseline.confirmed_delta_kopecks = 275000
WHERE base_order.order_id = 23275
  AND order_status.order_status_title = 'Оплачено'
  AND base_order.order_amount = 8
  AND base_order.order_sum = 2000.00
  AND payment.check_sum = 2750.00
  AND payment_link.status = 'AMOUNT_MISMATCH'
  AND payment_link.amount_kopecks = 275000
  AND payment_link.reserved_amount_kopecks = 275000
  AND payment_link.confirmed_amount_kopecks = 275000
  AND NOT EXISTS (
      SELECT 1
      FROM manual_payment_task_ledger_entries correction
      WHERE correction.operation_key =
          'V271:CORRECTION:PAYMENT_LINK:2520:PRIMARY-RECEIPT'
  );

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:flyway-v271',
       'primary_payment_evidence',
       'PAYMENT_PRIMARY_EVIDENCE_RECONCILED',
       'PAYMENT_CHECK',
       CAST(repair.check_id AS CHAR),
       23275,
       '2750.00',
       '2000.00',
       'Первичный чек: перевод 2000.00 RUB за итоговые 8 отзывов; ошибочная ручная корректировка от 2026-08-13 отменена'
FROM v271_order_23275_repair repair;

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:flyway-v271',
       'primary_payment_evidence',
       'PAYMENT_LINK_PRIMARY_EVIDENCE_RECONCILED',
       'PAYMENT_LINK',
       CAST(repair.payment_link_id AS CHAR),
       23275,
       'status=AMOUNT_MISMATCH;issued=275000;confirmed=275000',
       'status=CONFIRMED;active=200000;confirmed=200000',
       'Старый маршрут на 2750.00 RUB сохранён в аудите; активная сумма приведена к первичному чеку на 2000.00 RUB'
FROM v271_order_23275_repair repair;

INSERT INTO manual_payment_task_ledger_entries (
    task_id, task_generation, source_kind, source_id, source_generation,
    event_type, operation_key, operation_sequence, reservation_key,
    reserved_delta_kopecks, confirmed_delta_kopecks, redirected_amount_kopecks,
    accounting_target_kind, accounting_target_profile_id,
    accounting_target_label_snapshot, manual_payment_type,
    manual_phone_snapshot, bank_recipient_name_snapshot,
    manual_bank_name_snapshot, manual_payment_url_snapshot,
    manual_payment_button_snapshot, selected_recipient_key,
    target_overrun_acknowledged_at, target_overrun_acknowledged_by,
    verified, actor, reason, correction_of_id, created_at
)
SELECT baseline.task_id,
       baseline.task_generation,
       baseline.source_kind,
       baseline.source_id,
       baseline.source_generation,
       'CORRECTION',
       'V271:CORRECTION:PAYMENT_LINK:2520:PRIMARY-RECEIPT',
       0,
       NULL,
       0,
       -75000,
       0,
       baseline.accounting_target_kind,
       baseline.accounting_target_profile_id,
       baseline.accounting_target_label_snapshot,
       baseline.manual_payment_type,
       baseline.manual_phone_snapshot,
       baseline.bank_recipient_name_snapshot,
       baseline.manual_bank_name_snapshot,
       baseline.manual_payment_url_snapshot,
       baseline.manual_payment_button_snapshot,
       baseline.selected_recipient_key,
       baseline.target_overrun_acknowledged_at,
       baseline.target_overrun_acknowledged_by,
       TRUE,
       'system:flyway-v271',
       'Коррекция по первичному чеку заказа 23275: фактически получено 2000.00 RUB вместо ошибочно учтённых 2750.00 RUB',
       baseline.id,
       CURRENT_TIMESTAMP(6)
FROM manual_payment_task_ledger_entries baseline
JOIN v271_order_23275_repair repair
  ON repair.baseline_ledger_id = baseline.id;

UPDATE payment_check payment
JOIN v271_order_23275_repair repair
  ON repair.check_id = payment.check_id
SET payment.check_sum = 2000.00;

UPDATE payment_links payment_link
JOIN v271_order_23275_repair repair
  ON repair.payment_link_id = payment_link.id
SET payment_link.amount_kopecks = 200000,
    payment_link.reserved_amount_kopecks = 200000,
    payment_link.confirmed_amount_kopecks = 200000,
    payment_link.status = 'CONFIRMED',
    payment_link.last_error = NULL,
    payment_link.row_version = payment_link.row_version + 1,
    payment_link.updated_at = CURRENT_TIMESTAMP(6);

-- The mismatch card is resolved, but remains visible in reminder history.
UPDATE personal_reminders reminder
JOIN v271_order_23275_repair repair
  ON reminder.source_order_id = 23275
SET reminder.completed_at = COALESCE(reminder.completed_at, CURRENT_TIMESTAMP(6)),
    reminder.updated_at = CURRENT_TIMESTAMP(6)
WHERE reminder.source_type IN (
    'PAYMENT_ACCOUNTING_MISMATCH',
    'PAYMENT_ORDER_FACT_MISMATCH'
);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
SELECT 'financial-integrity.v268-analytics-rebuild-pending',
       'true',
       CURRENT_TIMESTAMP(6)
FROM v271_order_23275_repair
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = VALUES(updated_at);

DROP TEMPORARY TABLE v271_order_23275_repair;
DROP TEMPORARY TABLE v271_preflight_guard;
