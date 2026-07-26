UPDATE client_chat_unanswered_items item
SET item.audit_required = 0,
    item.resolution_reason_code = 'AUDIT_NOT_REQUIRED_ATTACHMENT',
    item.resolution_comment = COALESCE(
        item.resolution_comment,
        'Аудит автоматически снят: вложение или подтверждение оплаты'
    ),
    item.reply_quality = 'NOT_APPLICABLE',
    item.reply_quality_reason = 'Развернутый ответ для этого сообщения не требуется'
WHERE item.audit_required = 1
  AND item.status = 'ANSWERED'
  AND item.reply_quality IN ('PARTIAL', 'SUSPICIOUS')
  AND (
      LOWER(TRIM(item.last_message_text)) REGEXP '[.](pdf|doc|docx|xls|xlsx|csv|txt|rtf|png|jpg|jpeg|webp|heic|zip|rar)$'
      OR (
          LOWER(item.last_message_text) REGEXP 'оплата (успешно )?прошла|оплату (произвел|произвела|перевел|перевела|отправил|отправила)|я (оплатил|оплатила|перевел|перевела)|деньги (перевел|перевела)|чек об оплате|квитанция об оплате'
          AND LOWER(item.last_message_text) NOT REGEXP 'не прошла|не прошел|не проходит|не получается|ошибка|проблем|не дош'
      )
  );
