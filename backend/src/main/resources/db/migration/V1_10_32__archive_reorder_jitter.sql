INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('client.messages.archive-reorder.jitter-days', '10', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;

UPDATE scheduled_client_message_state
SET next_attempt_at = DATE_ADD(
        next_attempt_at,
        INTERVAL (CRC32(CONCAT(COALESCE(target_key, ''), ':', state_id)) % 11) DAY
    )
WHERE scenario = 'ARCHIVE_REORDER_OFFER'
  AND state_status = 'ACTIVE'
  AND last_success_at IS NOT NULL
  AND next_attempt_at >= DATE_ADD(NOW(6), INTERVAL 30 DAY);
