CREATE TABLE bank_recipient_change (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    target_kind VARCHAR(12) NOT NULL,
    target_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    snapshot_json TEXT NOT NULL,
    actor_json TEXT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    message VARCHAR(500) NOT NULL,
    next_check DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    replacement_id BIGINT,
    closed_status VARCHAR(32),
    lease_token CHAR(36),
    lease_until DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_bank_recipient_source (target_kind, target_id, source_id),
    KEY ix_bank_recipient_check (state, next_check)
) ENGINE=InnoDB;
