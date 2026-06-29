UPDATE client_chat_messages
SET sender_role = 'BOT'
WHERE platform = 'TELEGRAM'
  AND (
      sender_external_id = '1087968824'
      OR LOWER(COALESCE(sender_name, '')) LIKE '%groupanonymousbot%'
  );

UPDATE client_chat_unanswered_items item
JOIN client_chat_messages message ON message.id = item.last_client_message_id
SET item.status = 'NO_RESPONSE_NEEDED',
    item.closed_at = COALESCE(item.closed_at, NOW()),
    item.close_reason = 'Системное сообщение Telegram'
WHERE item.status = 'OPEN'
  AND message.platform = 'TELEGRAM'
  AND message.sender_role = 'BOT';
