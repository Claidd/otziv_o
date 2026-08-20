SET NAMES utf8mb4;

SET @repair_now = NOW(6);
SET @repair_slot = TIMESTAMP('2026-08-20 14:00:00.000000');
SET @unsafe_in_progress = 'state_transaction_in_progress';
SET @unsafe_uncertain = 'state_transaction_outcome_uncertain';

CREATE TABLE IF NOT EXISTS codex_client_message_state_backup_20260820
LIKE scheduled_client_message_state;

DROP TEMPORARY TABLE IF EXISTS codex_repair_order_candidates;
CREATE TEMPORARY TABLE codex_repair_order_candidates AS
SELECT
  o.order_id,
  o.order_company AS company_id,
  os.order_status_title,
  o.order_status_changed_at,
  CONCAT(
    'order:',
    o.order_id,
    ':',
    CASE
      WHEN SECOND(o.order_status_changed_at) = 0
        THEN DATE_FORMAT(o.order_status_changed_at, '%Y-%m-%dT%H:%i')
      ELSE DATE_FORMAT(o.order_status_changed_at, '%Y-%m-%dT%H:%i:%s')
    END
  ) AS target_key
FROM orders o
JOIN order_statuses os ON os.order_status_id = o.order_status
WHERE o.order_complete = b'0'
  AND o.order_status_changed_at IS NOT NULL
  AND os.order_status_title IN ('Опубликовано', 'Выставлен счет', 'Напоминание')
  AND NOT EXISTS (
    SELECT 1
    FROM common_invoice_orders cio
    JOIN common_invoices ci ON ci.invoice_id = cio.invoice_id
    WHERE cio.order_id = o.order_id
      AND cio.active_membership = 1
      AND ci.status IN ('COLLECTING', 'READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID', 'NEEDS_ATTENTION')
  );

START TRANSACTION;

INSERT IGNORE INTO codex_client_message_state_backup_20260820
SELECT s.*
FROM scheduled_client_message_state s
LEFT JOIN orders o ON o.order_id = s.order_id
LEFT JOIN order_statuses os ON os.order_status_id = o.order_status
WHERE s.scenario IN ('PAYMENT_INVOICE_RETRY', 'PAYMENT_REMINDER', 'PAYMENT_OVERDUE_ESCALATION')
  AND (
    s.order_id IN (SELECT order_id FROM codex_repair_order_candidates)
    OR (
      s.state_status = 'ACTIVE'
      AND os.order_status_title IN ('Оплачено', 'Архив', 'Бан', 'Не оплачено')
    )
  );

SELECT 'backup_rows', ROW_COUNT();

INSERT INTO scheduled_client_message_state (
  scenario,
  target_type,
  target_key,
  company_id,
  order_id,
  archive_order_id,
  state_status,
  next_attempt_at,
  consecutive_failures,
  sent_count,
  created_at,
  updated_at
)
SELECT
  'PAYMENT_INVOICE_RETRY',
  'ORDER',
  candidate.target_key,
  candidate.company_id,
  candidate.order_id,
  NULL,
  'ACTIVE',
  GREATEST(DATE_ADD(candidate.order_status_changed_at, INTERVAL 2 HOUR), @repair_slot),
  0,
  0,
  @repair_now,
  @repair_now
FROM codex_repair_order_candidates candidate
WHERE candidate.order_status_title = 'Опубликовано'
  AND NOT EXISTS (
    SELECT 1
    FROM scheduled_client_message_state active_state
    WHERE active_state.scenario = 'PAYMENT_INVOICE_RETRY'
      AND active_state.target_key = candidate.target_key
      AND active_state.state_status = 'ACTIVE'
  )
ON DUPLICATE KEY UPDATE
  state_status = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN 'ACTIVE'
    ELSE scheduled_client_message_state.state_status
  END,
  next_attempt_at = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN VALUES(next_attempt_at)
    ELSE scheduled_client_message_state.next_attempt_at
  END,
  locked_until = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN NULL
    ELSE scheduled_client_message_state.locked_until
  END,
  last_error_code = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN NULL
    ELSE scheduled_client_message_state.last_error_code
  END,
  last_error_message = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN NULL
    ELSE scheduled_client_message_state.last_error_message
  END,
  consecutive_failures = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN 0
    ELSE scheduled_client_message_state.consecutive_failures
  END,
  company_id = VALUES(company_id),
  order_id = VALUES(order_id),
  updated_at = @repair_now;

SELECT 'invoice_rows', ROW_COUNT();

INSERT INTO scheduled_client_message_state (
  scenario,
  target_type,
  target_key,
  company_id,
  order_id,
  archive_order_id,
  state_status,
  next_attempt_at,
  consecutive_failures,
  sent_count,
  created_at,
  updated_at
)
SELECT
  'PAYMENT_REMINDER',
  'ORDER',
  candidate.target_key,
  candidate.company_id,
  candidate.order_id,
  NULL,
  'ACTIVE',
  GREATEST(DATE_ADD(candidate.order_status_changed_at, INTERVAL 2 DAY), @repair_slot),
  0,
  0,
  @repair_now,
  @repair_now
FROM codex_repair_order_candidates candidate
WHERE candidate.order_status_title IN ('Выставлен счет', 'Напоминание')
  AND NOT EXISTS (
    SELECT 1
    FROM scheduled_client_message_state active_state
    WHERE active_state.scenario = 'PAYMENT_REMINDER'
      AND active_state.target_key = candidate.target_key
      AND active_state.state_status = 'ACTIVE'
  )
ON DUPLICATE KEY UPDATE
  state_status = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN 'ACTIVE'
    ELSE scheduled_client_message_state.state_status
  END,
  next_attempt_at = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN VALUES(next_attempt_at)
    ELSE scheduled_client_message_state.next_attempt_at
  END,
  locked_until = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN NULL
    ELSE scheduled_client_message_state.locked_until
  END,
  last_error_code = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN NULL
    ELSE scheduled_client_message_state.last_error_code
  END,
  last_error_message = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN NULL
    ELSE scheduled_client_message_state.last_error_message
  END,
  consecutive_failures = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN 0
    ELSE scheduled_client_message_state.consecutive_failures
  END,
  company_id = VALUES(company_id),
  order_id = VALUES(order_id),
  updated_at = @repair_now;

SELECT 'reminder_rows', ROW_COUNT();

INSERT INTO scheduled_client_message_state (
  scenario,
  target_type,
  target_key,
  company_id,
  order_id,
  archive_order_id,
  state_status,
  next_attempt_at,
  consecutive_failures,
  sent_count,
  created_at,
  updated_at
)
SELECT
  'PAYMENT_OVERDUE_ESCALATION',
  'ORDER',
  candidate.target_key,
  candidate.company_id,
  candidate.order_id,
  NULL,
  'ACTIVE',
  GREATEST(DATE_ADD(candidate.order_status_changed_at, INTERVAL 30 DAY), @repair_slot),
  0,
  0,
  @repair_now,
  @repair_now
FROM codex_repair_order_candidates candidate
WHERE candidate.order_status_title IN ('Выставлен счет', 'Напоминание')
  AND NOT EXISTS (
    SELECT 1
    FROM scheduled_client_message_state active_state
    WHERE active_state.scenario = 'PAYMENT_OVERDUE_ESCALATION'
      AND active_state.target_key = candidate.target_key
      AND active_state.state_status = 'ACTIVE'
  )
ON DUPLICATE KEY UPDATE
  state_status = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN 'ACTIVE'
    ELSE scheduled_client_message_state.state_status
  END,
  next_attempt_at = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN VALUES(next_attempt_at)
    ELSE scheduled_client_message_state.next_attempt_at
  END,
  locked_until = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN NULL
    ELSE scheduled_client_message_state.locked_until
  END,
  last_error_code = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN NULL
    ELSE scheduled_client_message_state.last_error_code
  END,
  last_error_message = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN NULL
    ELSE scheduled_client_message_state.last_error_message
  END,
  consecutive_failures = CASE
    WHEN scheduled_client_message_state.state_status = 'DONE'
      AND scheduled_client_message_state.sent_count = 0
      AND scheduled_client_message_state.last_success_at IS NULL
      AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (@unsafe_in_progress, @unsafe_uncertain)
      THEN 0
    ELSE scheduled_client_message_state.consecutive_failures
  END,
  company_id = VALUES(company_id),
  order_id = VALUES(order_id),
  updated_at = @repair_now;

SELECT 'overdue_rows', ROW_COUNT();

INSERT INTO scheduled_client_message_attempts (
  state_id,
  scenario,
  target_type,
  target_key,
  company_id,
  order_id,
  archive_order_id,
  attempt_status,
  channel,
  error_code,
  error_message,
  message_preview,
  duration_ms,
  attempted_at
)
SELECT
  s.state_id,
  s.scenario,
  s.target_type,
  s.target_key,
  s.company_id,
  s.order_id,
  s.archive_order_id,
  'SKIPPED',
  NULL,
  'order_closed',
  'Платежная задача закрыта сверкой: заказ уже не требует платежной авторассылки',
  'Платежная задача закрыта сверкой: заказ уже не требует платежной авторассылки',
  0,
  @repair_now
FROM scheduled_client_message_state s
JOIN orders o ON o.order_id = s.order_id
JOIN order_statuses os ON os.order_status_id = o.order_status
WHERE s.state_status = 'ACTIVE'
  AND s.scenario IN ('PAYMENT_INVOICE_RETRY', 'PAYMENT_REMINDER', 'PAYMENT_OVERDUE_ESCALATION')
  AND os.order_status_title IN ('Оплачено', 'Архив', 'Бан', 'Не оплачено');

SELECT 'closed_attempt_rows', ROW_COUNT();

UPDATE scheduled_client_message_state s
JOIN orders o ON o.order_id = s.order_id
JOIN order_statuses os ON os.order_status_id = o.order_status
SET
  s.state_status = 'DONE',
  s.last_attempt_at = @repair_now,
  s.last_error_code = NULL,
  s.last_error_message = NULL,
  s.consecutive_failures = 0,
  s.next_attempt_at = NULL,
  s.locked_until = NULL,
  s.updated_at = @repair_now
WHERE s.state_status = 'ACTIVE'
  AND s.scenario IN ('PAYMENT_INVOICE_RETRY', 'PAYMENT_REMINDER', 'PAYMENT_OVERDUE_ESCALATION')
  AND os.order_status_title IN ('Оплачено', 'Архив', 'Бан', 'Не оплачено');

SELECT 'closed_state_rows', ROW_COUNT();

COMMIT;
