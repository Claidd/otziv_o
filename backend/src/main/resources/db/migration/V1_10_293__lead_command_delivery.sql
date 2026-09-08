-- Stop old lead consumers before upgrade: they do not understand terminal states.
ALTER TABLE lead_sync_queue
    MODIFY payload_json MEDIUMTEXT,
    ADD COLUMN command_id CHAR(36) NULL,
    ADD COLUMN command_kind VARCHAR(16) NOT NULL DEFAULT 'SYNC',
    ADD COLUMN payload_version INT NOT NULL DEFAULT 0,
    ADD COLUMN delivery_state VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN processing_token CHAR(36) NULL,
    ADD COLUMN lease_until DATETIME(6) NULL,
    ADD COLUMN completed_at DATETIME(6) NULL,
    ADD UNIQUE KEY uk_lead_command_id (command_id),
    ADD KEY ix_lead_command_due (delivery_state, next_attempt_at, id),
    ADD KEY ix_lead_command_order (lead_id, id, delivery_state);

CREATE TABLE lead_command_replay_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    queue_id BIGINT NOT NULL,
    actor VARCHAR(255) NOT NULL,
    previous_state VARCHAR(24) NOT NULL,
    resolution VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_lead_replay_queue (queue_id, id)
) ENGINE=InnoDB;
