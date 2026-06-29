ALTER TABLE worker_risk_incidents
    ADD COLUMN telegram_notification_chat_id BIGINT NULL,
    ADD COLUMN telegram_notification_message_id INT NULL,
    ADD INDEX idx_worker_risk_telegram_message (telegram_notification_chat_id, telegram_notification_message_id);
