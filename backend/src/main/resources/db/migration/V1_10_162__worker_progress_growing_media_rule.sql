INSERT INTO notification_media_rules (
    event_code,
    recipient_type,
    enabled,
    image_probability_percent,
    cooldown_minutes,
    created_at,
    updated_at
)
VALUES (
    'WORKER_PROGRESS_GROWING',
    'WORKER',
    b'1',
    100,
    360,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    updated_at = CURRENT_TIMESTAMP(6);
