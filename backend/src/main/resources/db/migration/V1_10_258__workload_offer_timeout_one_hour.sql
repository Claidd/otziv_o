-- Give live transfer offers enough time for a worker to accept a company.

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('workload.live.offer-timeout-minutes', '60', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_value = '60',
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
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