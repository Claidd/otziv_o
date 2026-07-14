ALTER TABLE client_chat_messages
    ADD COLUMN matched_company_count INT NOT NULL DEFAULT 0 AFTER message_text,
    ADD COLUMN matched_company_titles VARCHAR(1000) NULL AFTER matched_company_count,
    ADD COLUMN routing_ambiguous BIT(1) NOT NULL DEFAULT b'0' AFTER matched_company_titles;

CREATE INDEX idx_client_chat_messages_routing_ambiguous
    ON client_chat_messages (routing_ambiguous, created_at, id);
