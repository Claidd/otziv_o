-- Globally recognize verified internal staff and clean up historical false client cards.
ALTER TABLE client_chat_participant_identities
    ADD INDEX idx_client_chat_identity_global_staff
        (platform, identity_key, sender_role, active, updated_at);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES (
    'manager-control.unanswered-client-messages.staff-name-aliases',
    'Мия Ригз=Мия О|Мия О!',
    CURRENT_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    setting_value = CASE
        WHEN LOWER(COALESCE(setting_value, '')) LIKE '%мия ригз=%'
            THEN setting_value
        WHEN TRIM(COALESCE(setting_value, '')) = ''
            THEN VALUES(setting_value)
        ELSE CONCAT(setting_value, ';', VALUES(setting_value))
    END,
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO client_chat_participant_identities (
    platform,
    chat_id,
    identity_key,
    external_id,
    normalized_name,
    sender_role,
    linked_user_id,
    verified_by_user_id,
    source,
    active,
    created_at,
    updated_at
)
SELECT
    'WHATSAPP',
    '*',
    'id:240161736638694@lid',
    '240161736638694@lid',
    'мия о',
    'STAFF',
    u.id,
    NULL,
    'SYSTEM_BACKFILL',
    1,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
FROM users u
JOIN users_roles user_role ON user_role.user_id = u.id
JOIN roles role ON role.id = user_role.role_id
WHERE u.active = 1
  AND u.username = 'mia'
  AND role.name = 'ROLE_OWNER'
LIMIT 1
ON DUPLICATE KEY UPDATE
    sender_role = 'STAFF',
    linked_user_id = VALUES(linked_user_id),
    source = 'SYSTEM_BACKFILL',
    active = 1,
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO client_chat_participant_identities (
    platform,
    chat_id,
    identity_key,
    external_id,
    normalized_name,
    sender_role,
    linked_user_id,
    verified_by_user_id,
    source,
    active,
    created_at,
    updated_at
)
SELECT
    'MAX',
    '*',
    'id:260174587',
    '260174587',
    'мия о',
    'STAFF',
    u.id,
    NULL,
    'SYSTEM_BACKFILL',
    1,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
FROM users u
JOIN users_roles user_role ON user_role.user_id = u.id
JOIN roles role ON role.id = user_role.role_id
WHERE u.active = 1
  AND u.username = 'mia'
  AND role.name = 'ROLE_OWNER'
LIMIT 1
ON DUPLICATE KEY UPDATE
    sender_role = 'STAFF',
    linked_user_id = VALUES(linked_user_id),
    source = 'SYSTEM_BACKFILL',
    active = 1,
    updated_at = CURRENT_TIMESTAMP(6);

UPDATE client_chat_messages message
SET message.sender_role = 'STAFF'
WHERE (message.platform = 'WHATSAPP'
        AND message.sender_external_id = '240161736638694@lid')
   OR (message.platform = 'MAX'
        AND message.sender_external_id = '260174587');

UPDATE manager_daily_control_concrete_items concrete
JOIN client_chat_unanswered_items item ON item.id = concrete.entity_id
JOIN client_chat_messages message ON message.id = item.last_client_message_id
SET concrete.resolved_episode_count = concrete.resolved_episode_count
        + CASE WHEN concrete.item_status <> 'RESOLVED' THEN 1 ELSE 0 END,
    concrete.auto_closed_episode_count = concrete.auto_closed_episode_count
        + CASE WHEN concrete.item_status <> 'RESOLVED' THEN 1 ELSE 0 END,
    concrete.item_status = 'RESOLVED',
    concrete.action_type = 'RESOLVED',
    concrete.comment = 'Автоматически закрыто: отправитель распознан как сотрудник',
    concrete.resolved_at = COALESCE(concrete.resolved_at, CURRENT_TIMESTAMP(6)),
    concrete.automatic_resolution = 1,
    concrete.updated_at = CURRENT_TIMESTAMP(6)
WHERE concrete.entity_type IN ('CLIENT_CHAT_UNANSWERED', 'CLIENT_CHAT_AUDIT')
  AND (
      (message.platform = 'WHATSAPP'
       AND message.sender_external_id = '240161736638694@lid')
      OR
      (message.platform = 'MAX'
       AND message.sender_external_id = '260174587')
  );

UPDATE client_chat_unanswered_items item
JOIN client_chat_messages message ON message.id = item.last_client_message_id
SET item.status = 'MISCLASSIFIED',
    item.closed_at = COALESCE(item.closed_at, CURRENT_TIMESTAMP(6)),
    item.close_reason = 'Сообщение отправлено сотрудником',
    item.resolution_type = 'MISCLASSIFIED',
    item.resolution_message_id = NULL,
    item.resolution_reply_text = NULL,
    item.resolution_reason_code = 'STAFF_IDENTITY_BACKFILL',
    item.resolution_comment = 'Автоматически исправлено после глобального распознавания сотрудника',
    item.resolved_by_user_id = NULL,
    item.manual_override = 0,
    item.reply_quality = NULL,
    item.reply_quality_reason = NULL,
    item.audit_required = 0,
    item.updated_at = CURRENT_TIMESTAMP(6)
WHERE (message.platform = 'WHATSAPP'
        AND message.sender_external_id = '240161736638694@lid')
   OR (message.platform = 'MAX'
        AND message.sender_external_id = '260174587');

UPDATE manager_daily_control_items parent
JOIN (
    SELECT
        concrete.parent_item_id,
        COUNT(*) AS concrete_count,
        SUM(concrete.item_status = 'OPEN') AS open_count,
        SUM(concrete.item_status <> 'RESOLVED') AS non_resolved_count
    FROM manager_daily_control_concrete_items concrete
    GROUP BY concrete.parent_item_id
) state ON state.parent_item_id = parent.control_item_id
SET parent.resolved_episode_count = parent.resolved_episode_count
        + CASE
            WHEN state.non_resolved_count = 0 AND parent.item_status <> 'RESOLVED' THEN 1
            ELSE 0
        END,
    parent.action_taken_episode_count = parent.action_taken_episode_count
        + CASE
            WHEN state.non_resolved_count > 0 AND parent.item_status <> 'ACTION_TAKEN' THEN 1
            ELSE 0
        END,
    parent.auto_closed_episode_count = parent.auto_closed_episode_count
        + CASE WHEN parent.item_status = 'OPEN' THEN 1 ELSE 0 END,
    parent.item_status = CASE
        WHEN state.non_resolved_count = 0 THEN 'RESOLVED'
        ELSE 'ACTION_TAKEN'
    END,
    parent.action_type = CASE
        WHEN state.non_resolved_count = 0 THEN 'RESOLVED'
        ELSE 'ACTION_TAKEN'
    END,
    parent.comment = CASE
        WHEN state.non_resolved_count = 0
            THEN 'Все конкретные карточки внутри пункта закрыты'
        ELSE 'Все конкретные карточки внутри пункта обработаны'
    END,
    parent.resolved_at = CASE
        WHEN state.non_resolved_count = 0
            THEN COALESCE(parent.resolved_at, CURRENT_TIMESTAMP(6))
        ELSE NULL
    END,
    parent.automatic_resolution = 1,
    parent.updated_at = CURRENT_TIMESTAMP(6)
WHERE parent.group_code = 'ACTION'
  AND state.open_count = 0
  AND state.concrete_count >= parent.item_count
  AND EXISTS (
      SELECT 1
      FROM manager_daily_control_concrete_items affected
      JOIN client_chat_unanswered_items item ON item.id = affected.entity_id
      JOIN client_chat_messages message ON message.id = item.last_client_message_id
      WHERE affected.parent_item_id = parent.control_item_id
        AND affected.entity_type IN ('CLIENT_CHAT_UNANSWERED', 'CLIENT_CHAT_AUDIT')
        AND (
            (message.platform = 'WHATSAPP'
             AND message.sender_external_id = '240161736638694@lid')
            OR
            (message.platform = 'MAX'
             AND message.sender_external_id = '260174587')
        )
  );
