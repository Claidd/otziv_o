-- No receipt expiry: gateway/history replay must never repeat a committed effect.
CREATE TABLE whatsapp_inbound_receipts (
    receipt_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL
) ENGINE=InnoDB;

-- The frozen, encrypted envelope lives in whatsapp_business_send_operations.
-- This row is inserted in the same transaction as the inbound receipt and preference.
CREATE TABLE whatsapp_inbound_reply_outbox (
    operation_id VARCHAR(128) NOT NULL PRIMARY KEY,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    KEY ix_whatsapp_inbound_reply_due(state,next_attempt_at)
) ENGINE=InnoDB;
