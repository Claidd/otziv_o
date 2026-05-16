ALTER TABLE personal_reminders
    ADD COLUMN source_type VARCHAR(60) NULL,
    ADD COLUMN source_id BIGINT NULL,
    ADD COLUMN source_order_id BIGINT NULL;

CREATE INDEX idx_personal_reminders_source
    ON personal_reminders (source_type, source_id);

CREATE INDEX idx_personal_reminders_user_source
    ON personal_reminders (user_id, source_type, source_id, completed_at);
