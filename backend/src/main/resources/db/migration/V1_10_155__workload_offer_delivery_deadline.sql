ALTER TABLE workload_transfer_offers
    ADD COLUMN delivery_deadline_at DATETIME(6) NULL
        AFTER target_group_chat_id;

-- Offers created by the previous application version used expires_at for both
-- Telegram delivery and employee response.  Preserve that timestamp as the
-- technical delivery deadline, then clear the response deadline until the
-- message has actually been delivered.
UPDATE workload_transfer_offers
SET delivery_deadline_at = COALESCE(
        expires_at,
        TIMESTAMPADD(MINUTE, 30, created_at)
    )
WHERE status IN ('READY', 'RETRY', 'SENDING');

UPDATE workload_transfer_offers
SET expires_at = NULL
WHERE status IN ('READY', 'RETRY', 'SENDING');

-- For already delivered offers, align the response deadline with offered_at.
UPDATE workload_transfer_offers
SET delivery_deadline_at = COALESCE(offered_at, created_at),
    expires_at = TIMESTAMPADD(
        MINUTE,
        COALESCE(
            (
                SELECT CAST(setting_value AS UNSIGNED)
                FROM app_settings
                WHERE setting_key = 'workload.live.offer-timeout-minutes'
                LIMIT 1
            ),
            15
        ),
        COALESCE(offered_at, created_at)
    )
WHERE status = 'OFFERED';

UPDATE workload_transfer_offers
SET delivery_deadline_at = COALESCE(
        delivery_deadline_at,
        offered_at,
        created_at
    )
WHERE delivery_deadline_at IS NULL;

ALTER TABLE workload_transfer_offers
    MODIFY COLUMN delivery_deadline_at DATETIME(6) NOT NULL,
    ADD INDEX idx_workload_transfer_offer_delivery_deadline (
        status,
        delivery_deadline_at,
        processing_lease_until,
        workload_transfer_offer_id
    );
