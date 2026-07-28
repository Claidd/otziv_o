-- Keep observation notifications muted during rollout. They are enabled only
-- after the new graph projection has completed and the active baseline is known.
UPDATE app_settings
SET setting_value = 'false',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'workload.shadow.group-notifications-enabled';

-- A digest can safely aggregate a large event burst in one set-based claim.
UPDATE app_settings
SET setting_value = '250',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'workload.shadow.notification-batch-size';

-- Existing observation events belong to the pre-fix baseline. They remain
-- visible in monitoring but must never be delivered when notifications are
-- enabled after deployment.
UPDATE workload_shadow_events
SET delivery_status = 'SKIPPED',
    delivery_attempts = 0,
    next_attempt_at = NULL,
    processing_started_at = NULL,
    processing_lease_until = NULL,
    last_error_code = 'NOTIFICATION_BASELINE',
    last_error = 'Событие существовало до безопасного включения сводных уведомлений'
WHERE active = TRUE
  AND target_group_type = 'ADMIN_OWNER_MONITORING';

-- Legacy manager routes are historical diagnostics only.
UPDATE workload_shadow_events
SET active = FALSE,
    delivery_status = CASE
        WHEN delivery_status = 'SENT' THEN 'SENT'
        ELSE 'SKIPPED'
    END,
    next_attempt_at = NULL,
    processing_started_at = NULL,
    processing_lease_until = NULL,
    resolved_at = COALESCE(resolved_at, CURRENT_TIMESTAMP(6)),
    last_error_code = 'ROUTING_POLICY_CHANGED',
    last_error = 'SHADOW-уведомления менеджерам запрещены'
WHERE active = TRUE
  AND target_group_type = 'MANAGER_AUDIT';

UPDATE app_settings
SET setting_value = CAST(CAST(setting_value AS UNSIGNED) + 1 AS CHAR),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'workload.shadow.settings-revision';
