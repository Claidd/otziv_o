-- Dispatches per sending account (one WhatsApp client id; the single Telegram or MAX bot).
-- Offer campaigns and the autoresponder family (state deliveries, archive notices, invoice
-- reminders) keep that account's interval between them. WAITING rows record that a lane was
-- held back by the other one, so the next free slot alternates instead of starving a lane.
CREATE TABLE IF NOT EXISTS client_message_sender_dispatches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sender_account VARCHAR(191) NOT NULL,
    lane VARCHAR(16) NOT NULL,
    event VARCHAR(8) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_client_message_sender_dispatches_account (sender_account, event, lane, occurred_at),
    KEY idx_client_message_sender_dispatches_time (occurred_at)
);
