INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('manager-control.unanswered-client-messages.resolution-enforcement-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.fast-click-guard-enabled', 'true', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = CURRENT_TIMESTAMP(6);

UPDATE client_chat_unanswered_items
SET audit_required = TRUE,
    manual_override = TRUE,
    resolution_reason_code = 'ACTION_COMPLETED_WITHOUT_REPLY_EVIDENCE',
    reply_quality = 'SUSPICIOUS',
    reply_quality_reason = 'Исходящий ответ после сообщения клиента не найден'
WHERE status = 'ACTION_COMPLETED'
  AND resolved_by_user_id IS NOT NULL
  AND resolution_message_id IS NULL
  AND (resolution_reply_text IS NULL OR TRIM(resolution_reply_text) = '')
  AND audit_required = FALSE
  AND closed_at >= CURRENT_TIMESTAMP(6) - INTERVAL 30 DAY;
