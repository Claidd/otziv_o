-- A lease prevents concurrent execution; this ledger also prevents a completed
-- business slot from replaying after lease release or process restart.
CREATE TABLE scheduler_job_runs (
    scheduler_job_run_id BIGINT NOT NULL AUTO_INCREMENT,
    job_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fencing_token BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'STARTED',
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    last_error VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (scheduler_job_run_id),
    UNIQUE KEY uk_scheduler_job_runs_job_run_key (job_name, run_key),
    INDEX idx_scheduler_job_runs_recovery
        (status, started_at, scheduler_job_run_id),
    CONSTRAINT ck_scheduler_job_runs_fencing_token
        CHECK (fencing_token > 0),
    CONSTRAINT ck_scheduler_job_runs_status
        CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_scheduler_job_runs_completion
        CHECK (
            (status = 'STARTED' AND completed_at IS NULL)
            OR (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED') AND completed_at IS NOT NULL)
        )
) ENGINE=InnoDB;
