-- Client chat message edits (Telegram edited_message, MAX message_edited, WhatsApp message_edit).
-- message_text keeps the text as first received; the latest edit and its time are stored beside it,
-- so a reply can be judged against the text it actually answered. Additive and instant on MySQL.
ALTER TABLE client_chat_messages
    ADD COLUMN edited_text TEXT NULL,
    ADD COLUMN edited_at DATETIME(6) NULL,
    ADD COLUMN edit_count INT NOT NULL DEFAULT 0;

-- Periodic, read-only comparison of a bound company group's stored WhatsApp invite with the invite
-- the connected account currently sees. SUSPECTED is one observation; STALE is confirmed by a later,
-- different group snapshot and raises the company's chat-link repair card. Nothing here changes the
-- company's link or bound group, and nothing is sent.
CREATE TABLE IF NOT EXISTS whatsapp_invite_link_checks (
    company_id BIGINT NOT NULL,
    group_id VARCHAR(160) NOT NULL,
    stored_invite_code VARCHAR(64) NOT NULL,
    current_invite_link VARCHAR(255) NULL,
    status VARCHAR(16) NOT NULL,
    first_seen_at DATETIME(6) NOT NULL,
    first_snapshot_at VARCHAR(40) NULL,
    confirmed_at DATETIME(6) NULL,
    checked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (company_id),
    KEY idx_whatsapp_invite_link_checks_status (status, company_id),
    CONSTRAINT fk_whatsapp_invite_link_checks_company
        FOREIGN KEY (company_id) REFERENCES companies (company_id) ON DELETE CASCADE
);
