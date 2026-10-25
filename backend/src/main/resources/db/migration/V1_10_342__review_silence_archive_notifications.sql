ALTER TABLE common_invoice_orders ADD COLUMN silence_archived_at DATETIME(6) NULL;
CREATE TABLE review_silence_archives (
  state_id BIGINT NOT NULL PRIMARY KEY,
  order_id BIGINT NOT NULL,
  target_key VARCHAR(100) NOT NULL,
  sent_count INT NOT NULL,
  source_snapshot MEDIUMTEXT NOT NULL,
  offer_next_at DATETIME(6) NULL,
  offer_status VARCHAR(500) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_review_silence_order_cycle (order_id,target_key)
);
CREATE TABLE review_archive_notifications (
  operation_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  source_state_id BIGINT NOT NULL,
  channel VARCHAR(20) NOT NULL,
  envelope MEDIUMTEXT NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'READY',
  claim_token VARCHAR(36) NULL,
  next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  attempts INT NOT NULL DEFAULT 0,
  error_code VARCHAR(120) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY ix_review_archive_notification_due(state,next_attempt_at)
);
