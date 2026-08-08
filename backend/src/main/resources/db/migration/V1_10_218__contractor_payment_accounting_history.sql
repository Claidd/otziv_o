-- V217 prototyped a FIFO allocation→ledger application table, but the
-- implemented accounting model uses immutable allocation events and aggregate
-- contractor obligations instead. Remove the unused table so the schema does
-- not promise unsupported one-to-one source consumption semantics.
DROP TABLE contractor_reward_applications;

ALTER TABLE contractor_payment_allocations
    DROP FOREIGN KEY fk_contractor_payment_allocations_order,
    DROP FOREIGN KEY fk_contractor_payment_allocations_common_invoice,
    DROP INDEX uk_contractor_payment_allocations_source,
    ADD COLUMN attempt_no INT NOT NULL DEFAULT 1 AFTER source_id,
    ADD COLUMN confirmed_kopecks BIGINT NOT NULL DEFAULT 0 AFTER amount_kopecks,
    ADD COLUMN returned_kopecks BIGINT NOT NULL DEFAULT 0 AFTER confirmed_kopecks,
    ADD COLUMN needs_return_amount BOOLEAN NOT NULL DEFAULT FALSE AFTER returned_kopecks,
    ADD COLUMN source_paid_baseline_kopecks BIGINT NOT NULL DEFAULT 0 AFTER needs_return_amount,
    ADD COLUMN last_reconciled_at DATETIME(6) NULL AFTER source_paid_baseline_kopecks,
    ADD COLUMN reconcile_claim_token VARCHAR(36) NULL AFTER last_reconciled_at,
    ADD COLUMN reconcile_lease_until DATETIME(6) NULL AFTER reconcile_claim_token,
    ADD COLUMN reconcile_attempts INT NOT NULL DEFAULT 0 AFTER reconcile_lease_until,
    ADD COLUMN reconcile_next_retry_at DATETIME(6) NULL AFTER reconcile_attempts,
    ADD COLUMN reconcile_last_error_code VARCHAR(120) NULL AFTER reconcile_next_retry_at,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0 AFTER reconcile_last_error_code,
    ADD CONSTRAINT uk_contractor_allocations_source_attempt
        UNIQUE (mode, source_type, source_id, attempt_no),
    ADD INDEX idx_contractor_allocations_mode (mode, id),
    ADD INDEX idx_contractor_allocations_reconcile
        (mode, source_type, status, reconcile_next_retry_at, reconcile_lease_until, last_reconciled_at, id);

UPDATE contractor_payment_allocations
SET confirmed_kopecks = CASE
        WHEN status IN ('CONFIRMED', 'SIMULATED_PAID', 'LATE_PAYMENT_AFTER_RELEASE', 'RETURNED')
            THEN amount_kopecks
        ELSE 0
    END,
    returned_kopecks = CASE WHEN status = 'RETURNED' THEN amount_kopecks ELSE 0 END;

UPDATE contractor_payment_allocations allocation
JOIN common_invoices invoice ON invoice.invoice_id = allocation.common_invoice_id
SET allocation.source_paid_baseline_kopecks = GREATEST(
        0,
        invoice.amount_kopecks - allocation.amount_kopecks
    )
WHERE allocation.source_type = 'COMMON_INVOICE';

CREATE TABLE contractor_payment_allocation_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    allocation_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    amount_kopecks BIGINT NOT NULL DEFAULT 0,
    status_before VARCHAR(32) NULL,
    status_after VARCHAR(32) NULL,
    effective_at DATETIME(6) NOT NULL,
    observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reason VARCHAR(255) NULL,
    external_ref VARCHAR(160) NOT NULL,
    actor VARCHAR(150) NOT NULL DEFAULT 'system',
    CONSTRAINT uk_contractor_allocation_event_ref UNIQUE (allocation_id, external_ref),
    CONSTRAINT fk_contractor_allocation_event_allocation
        FOREIGN KEY (allocation_id) REFERENCES contractor_payment_allocations (id),
    INDEX idx_contractor_allocation_event_profile_stats (event_type, effective_at, allocation_id),
    INDEX idx_contractor_allocation_event_allocation_time (allocation_id, effective_at, id)
) ENGINE=InnoDB;

INSERT INTO contractor_payment_allocation_events (
    allocation_id, event_type, amount_kopecks, status_after, effective_at, observed_at, reason, external_ref
)
SELECT id,
       CASE
           WHEN status = 'CLIENT_REPORTED' THEN 'CLIENT_REPORTED'
           WHEN status IN ('CONFIRMED', 'SIMULATED_PAID', 'LATE_PAYMENT_AFTER_RELEASE', 'RETURNED')
               THEN CASE WHEN mode = 'SHADOW' THEN 'SIMULATED_CONFIRMED' ELSE 'CONFIRMED' END
           WHEN status = 'EXPIRED' THEN 'EXPIRED'
           WHEN status = 'CANCELED' THEN 'CANCELED'
           WHEN status = 'RELEASED_UNPAID' THEN 'RELEASED'
           WHEN status = 'OWNER_FALLBACK' THEN 'OWNER_FALLBACK'
           ELSE 'RESERVED'
       END,
       CASE
           WHEN status IN ('CONFIRMED', 'SIMULATED_PAID', 'LATE_PAYMENT_AFTER_RELEASE', 'RETURNED')
               THEN amount_kopecks
           WHEN status IN ('CLIENT_REPORTED', 'EXPIRED', 'CANCELED', 'RELEASED_UNPAID', 'RESERVED')
               THEN amount_kopecks
           ELSE 0
       END,
       CASE
           WHEN status = 'RETURNED' AND mode = 'SHADOW' THEN 'SIMULATED_PAID'
           WHEN status = 'RETURNED' AND mode = 'LIVE' THEN 'CONFIRMED'
           ELSE status
       END,
       COALESCE(confirmed_at, client_reported_at, released_at, reserved_at, created_at),
       updated_at,
       release_reason,
       'MIGRATION:CURRENT_STATE'
FROM contractor_payment_allocations;

INSERT INTO contractor_payment_allocation_events (
    allocation_id, event_type, amount_kopecks, status_before, status_after,
    effective_at, observed_at, reason, external_ref
)
SELECT id, 'RETURNED', amount_kopecks,
       CASE WHEN mode = 'SHADOW' THEN 'SIMULATED_PAID' ELSE 'CONFIRMED' END,
       'RETURNED',
       COALESCE(released_at, updated_at), updated_at, release_reason, 'MIGRATION:RETURNED'
FROM contractor_payment_allocations
WHERE status = 'RETURNED';

ALTER TABLE contractor_payment_profiles
    ADD COLUMN ledger_sync_zp_id BIGINT NOT NULL DEFAULT 0 AFTER tracking_start_zp_id,
    ADD COLUMN ledger_sync_at DATETIME(6) NULL AFTER ledger_sync_zp_id;

UPDATE contractor_payment_profiles
SET ledger_sync_zp_id = tracking_start_zp_id,
    ledger_sync_at = tracking_started_at;

-- Profession rows are permanent accounting identities even if a security
-- role was removed before cutover. New historical anchors use the same
-- cutover watermark as V217 profiles and remain disabled by default.
INSERT IGNORE INTO contractor_payment_profiles (
    user_id, contractor_role, enabled, tracking_started_at,
    tracking_start_zp_id, ledger_sync_zp_id, ledger_sync_at
)
SELECT DISTINCT w.user_id, 'SPECIALIST', FALSE, CURRENT_TIMESTAMP(6),
       watermark.max_zp_id, watermark.max_zp_id, CURRENT_TIMESTAMP(6)
FROM workers w
CROSS JOIN (SELECT COALESCE(MAX(zp_id), 0) AS max_zp_id FROM zp) watermark
WHERE w.user_id IS NOT NULL;

INSERT IGNORE INTO contractor_payment_profiles (
    user_id, contractor_role, enabled, tracking_started_at,
    tracking_start_zp_id, ledger_sync_zp_id, ledger_sync_at
)
SELECT DISTINCT m.user_id, 'MANAGER', FALSE, CURRENT_TIMESTAMP(6),
       watermark.max_zp_id, watermark.max_zp_id, CURRENT_TIMESTAMP(6)
FROM managers m
CROSS JOIN (SELECT COALESCE(MAX(zp_id), 0) AS max_zp_id FROM zp) watermark
WHERE m.user_id IS NOT NULL;

CREATE TABLE contractor_payment_profile_adjustments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    profile_id BIGINT NOT NULL,
    old_balance_kopecks BIGINT NOT NULL,
    new_balance_kopecks BIGINT NOT NULL,
    delta_kopecks BIGINT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    changed_by VARCHAR(160) NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_contractor_profile_adjustment_profile
        FOREIGN KEY (profile_id) REFERENCES contractor_payment_profiles (id),
    INDEX idx_contractor_profile_adjustment_profile_time (profile_id, effective_at, id)
) ENGINE=InnoDB;

INSERT INTO contractor_payment_profile_adjustments (
    profile_id, old_balance_kopecks, new_balance_kopecks, delta_kopecks,
    reason, changed_by, effective_at
)
SELECT id, 0, opening_balance_kopecks, opening_balance_kopecks,
       'Начальный остаток до включения журнала изменений', 'MIGRATION',
       COALESCE(updated_at, created_at)
FROM contractor_payment_profiles
WHERE opening_balance_kopecks <> 0;

ALTER TABLE contractor_reward_ledger
    DROP FOREIGN KEY fk_contractor_reward_ledger_zp,
    DROP INDEX uk_contractor_reward_ledger_zp,
    ADD COLUMN attributed_worker_id BIGINT NULL AFTER source_zp_id,
    ADD COLUMN attribution_key BIGINT NOT NULL DEFAULT 0 AFTER attributed_worker_id,
    ADD CONSTRAINT uk_contractor_reward_ledger_attribution
        UNIQUE (source_zp_id, profile_id, attribution_key),
    ADD INDEX idx_contractor_reward_ledger_source (source_zp_id);

CREATE TABLE contractor_reward_sync_markers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_zp_id BIGINT NOT NULL,
    source_active BOOLEAN NOT NULL,
    source_updated_at DATETIME(6) NULL,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_contractor_reward_sync_marker UNIQUE (source_zp_id)
) ENGINE=InnoDB;

CREATE TABLE contractor_reward_repair_claims (
    source_zp_id BIGINT PRIMARY KEY,
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    retry_attempts INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    last_error_code VARCHAR(120) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_contractor_reward_repair_due (next_retry_at, lease_until, source_zp_id)
) ENGINE=InnoDB;

CREATE TABLE contractor_shadow_backfill_claims (
    claim_key VARCHAR(96) PRIMARY KEY,
    queue_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    retry_attempts INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    last_error_code VARCHAR(120) NULL,
    completed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_contractor_shadow_backfill_due
        (completed_at, next_retry_at, lease_until, queue_type, source_id)
) ENGINE=InnoDB;

ALTER TABLE zp
    ADD COLUMN zp_attribution_final BOOLEAN NOT NULL DEFAULT FALSE AFTER zp_contractor_role,
    ADD COLUMN zp_reward_basis DECIMAL(19,2) NULL AFTER zp_attribution_final,
    ADD COLUMN zp_attribution_snapshot TEXT NULL AFTER zp_reward_basis,
    ADD COLUMN zp_updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) AFTER zp_date,
    ADD INDEX idx_zp_contractor_updated (zp_contractor_role, zp_updated_at, zp_id);

ALTER TABLE archive_zp
    ADD COLUMN zp_source VARCHAR(64) NULL,
    ADD COLUMN zp_contractor_role VARCHAR(24) NULL,
    ADD COLUMN zp_attribution_final BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN zp_reward_basis DECIMAL(19,2) NULL,
    ADD COLUMN zp_attribution_snapshot TEXT NULL,
    ADD COLUMN zp_updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

INSERT IGNORE INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('contractor-payments.reward-attribution-live-enabled', 'false', CURRENT_TIMESTAMP(6));
