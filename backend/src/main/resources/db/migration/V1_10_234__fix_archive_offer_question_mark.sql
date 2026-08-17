UPDATE app_settings
SET setting_value = CONCAT(LEFT(setting_value, CHAR_LENGTH(setting_value) - 1), '?'),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'client.messages.archive-offer-text'
  AND setting_value = '{company}\n\nЗдравствуйте! Давно не запускали новый заказ. Можем подготовить новую аккуратную серию отзывов и обновить карточку компании. Если актуально, напишите, пожалуйста, сколько отзывов нужно в этот раз.';

UPDATE client_chat_unanswered_items item
JOIN client_chat_messages message
  ON message.id = item.last_client_message_id
SET item.status = 'MISCLASSIFIED',
    item.closed_at = CURRENT_TIMESTAMP(6),
    item.close_reason = 'Системное уведомление WhatsApp ошибочно принято за сообщение клиента',
    item.resolution_type = 'MISCLASSIFIED',
    item.resolution_message_id = NULL,
    item.resolution_reply_text = NULL,
    item.resolution_reason_code = 'WHATSAPP_SYSTEM_NOTIFICATION',
    item.resolution_comment = 'Карточка автоматически закрыта: e2e_notification не является сообщением клиента',
    item.resolved_by_user_id = NULL,
    item.manual_override = FALSE,
    item.reply_quality = NULL,
    item.reply_quality_reason = NULL,
    item.audit_required = FALSE,
    item.updated_at = CURRENT_TIMESTAMP(6)
WHERE item.status = 'OPEN'
  AND item.platform = 'WHATSAPP'
  AND message.platform = 'WHATSAPP'
  AND message.direction = 'INCOMING'
  AND message.sender_role = 'CLIENT'
  AND message.message_text = '[Вложение: e2e_notification]'
  AND item.last_message_text = '[Вложение: e2e_notification]'
  AND NOT EXISTS (
      SELECT 1
      FROM client_chat_messages later_message
      WHERE later_message.platform = message.platform
        AND later_message.chat_id = message.chat_id
        AND later_message.direction = 'INCOMING'
        AND later_message.sender_role = 'CLIENT'
        AND later_message.message_at > message.message_at
        AND COALESCE(later_message.message_text, '') <> '[Вложение: e2e_notification]'
  );
