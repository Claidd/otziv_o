CREATE TABLE thematic_notification_dispatches (
    dispatch_id BIGINT NOT NULL AUTO_INCREMENT,
    event_code VARCHAR(80) NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    dispatch_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    sent_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (dispatch_id),
    UNIQUE KEY uk_thematic_dispatch_event_user_date (
        event_code,
        recipient_user_id,
        dispatch_date
    ),
    KEY idx_thematic_dispatch_user_date_status (
        recipient_user_id,
        dispatch_date,
        status
    ),
    KEY idx_thematic_dispatch_date (dispatch_date),
    CONSTRAINT fk_thematic_dispatch_user
        FOREIGN KEY (recipient_user_id) REFERENCES users (id)
        ON DELETE CASCADE
);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('worker.thematic-notifications.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('worker.thematic-notifications.max-per-day', '2', CURRENT_TIMESTAMP(6)),
    ('worker.thematic-notifications.day-start-hour', '11', CURRENT_TIMESTAMP(6)),
    ('worker.thematic-notifications.site-inactive-hour', '13', CURRENT_TIMESTAMP(6)),
    ('worker.thematic-notifications.publication-hour', '16', CURRENT_TIMESTAMP(6)),
    ('worker.thematic-notifications.progress-hour', '17', CURRENT_TIMESTAMP(6)),
    ('manager.thematic-notifications.progress-hour', '17', CURRENT_TIMESTAMP(6)),
    ('thematic-notifications.retention-days', '90', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);

INSERT INTO notification_media_rules (
    event_code,
    recipient_type,
    enabled,
    image_probability_percent,
    cooldown_minutes,
    created_at,
    updated_at
)
VALUES
    ('WORKER_DAY_START', 'WORKER', b'1', 100, 720, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    ('WORKER_SITE_INACTIVE', 'WORKER', b'1', 100, 720, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    ('WORKER_PUBLICATION_PENDING', 'WORKER', b'1', 100, 720, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    enabled = VALUES(enabled),
    image_probability_percent = VALUES(image_probability_percent),
    cooldown_minutes = VALUES(cooldown_minutes),
    updated_at = CURRENT_TIMESTAMP(6);

UPDATE notification_media_rules
SET cooldown_minutes = 360,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE event_code IN ('WORKER_PROGRESS_SLOWED', 'MANAGER_TEAM_PROGRESS_SLOWED');

UPDATE notification_media_assets asset
JOIN notification_media_rules rule
  ON rule.event_code = 'WORKER_SITE_INACTIVE'
 AND rule.recipient_type = 'WORKER'
SET asset.rule_id = rule.rule_id,
    asset.sort_order = CASE asset.storage_key
        WHEN 'notification-media/worker_progress_slowed/photo_2026-07-29_21-14-31.jpg' THEN 0
        WHEN 'notification-media/worker_progress_slowed/photo_2026-07-29_21-35-12.jpg' THEN 1
        WHEN 'notification-media/worker_progress_slowed/photo_2026-07-29_22-00-41.jpg' THEN 2
        WHEN 'notification-media/worker_progress_slowed/photo_2026-07-30_00-22-47.jpg' THEN 3
        WHEN 'notification-media/worker_task_repeat/photo_2026-07-30_17-26-08.jpg' THEN 4
        ELSE asset.sort_order
    END,
    asset.updated_at = CURRENT_TIMESTAMP(6)
WHERE asset.storage_key IN (
    'notification-media/worker_progress_slowed/photo_2026-07-29_21-14-31.jpg',
    'notification-media/worker_progress_slowed/photo_2026-07-29_21-35-12.jpg',
    'notification-media/worker_progress_slowed/photo_2026-07-29_22-00-41.jpg',
    'notification-media/worker_progress_slowed/photo_2026-07-30_00-22-47.jpg',
    'notification-media/worker_task_repeat/photo_2026-07-30_17-26-08.jpg'
);

UPDATE notification_media_assets asset
JOIN notification_media_rules rule
  ON rule.event_code = 'WORKER_DAY_START'
 AND rule.recipient_type = 'WORKER'
SET asset.rule_id = rule.rule_id,
    asset.sort_order = 0,
    asset.updated_at = CURRENT_TIMESTAMP(6)
WHERE asset.storage_key =
      'notification-media/worker_task_first/photo_2026-07-30_00-19-42.jpg';

UPDATE notification_media_assets asset
JOIN notification_media_rules rule
  ON rule.event_code = 'WORKER_PUBLICATION_PENDING'
 AND rule.recipient_type = 'WORKER'
SET asset.rule_id = rule.rule_id,
    asset.sort_order = 0,
    asset.updated_at = CURRENT_TIMESTAMP(6)
WHERE asset.storage_key =
      'notification-media/worker_task_first/photo_2026-07-29_23-02-23.jpg';
