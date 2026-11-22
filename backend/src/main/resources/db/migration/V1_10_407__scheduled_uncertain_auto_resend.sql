-- When the autoresponder itself re-sent a delivery whose outcome stayed unconfirmed for 24 hours (once per
-- occurrence). Manager control shows the unconfirmed delivery only after that automatic attempt was used.
ALTER TABLE scheduled_client_message_state
    ADD COLUMN uncertain_auto_resend_at DATETIME(6) NULL;
