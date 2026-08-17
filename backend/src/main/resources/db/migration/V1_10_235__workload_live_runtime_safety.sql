-- Runtime fail-closed protection for workload CANARY/LIVE.
-- The migration is deliberately non-activating: existing live settings remain unchanged.

ALTER TABLE workload_shadow_runs
    ADD COLUMN settings_revision BIGINT NULL AFTER instance_id,
    ADD INDEX idx_workload_shadow_runs_revision_status_finished (
        settings_revision,
        status,
        finished_at
    );

ALTER TABLE workload_shadow_worker_daily
    ADD COLUMN settings_revision BIGINT NULL AFTER manager_id,
    ADD INDEX idx_workload_shadow_daily_revision_finalized (
        settings_revision,
        finalized,
        progress_date
    );

ALTER TABLE workload_transfer_workflows
    ADD COLUMN live_settings_revision BIGINT NOT NULL DEFAULT 0 AFTER mode,
    ADD COLUMN shadow_settings_revision BIGINT NOT NULL DEFAULT 0
        AFTER live_settings_revision;

-- A single InnoDB row per business day serializes quota reservation across
-- application replicas. The authoritative usage remains the workflow and
-- emergency-assignment journals, so a rolled-back transaction never leaks quota.
CREATE TABLE workload_live_daily_quota_locks (
    decision_date DATE NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (decision_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
