ALTER TABLE scheduled_client_message_state
    ADD COLUMN archive_checked_at DATETIME(6) NULL,
    ADD COLUMN archive_check_status VARCHAR(32) NULL,
    ADD COLUMN archive_check_channel VARCHAR(24) NULL;
