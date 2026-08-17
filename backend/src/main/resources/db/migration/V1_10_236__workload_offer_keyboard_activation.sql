-- A Telegram offer is actionable only after the inline keyboard was installed
-- and the activation was durably acknowledged by the application.
ALTER TABLE workload_transfer_offers
    ADD COLUMN keyboard_activated BIT(1) NOT NULL DEFAULT b'0'
        AFTER telegram_message_id;
