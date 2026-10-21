CREATE TABLE scheduled_client_message_manual_handoffs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    state_id BIGINT NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    control_entity_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    checked_at DATETIME(6) NOT NULL,
    note VARCHAR(400) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_scheduled_manual_handoff_state (state_id),
    KEY idx_scheduled_manual_handoff_actor (actor_user_id, created_at)
);
