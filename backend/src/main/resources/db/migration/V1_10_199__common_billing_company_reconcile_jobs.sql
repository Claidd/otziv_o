-- Enabling a company is committed before cross-account Order -> Account ->
-- Invoice reconciliation starts. These durable, leased fields make that
-- post-commit work crash-safe and bounded across multiple application nodes.
ALTER TABLE common_billing_account_companies
    ADD COLUMN reconcile_pending BOOLEAN NOT NULL DEFAULT FALSE AFTER enabled,
    ADD COLUMN reconcile_attempts INT NOT NULL DEFAULT 0 AFTER reconcile_pending,
    ADD COLUMN reconcile_next_attempt_at DATETIME(6) NULL AFTER reconcile_attempts,
    ADD COLUMN reconcile_lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER reconcile_next_attempt_at,
    ADD COLUMN reconcile_lease_until DATETIME(6) NULL AFTER reconcile_lease_token,
    ADD COLUMN reconcile_last_error VARCHAR(512) NULL AFTER reconcile_lease_until,
    ADD INDEX idx_common_billing_company_reconcile
        (reconcile_pending, reconcile_next_attempt_at, reconcile_lease_until, account_company_id),
    ADD CONSTRAINT ck_common_billing_company_reconcile_attempts
        CHECK (reconcile_attempts >= 0),
    ADD CONSTRAINT ck_common_billing_company_reconcile_lease
        CHECK ((reconcile_lease_token IS NULL) = (reconcile_lease_until IS NULL));

-- Existing links stay ready (DEFAULT FALSE): before this migration they were
-- reconciled synchronously. Only a new add/re-enable writes pending=TRUE in
-- the same business transaction, so rollout never pauses legacy attachments.
