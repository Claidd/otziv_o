ALTER TABLE users
    ADD COLUMN worker_chat_url VARCHAR(500) NULL,
    ADD COLUMN worker_telegram_group_chat_id BIGINT NULL;
