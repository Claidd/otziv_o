-- Background client messages sent outside the autoresponder state machine (common and paper
-- invoice reminders, offer campaigns) reserve their slot here, so the autoresponder's shared
-- daily limit and per-channel interval see them and they see the autoresponder.
CREATE TABLE IF NOT EXISTS client_message_external_dispatches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    purpose VARCHAR(32) NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    channel VARCHAR(16) NOT NULL,
    counts_daily_limit TINYINT(1) NOT NULL,
    reserved_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_client_message_external_dispatches_reserved (reserved_at),
    KEY idx_client_message_external_dispatches_channel (channel, reserved_at)
);
