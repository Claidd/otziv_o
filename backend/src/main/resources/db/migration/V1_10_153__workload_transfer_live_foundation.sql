-- Safe foundation for the future workload transfer live workflow.
-- The migration does not enable offers or mutate current assignments.

CREATE TABLE workload_transfer_workflows (
    workload_transfer_workflow_id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_key CHAR(36) NOT NULL,
    shadow_case_id BIGINT NULL,
    manager_id BIGINT NOT NULL,
    source_worker_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    company_title VARCHAR(500) NULL,
    failure_number INT NOT NULL,
    transfer_percent INT NOT NULL,
    selection_rank INT NOT NULL,
    problem_units BIGINT NOT NULL DEFAULT 0,
    estimated_minutes BIGINT NOT NULL DEFAULT 0,
    active_order_count BIGINT NOT NULL DEFAULT 0,
    new_unit_count BIGINT NOT NULL DEFAULT 0,
    correction_count BIGINT NOT NULL DEFAULT 0,
    nagul_count BIGINT NOT NULL DEFAULT 0,
    publish_count BIGINT NOT NULL DEFAULT 0,
    recovery_count BIGINT NOT NULL DEFAULT 0,
    bad_count BIGINT NOT NULL DEFAULT 0,
    graph_fingerprint CHAR(64) NOT NULL,
    graph_json LONGTEXT NOT NULL,
    mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'READY_TO_OFFER',
    workflow_version BIGINT NOT NULL DEFAULT 0,
    current_offer_id BIGINT NULL,
    accepted_worker_id BIGINT NULL,
    owner_confirmation_required BIT NOT NULL DEFAULT 0,
    owner_confirmed_at DATETIME(6) NULL,
    last_error_code VARCHAR(80) NULL,
    last_error_message VARCHAR(1000) NULL,
    decision_date DATE NOT NULL,
    active BIT NOT NULL DEFAULT 1,
    active_slot VARCHAR(96)
        GENERATED ALWAYS AS (
            CASE
                WHEN active = TRUE
                THEN CONCAT(source_worker_id, ':', company_id)
                ELSE NULL
            END
        ) STORED,
    last_transition_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workload_transfer_workflow_id),
    UNIQUE KEY uk_workload_transfer_workflow_key (workflow_key),
    UNIQUE KEY uk_workload_transfer_workflow_active_slot (active_slot),
    INDEX idx_workload_transfer_workflow_status (
        active,
        status,
        decision_date,
        manager_id
    ),
    INDEX idx_workload_transfer_workflow_shadow_case (shadow_case_id),
    INDEX idx_workload_transfer_workflow_current_offer (current_offer_id),
    CONSTRAINT fk_workload_transfer_workflow_shadow_case
        FOREIGN KEY (shadow_case_id)
        REFERENCES workload_shadow_transfer_cases (workload_shadow_transfer_case_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_workload_transfer_workflow_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id),
    CONSTRAINT fk_workload_transfer_workflow_source
        FOREIGN KEY (source_worker_id) REFERENCES workers (worker_id),
    CONSTRAINT fk_workload_transfer_workflow_company
        FOREIGN KEY (company_id) REFERENCES companies (company_id),
    CONSTRAINT fk_workload_transfer_workflow_accepted_worker
        FOREIGN KEY (accepted_worker_id) REFERENCES workers (worker_id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workload_transfer_workflow_candidates (
    workload_transfer_workflow_candidate_id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    sequence_number INT NOT NULL,
    rating DECIMAL(5,2) NOT NULL DEFAULT 0,
    hundred_percent_days INT NOT NULL DEFAULT 0,
    failure_days INT NOT NULL DEFAULT 0,
    current_estimated_minutes BIGINT NOT NULL DEFAULT 0,
    target_group_chat_id BIGINT NOT NULL,
    candidate_telegram_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
    last_offered_at DATETIME(6) NULL,
    last_responded_at DATETIME(6) NULL,
    response_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workload_transfer_workflow_candidate_id),
    UNIQUE KEY uk_workload_transfer_workflow_candidate (workflow_id, worker_id),
    UNIQUE KEY uk_workload_transfer_workflow_sequence (workflow_id, sequence_number),
    INDEX idx_workload_transfer_workflow_candidate_status (
        workflow_id,
        status,
        sequence_number
    ),
    CONSTRAINT fk_workload_transfer_workflow_candidate_workflow
        FOREIGN KEY (workflow_id)
        REFERENCES workload_transfer_workflows (workload_transfer_workflow_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_workload_transfer_workflow_candidate_worker
        FOREIGN KEY (worker_id) REFERENCES workers (worker_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workload_transfer_offers (
    workload_transfer_offer_id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    workflow_candidate_id BIGINT NOT NULL,
    candidate_worker_id BIGINT NOT NULL,
    sequence_number INT NOT NULL,
    offer_token CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'READY',
    workflow_version BIGINT NOT NULL DEFAULT 0,
    target_group_chat_id BIGINT NOT NULL,
    telegram_message_id INT NULL,
    offered_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL,
    responded_at DATETIME(6) NULL,
    response_actor_telegram_id BIGINT NULL,
    response_actor_user_id BIGINT NULL,
    response_reason VARCHAR(500) NULL,
    delivery_attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    processing_token CHAR(36) NULL,
    processing_lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(80) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workload_transfer_offer_id),
    UNIQUE KEY uk_workload_transfer_offer_token (offer_token),
    UNIQUE KEY uk_workload_transfer_offer_candidate (
        workflow_id,
        workflow_candidate_id
    ),
    INDEX idx_workload_transfer_offer_due (
        status,
        next_attempt_at,
        processing_lease_until,
        expires_at,
        workload_transfer_offer_id
    ),
    UNIQUE KEY uk_workload_transfer_offer_processing_token (processing_token),
    INDEX idx_workload_transfer_offer_worker (
        candidate_worker_id,
        status,
        created_at
    ),
    CONSTRAINT fk_workload_transfer_offer_workflow
        FOREIGN KEY (workflow_id)
        REFERENCES workload_transfer_workflows (workload_transfer_workflow_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_workload_transfer_offer_workflow_candidate
        FOREIGN KEY (workflow_candidate_id)
        REFERENCES workload_transfer_workflow_candidates (
            workload_transfer_workflow_candidate_id
        )
        ON DELETE CASCADE,
    CONSTRAINT fk_workload_transfer_offer_worker
        FOREIGN KEY (candidate_worker_id) REFERENCES workers (worker_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_workload_transfer_offer_actor
        FOREIGN KEY (response_actor_user_id) REFERENCES users (id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workload_transfer_executions (
    workload_transfer_execution_id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    accepted_offer_id BIGINT NOT NULL,
    idempotency_key CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PREPARED',
    source_worker_id BIGINT NOT NULL,
    target_worker_id BIGINT NOT NULL,
    manager_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    graph_fingerprint CHAR(64) NOT NULL,
    plan_json LONGTEXT NOT NULL,
    before_snapshot_json LONGTEXT NULL,
    after_snapshot_json LONGTEXT NULL,
    transferred_order_count INT NOT NULL DEFAULT 0,
    transferred_review_count INT NOT NULL DEFAULT 0,
    transferred_bad_task_count INT NOT NULL DEFAULT 0,
    transferred_recovery_task_count INT NOT NULL DEFAULT 0,
    started_at DATETIME(6) NULL,
    applied_at DATETIME(6) NULL,
    rollback_deadline_at DATETIME(6) NULL,
    rolled_back_at DATETIME(6) NULL,
    error_code VARCHAR(80) NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workload_transfer_execution_id),
    UNIQUE KEY uk_workload_transfer_execution_idempotency (idempotency_key),
    UNIQUE KEY uk_workload_transfer_execution_offer (accepted_offer_id),
    INDEX idx_workload_transfer_execution_workflow (
        workflow_id,
        status,
        created_at
    ),
    INDEX idx_workload_transfer_execution_company (
        company_id,
        status,
        created_at
    ),
    CONSTRAINT fk_workload_transfer_execution_workflow
        FOREIGN KEY (workflow_id)
        REFERENCES workload_transfer_workflows (workload_transfer_workflow_id),
    CONSTRAINT fk_workload_transfer_execution_offer
        FOREIGN KEY (accepted_offer_id)
        REFERENCES workload_transfer_offers (workload_transfer_offer_id),
    CONSTRAINT fk_workload_transfer_execution_source
        FOREIGN KEY (source_worker_id) REFERENCES workers (worker_id),
    CONSTRAINT fk_workload_transfer_execution_target
        FOREIGN KEY (target_worker_id) REFERENCES workers (worker_id),
    CONSTRAINT fk_workload_transfer_execution_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id),
    CONSTRAINT fk_workload_transfer_execution_company
        FOREIGN KEY (company_id) REFERENCES companies (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workload_transfer_assignment_audit (
    workload_transfer_assignment_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    execution_id BIGINT NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id BIGINT NOT NULL,
    previous_worker_id BIGINT NULL,
    new_worker_id BIGINT NULL,
    details_json LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workload_transfer_assignment_audit_id),
    UNIQUE KEY uk_workload_transfer_assignment_entity (
        execution_id,
        entity_type,
        entity_id
    ),
    INDEX idx_workload_transfer_assignment_lookup (
        entity_type,
        entity_id,
        created_at
    ),
    CONSTRAINT fk_workload_transfer_assignment_execution
        FOREIGN KEY (execution_id)
        REFERENCES workload_transfer_executions (workload_transfer_execution_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_workload_transfer_assignment_previous_worker
        FOREIGN KEY (previous_worker_id) REFERENCES workers (worker_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_workload_transfer_assignment_new_worker
        FOREIGN KEY (new_worker_id) REFERENCES workers (worker_id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workload_transfer_emergency_assignments (
    workload_transfer_emergency_assignment_id BIGINT NOT NULL AUTO_INCREMENT,
    assignment_key CHAR(36) NOT NULL,
    shadow_case_id BIGINT NULL,
    exhausted_workflow_id BIGINT NULL,
    source_manager_id BIGINT NOT NULL,
    source_worker_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    review_id BIGINT NOT NULL,
    target_manager_id BIGINT NOT NULL,
    target_worker_id BIGINT NOT NULL,
    target_group_chat_id BIGINT NOT NULL,
    audit_group_chat_id BIGINT NOT NULL,
    mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PREPARED',
    reason VARCHAR(500) NOT NULL,
    review_bot_id BIGINT NULL,
    review_vigul_before BIT NOT NULL DEFAULT 0,
    review_text_ready_at_before DATETIME(6) NULL,
    review_text_hash_before CHAR(64) NULL,
    target_company_link_added BIT NOT NULL DEFAULT 0,
    target_notification_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    audit_notification_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    notification_attempts INT NOT NULL DEFAULT 0,
    notification_processing_token CHAR(36) NULL,
    notification_lease_until DATETIME(6) NULL,
    notification_next_attempt_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    decision_date DATE NOT NULL,
    applied_at DATETIME(6) NULL,
    rollback_deadline_at DATETIME(6) NULL,
    rolled_back_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workload_transfer_emergency_assignment_id),
    UNIQUE KEY uk_workload_transfer_emergency_key (assignment_key),
    UNIQUE KEY uk_workload_transfer_emergency_case_review (
        shadow_case_id,
        review_id
    ),
    UNIQUE KEY uk_workload_transfer_emergency_notification_token (
        notification_processing_token
    ),
    INDEX idx_workload_transfer_emergency_due (
        status,
        notification_next_attempt_at,
        notification_lease_until
    ),
    INDEX idx_workload_transfer_emergency_monitor (
        decision_date,
        source_manager_id,
        status
    ),
    CONSTRAINT fk_workload_transfer_emergency_case
        FOREIGN KEY (shadow_case_id)
        REFERENCES workload_shadow_transfer_cases (
            workload_shadow_transfer_case_id
        )
        ON DELETE SET NULL,
    CONSTRAINT fk_workload_transfer_emergency_workflow
        FOREIGN KEY (exhausted_workflow_id)
        REFERENCES workload_transfer_workflows (
            workload_transfer_workflow_id
        )
        ON DELETE SET NULL,
    CONSTRAINT fk_workload_transfer_emergency_source_manager
        FOREIGN KEY (source_manager_id) REFERENCES managers (manager_id),
    CONSTRAINT fk_workload_transfer_emergency_source
        FOREIGN KEY (source_worker_id) REFERENCES workers (worker_id),
    CONSTRAINT fk_workload_transfer_emergency_company
        FOREIGN KEY (company_id) REFERENCES companies (company_id),
    CONSTRAINT fk_workload_transfer_emergency_review
        FOREIGN KEY (review_id) REFERENCES reviews (review_id),
    CONSTRAINT fk_workload_transfer_emergency_target_manager
        FOREIGN KEY (target_manager_id) REFERENCES managers (manager_id),
    CONSTRAINT fk_workload_transfer_emergency_target
        FOREIGN KEY (target_worker_id) REFERENCES workers (worker_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('workload.live.mode', 'SHADOW', CURRENT_TIMESTAMP(6)),
    ('workload.live.apply-enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('workload.live.history-start-date', '2026-08-01', CURRENT_TIMESTAMP(6)),
    ('workload.live.min-finalized-days', '14', CURRENT_TIMESTAMP(6)),
    ('workload.live.stable-hours', '168', CURRENT_TIMESTAMP(6)),
    ('workload.live.min-candidates-per-manager', '2', CURRENT_TIMESTAMP(6)),
    ('workload.live.canary-manager-ids', '', CURRENT_TIMESTAMP(6)),
    ('workload.live.offer-timeout-minutes', '15', CURRENT_TIMESTAMP(6)),
    ('workload.live.offer-start-time', '10:00', CURRENT_TIMESTAMP(6)),
    ('workload.live.offer-end-time', '21:00', CURRENT_TIMESTAMP(6)),
    ('workload.live.max-transfers-per-manager-day', '1', CURRENT_TIMESTAMP(6)),
    ('workload.live.max-transfers-global-day', '3', CURRENT_TIMESTAMP(6)),
    ('workload.live.rollback-window-minutes', '30', CURRENT_TIMESTAMP(6)),
    ('workload.live.first-live-owner-confirmations', '5', CURRENT_TIMESTAMP(6)),
    ('workload.live.emergency-fallback-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('workload.live.retention-days', '400', CURRENT_TIMESTAMP(6)),
    ('workload.live.settings-revision', '1', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
