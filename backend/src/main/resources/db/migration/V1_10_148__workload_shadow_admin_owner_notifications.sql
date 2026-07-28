INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('workload.shadow.notification-group-chat-id', '', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);

-- Observation notifications must never be routed to manager audit groups.
UPDATE app_settings
SET setting_value = 'false',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'workload.shadow.group-notifications-enabled';

UPDATE workload_shadow_events
SET delivery_status = 'SKIPPED',
    next_attempt_at = NULL,
    processing_started_at = NULL,
    processing_lease_until = NULL,
    last_error_code = 'ROUTING_POLICY_CHANGED',
    last_error = 'Доставка в audit-группы менеджеров отключена; требуется общая группа администраторов и владельцев'
WHERE target_group_type = 'MANAGER_AUDIT'
  AND delivery_status IN ('PENDING', 'RETRY', 'PROCESSING');
