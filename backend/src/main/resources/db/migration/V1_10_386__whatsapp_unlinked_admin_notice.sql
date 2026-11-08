-- A message from a WhatsApp group that is not linked to any company is announced to the admin chats
-- by the application itself, with the group, the author, the text and the likely company. The notice
-- covers every new message of the group since the previous one; admin_notified_at records which
-- messages it already covered. Messages saved before this release were already announced by the
-- metric-only alert, so they count as notified and the release sends no burst of old notices.
ALTER TABLE whatsapp_unlinked_events ADD COLUMN admin_notified_at DATETIME(6) NULL;

UPDATE whatsapp_unlinked_events SET admin_notified_at = created_at WHERE admin_notified_at IS NULL;
