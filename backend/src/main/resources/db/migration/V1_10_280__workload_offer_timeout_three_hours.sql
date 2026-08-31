-- Extend the response window for delivered company-transfer offers to three
-- hours. Only legacy defaults are replaced; an explicitly configured custom
-- value remains untouched.

SET @v280_now = CURRENT_TIMESTAMP(6);
SET @v280_old_timeout = (
    SELECT setting_value
    FROM app_settings
    WHERE setting_key = 'workload.live.offer-timeout-minutes'
    LIMIT 1
);
SET @v280_timeout_changed = CASE
    WHEN @v280_old_timeout IS NULL THEN 1
    WHEN TRIM(@v280_old_timeout) IN ('15', '60') THEN 1
    ELSE 0
END;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
SELECT 'workload.live.offer-timeout-minutes', '180', @v280_now
WHERE @v280_timeout_changed = 1
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = VALUES(updated_at);

SET @v280_effective_timeout = (
    SELECT setting_value
    FROM app_settings
    WHERE setting_key = 'workload.live.offer-timeout-minutes'
    LIMIT 1
);

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    old_value, new_value, details
)
SELECT @v280_now,
       'system:migration',
       'V1_10_280_workload_offer_timeout',
       'WORKLOAD_OFFER_TIMEOUT_CHANGED',
       'APP_SETTING',
       'workload.live.offer-timeout-minutes',
       COALESCE(@v280_old_timeout, '<missing>'),
       '180',
       'Default response window changed to three hours; custom values are preserved'
WHERE @v280_timeout_changed = 1;

-- The timeout is snapshotted into expires_at after Telegram delivery. Extend
-- only offers that are still answerable; expired/answered history is immutable.
-- The live settings revision intentionally stays unchanged so an in-flight
-- accepted workflow does not become stale solely because its response window
-- was lengthened.
INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    old_value, new_value, details
)
SELECT @v280_now,
       'system:migration',
       'V1_10_280_workload_offer_timeout',
       'WORKLOAD_OFFER_DEADLINE_EXTENDED',
       'WORKLOAD_TRANSFER_OFFER',
       CAST(offer.workload_transfer_offer_id AS CHAR),
       DATE_FORMAT(offer.expires_at, '%Y-%m-%d %H:%i:%s.%f'),
       DATE_FORMAT(
           DATE_ADD(offer.offered_at, INTERVAL 180 MINUTE),
           '%Y-%m-%d %H:%i:%s.%f'
       ),
       'Still-active delivered offer extended to three hours from delivery'
FROM workload_transfer_offers offer
WHERE TRIM(COALESCE(@v280_effective_timeout, '')) = '180'
  AND offer.status = 'OFFERED'
  AND offer.offered_at IS NOT NULL
  AND offer.expires_at > @v280_now
  AND offer.expires_at < DATE_ADD(offer.offered_at, INTERVAL 180 MINUTE);

UPDATE workload_transfer_offers offer
SET offer.expires_at = DATE_ADD(offer.offered_at, INTERVAL 180 MINUTE),
    offer.updated_at = @v280_now
WHERE TRIM(COALESCE(@v280_effective_timeout, '')) = '180'
  AND offer.status = 'OFFERED'
  AND offer.offered_at IS NOT NULL
  AND offer.expires_at > @v280_now
  AND offer.expires_at < DATE_ADD(offer.offered_at, INTERVAL 180 MINUTE);
