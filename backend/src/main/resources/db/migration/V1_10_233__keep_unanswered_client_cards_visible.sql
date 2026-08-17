UPDATE client_chat_unanswered_items
SET resolution_reason_code = 'FOLLOW_UP_RECORDED',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE status = 'OPEN'
  AND resolution_type = 'DEFERRED'
  AND resolution_reason_code = 'FOLLOW_UP_SCHEDULED';

UPDATE manager_daily_control_concrete_items concrete
JOIN client_chat_unanswered_items unanswered
  ON unanswered.id = concrete.entity_id
JOIN manager_daily_controls control_day
  ON control_day.daily_control_id = concrete.control_id
SET concrete.item_status = 'OPEN',
    concrete.action_type = NULL,
    concrete.resolved_at = NULL,
    concrete.automatic_resolution = FALSE,
    concrete.follow_up_at = NULL,
    concrete.updated_at = CURRENT_TIMESTAMP(6)
WHERE concrete.entity_type = 'CLIENT_CHAT_UNANSWERED'
  AND unanswered.status = 'OPEN'
  AND concrete.item_status <> 'OPEN'
  AND control_day.control_date = CURRENT_DATE;

UPDATE manager_daily_control_items parent_item
JOIN manager_daily_controls control_day
  ON control_day.daily_control_id = parent_item.control_id
JOIN manager_daily_control_concrete_items concrete
  ON concrete.parent_item_id = parent_item.control_item_id
JOIN client_chat_unanswered_items unanswered
  ON unanswered.id = concrete.entity_id
SET parent_item.item_status = 'OPEN',
    parent_item.action_type = NULL,
    parent_item.comment = NULL,
    parent_item.resolved_at = NULL,
    parent_item.automatic_resolution = FALSE,
    parent_item.updated_at = CURRENT_TIMESTAMP(6)
WHERE concrete.entity_type = 'CLIENT_CHAT_UNANSWERED'
  AND concrete.item_status = 'OPEN'
  AND unanswered.status = 'OPEN'
  AND control_day.control_date = CURRENT_DATE
  AND parent_item.item_status <> 'OPEN';

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('manager-control.unanswered-client-messages.no-response-ai-timeout-seconds', '20', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.no-response-ai-minimum-confidence', '90', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
