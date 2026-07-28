-- Follow-up for workload shadow mode.
-- V1_10_144 and V1_10_145 are already applied in production and must remain immutable.

ALTER TABLE bad_review_tasks
    ADD COLUMN bad_review_task_created_at DATETIME(6) NULL;

ALTER TABLE archive_bad_review_tasks
    ADD COLUMN bad_review_task_created_at DATETIME(6) NULL;

ALTER TABLE reviews
    ADD COLUMN review_created_at DATETIME(6) NULL,
    ADD COLUMN review_vigul_changed_at DATETIME(6) NULL,
    ADD COLUMN review_text_ready_at DATETIME(6) NULL,
    ADD COLUMN review_text_ready_worker_id BIGINT NULL,
    ADD INDEX idx_reviews_worker_publish_marked (
        review_worker,
        review_publish,
        review_published_marked_at,
        review_id
    ),
    ADD INDEX idx_reviews_worker_text_ready (
        review_text_ready_worker_id,
        review_text_ready_at
    );

-- Existing rows are not rewritten. Shadow queries use legacy review/order
-- timestamps as a read-time fallback, while new transitions are captured exactly.
ALTER TABLE archive_reviews
    ADD COLUMN review_created_at DATETIME(6) NULL,
    ADD COLUMN review_vigul_changed_at DATETIME(6) NULL,
    ADD COLUMN review_text_ready_at DATETIME(6) NULL,
    ADD COLUMN review_text_ready_worker_id BIGINT NULL;

ALTER TABLE business_audit_events
    ADD INDEX idx_business_audit_action_order_created (
        action,
        order_id,
        created_at
    ),
    ADD INDEX idx_business_audit_action_review_created (
        action,
        review_id,
        created_at
    );

ALTER TABLE worker_activity_events
    ADD INDEX idx_worker_activity_review_action_created (
        review_id,
        action,
        created_at
    );

CREATE TABLE IF NOT EXISTS workload_shadow_recalculation_locks (
    lock_name VARCHAR(80) NOT NULL,
    owner_instance_id VARCHAR(120) NULL,
    owner_token CHAR(36) NULL,
    run_id BIGINT NULL,
    acquired_at DATETIME(6) NULL,
    renewed_at DATETIME(6) NULL,
    lease_until DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    takeover_count BIGINT NOT NULL DEFAULT 0,
    last_recovered_at DATETIME(6) NULL,
    last_released_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (lock_name),
    UNIQUE KEY uk_workload_shadow_recalculation_owner_token (owner_token),
    INDEX idx_workload_shadow_recalculation_lease (lease_until),
    CONSTRAINT fk_workload_shadow_recalculation_run
        FOREIGN KEY (run_id) REFERENCES workload_shadow_runs (workload_shadow_run_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO workload_shadow_recalculation_locks (
    lock_name,
    lease_until
) VALUES (
    'GLOBAL_RECALCULATION',
    '1970-01-01 00:00:00.000000'
);

ALTER TABLE workload_shadow_worker_current
    ADD COLUMN planned_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN incoming_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN urgent_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN external_blocked_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN client_deferred_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN manager_deferred_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_day_reached_100 BIT NOT NULL DEFAULT 0;

ALTER TABLE workload_shadow_worker_daily
    ADD COLUMN planned_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN incoming_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN urgent_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN external_blocked_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN client_deferred_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN manager_deferred_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN finalization_status VARCHAR(32) NOT NULL DEFAULT 'LIVE';

CREATE TABLE IF NOT EXISTS workload_shadow_late_batches (
    progress_date DATE NOT NULL,
    worker_id BIGINT NOT NULL,
    batch_key VARCHAR(190) NOT NULL,
    section_code VARCHAR(32) NOT NULL,
    initial_units BIGINT NOT NULL DEFAULT 0,
    remaining_units BIGINT NOT NULL DEFAULT 0,
    initial_estimated_minutes BIGINT NOT NULL DEFAULT 0,
    remaining_estimated_minutes BIGINT NOT NULL DEFAULT 0,
    first_detected_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    active BIT NOT NULL DEFAULT 1,
    PRIMARY KEY (progress_date, worker_id, batch_key),
    INDEX idx_workload_shadow_late_worker_date (worker_id, progress_date, active),
    INDEX idx_workload_shadow_late_seen (last_seen_at),
    CONSTRAINT fk_workload_shadow_late_worker
        FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE workload_shadow_transfer_cases
    ADD COLUMN graph_warning_count INT NOT NULL DEFAULT 0,
    ADD COLUMN graph_error_count INT NOT NULL DEFAULT 0,
    ADD COLUMN graph_warning_codes VARCHAR(1000) NOT NULL DEFAULT '',
    ADD COLUMN graph_error_codes VARCHAR(1000) NOT NULL DEFAULT '',
    ADD INDEX idx_workload_shadow_transfer_graph_health (
        active,
        graph_error_count,
        graph_warning_count
    ),
    ADD INDEX idx_workload_shadow_transfer_retention (
        active,
        resolved_at,
        workload_shadow_transfer_case_id
    );

ALTER TABLE workload_shadow_events
    DROP INDEX idx_workload_shadow_event_delivery,
    DROP INDEX idx_workload_shadow_event_processing,
    ADD INDEX idx_workload_shadow_event_due (
        active,
        delivery_status,
        next_attempt_at,
        first_seen_at,
        workload_shadow_event_id
    ),
    ADD INDEX idx_workload_shadow_event_retention (
        active,
        delivery_status,
        last_seen_at,
        workload_shadow_event_id
    ),
    ADD INDEX idx_workload_shadow_event_monitor (
        active,
        last_seen_at,
        workload_shadow_event_id
    ),
    ADD INDEX idx_workload_shadow_event_processing (
        active,
        delivery_status,
        processing_lease_until,
        workload_shadow_event_id
    );
