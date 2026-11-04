ALTER TABLE specialist_manual_payment_reviews
    ADD COLUMN credit_attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN credit_next_attempt_at DATETIME(6) NULL,
    ADD INDEX idx_manual_payment_credit_retry (status, credit_next_attempt_at, id);

-- Reopen only the old, explicitly transient allocation-lease failure. The
-- original receipt, recipient, deadline and money remain untouched. The normal
-- workflow rechecks all bank, return, access and recipient guards before credit.
UPDATE specialist_manual_payment_reviews
SET status = 'WAITING', credit_next_attempt_at = CURRENT_TIMESTAMP(6)
WHERE status = 'NEEDS_ATTENTION'
  AND credited_at IS NULL AND settled_at IS NULL
  AND disputed_at IS NULL AND resolved_at IS NULL
  AND last_error = 'Учёт распределения занят возвратом или сверкой; повторите операцию позже';
