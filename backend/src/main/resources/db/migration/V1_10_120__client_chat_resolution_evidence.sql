CREATE TABLE IF NOT EXISTS client_chat_participant_identities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    platform VARCHAR(24) NOT NULL,
    chat_id VARCHAR(160) NOT NULL,
    identity_key VARCHAR(220) NOT NULL,
    external_id VARCHAR(160) NULL,
    normalized_name VARCHAR(255) NULL,
    sender_role VARCHAR(24) NOT NULL,
    linked_user_id BIGINT NULL,
    verified_by_user_id BIGINT NULL,
    source VARCHAR(40) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_client_chat_identity UNIQUE (platform, chat_id, identity_key),
    INDEX idx_client_chat_identity_external (platform, chat_id, external_id, active),
    INDEX idx_client_chat_identity_name (platform, chat_id, normalized_name, active),
    INDEX idx_client_chat_identity_user (linked_user_id),
    CONSTRAINT fk_client_chat_identity_user
        FOREIGN KEY (linked_user_id) REFERENCES users(id) ON DELETE SET NULL
);

ALTER TABLE client_chat_unanswered_items
    ADD COLUMN resolution_type VARCHAR(32) NULL AFTER close_reason,
    ADD COLUMN resolution_message_id BIGINT NULL AFTER resolution_type,
    ADD COLUMN resolution_reason_code VARCHAR(60) NULL AFTER resolution_message_id,
    ADD COLUMN resolution_comment VARCHAR(1000) NULL AFTER resolution_reason_code,
    ADD COLUMN resolved_by_user_id BIGINT NULL AFTER resolution_comment,
    ADD COLUMN manual_override TINYINT(1) NOT NULL DEFAULT 0 AFTER resolved_by_user_id,
    ADD COLUMN reply_quality VARCHAR(24) NULL AFTER manual_override,
    ADD COLUMN reply_quality_reason VARCHAR(500) NULL AFTER reply_quality,
    ADD COLUMN audit_required TINYINT(1) NOT NULL DEFAULT 0 AFTER reply_quality_reason,
    ADD INDEX idx_client_chat_unanswered_audit (audit_required, closed_at),
    ADD INDEX idx_client_chat_unanswered_resolved_by (resolved_by_user_id),
    ADD CONSTRAINT fk_client_chat_unanswered_resolution_message
        FOREIGN KEY (resolution_message_id) REFERENCES client_chat_messages(id) ON DELETE SET NULL;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('manager-control.unanswered-client-messages.resolution-enforcement-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.fast-click-guard-enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.fast-click-warning-count', '3', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.fast-click-warning-seconds', '10', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.fast-click-critical-count', '10', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.fast-click-critical-seconds', '60', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.reply-quality-shadow-enabled', 'true', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);

UPDATE client_chat_unanswered_items item
SET item.audit_required = 1,
    item.resolution_type = COALESCE(item.resolution_type, 'ADMIN_OVERRIDE'),
    item.resolution_reason_code = COALESCE(item.resolution_reason_code, 'HISTORICAL_CLOSURE_WITHOUT_EVIDENCE')
WHERE item.closed_at >= NOW() - INTERVAL 7 DAY
  AND item.status = 'ANSWERED'
  AND item.close_reason = 'Ответ клиенту проверен вручную'
  AND (
      item.last_message_text LIKE '%?%'
      OR LOWER(item.last_message_text) REGEXP 'не работает|не открывается|не получается|ошибка|проблем|плох|не прош|исправ|добав|запуст|публику|оплат'
  )
  AND NOT EXISTS (
      SELECT 1
      FROM client_chat_messages answer
      WHERE answer.platform = item.platform
        AND answer.chat_id = item.chat_id
        AND answer.message_at > item.last_client_message_at
        AND (
            answer.sender_role = 'STAFF'
            OR LOWER(COALESCE(answer.sender_name, '')) LIKE '%groupanonymousbot%'
        )
  );
