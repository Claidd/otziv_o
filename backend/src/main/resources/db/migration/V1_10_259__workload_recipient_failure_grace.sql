-- Keep recipient eligibility grace aligned with the source-worker threshold.
-- A potential recipient should not be excluded after the first non-100% day:
-- the same monthly grace window is applied before stricter recovery checks.

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('workload.shadow.recipient-maximum-failure-days', '4', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('workload.shadow.settings-revision', '1', CURRENT_TIMESTAMP(6))
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
