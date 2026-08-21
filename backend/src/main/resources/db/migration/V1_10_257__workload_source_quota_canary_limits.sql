-- Align workload live canary limits with source-worker business stages.
-- The business rule starts reducing load only after more than 4 failed days
-- in the month, while live manager/global limits remain safety brakes.

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('workload.shadow.allowed-failure-days', '4', CURRENT_TIMESTAMP(6)),
    ('workload.live.max-transfers-per-manager-day', '5', CURRENT_TIMESTAMP(6)),
    ('workload.live.max-transfers-global-day', '10', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('workload.shadow.settings-revision', '1', CURRENT_TIMESTAMP(6)),
    ('workload.live.settings-revision', '1', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_value = CAST(
        CAST(
            CASE
                WHEN TRIM(setting_value) REGEXP '^[0-9]+$'
                    THEN TRIM(setting_value)
                ELSE '0'
            END AS UNSIGNED
        ) + 1 AS CHAR
    ),
    updated_at = CURRENT_TIMESTAMP(6);
