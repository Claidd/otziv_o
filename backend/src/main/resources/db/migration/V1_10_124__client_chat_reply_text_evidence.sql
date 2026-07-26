ALTER TABLE client_chat_unanswered_items
    ADD COLUMN resolution_reply_text TEXT NULL AFTER resolution_message_id;

UPDATE client_chat_unanswered_items item
JOIN client_chat_messages message ON message.id = item.resolution_message_id
SET item.resolution_reply_text = message.message_text
WHERE item.resolution_reply_text IS NULL
  AND message.message_text IS NOT NULL
  AND message.message_text <> '';
