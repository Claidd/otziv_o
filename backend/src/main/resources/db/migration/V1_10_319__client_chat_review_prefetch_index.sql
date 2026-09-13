-- Bounded recovery reads recent OPEN cards without scanning every manager's history.
-- No new message/AI-result table: prepared decisions remain in a bounded process cache.
ALTER TABLE client_chat_unanswered_items
    ADD INDEX idx_client_chat_unanswered_status_recent (status, last_client_message_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;
