ALTER TABLE client_chat_messages
    ADD COLUMN actor_user_id BIGINT NULL AFTER manager_id,
    ADD INDEX idx_client_chat_messages_actor_time (actor_user_id, message_at),
    ADD CONSTRAINT fk_client_chat_messages_actor_user
        FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL;
