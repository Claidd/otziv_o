ALTER TABLE scheduled_client_message_state
    ADD COLUMN delivery_token VARCHAR(64) NULL AFTER locked_until,
    ADD COLUMN delivery_status VARCHAR(32) NULL AFTER delivery_token,
    ADD COLUMN delivery_message TEXT NULL AFTER delivery_status,
    ADD COLUMN delivery_task_id BIGINT NULL AFTER delivery_message,
    ADD COLUMN delivery_prepared_at DATETIME(6) NULL AFTER delivery_task_id,
    ADD UNIQUE INDEX uk_scheduled_message_delivery_token (delivery_token),
    ADD INDEX idx_scheduled_message_delivery_status (delivery_status, delivery_prepared_at),
    ADD INDEX idx_scheduled_message_scenario_error
        (scenario, state_status, last_error_code, next_attempt_at);
