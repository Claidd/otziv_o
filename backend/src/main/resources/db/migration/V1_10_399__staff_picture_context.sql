ALTER TABLE notification_media_assets ADD COLUMN context_condition VARCHAR(40) NULL;

CREATE TABLE staff_picture_cadence (
    chat_id BIGINT NOT NULL PRIMARY KEY,
    skips_left TINYINT UNSIGNED NOT NULL
);

INSERT INTO notification_media_rules (event_code, recipient_type, enabled, image_probability_percent,
                                      cooldown_minutes, created_at, updated_at)
VALUES ('STAFF_PAYMENT_DISPUTE','STAFF',1,100,0,NOW(6),NOW(6)),
       ('WORKER_ACCOUNT_SHORTAGE','WORKER',1,100,360,NOW(6),NOW(6)),
       ('WORKER_CORRECTION_REPORTED','WORKER',1,100,360,NOW(6),NOW(6))
ON DUPLICATE KEY UPDATE event_code=VALUES(event_code);

CREATE TEMPORARY TABLE staff_picture_mapping (
    filename VARCHAR(32) PRIMARY KEY, event_code VARCHAR(80) NOT NULL, condition_code VARCHAR(40) NOT NULL
);
INSERT INTO staff_picture_mapping VALUES
('07-zheka.png','WORKER_TASK_REPEAT','TASK_FOLLOWUP'),
('10-zheka.png','WORKER_PROGRESS_SLOWED','LAST_TASK'),
('15-zheka.png','WORKER_TEXT_GUIDE','TEXT_PROOFREADING'),
('18-zheka.png','WORKER_TASK_OVERDUE','TASK_ACKNOWLEDGED'),
('19-zheka.png','WORKER_PUBLICATION_PENDING','VK_PUBLICATION'),
('20-zheka.png','WORKER_PROGRESS_GROWING','BEFORE_REMINDERS'),
('24-zheka.png','WORKER_TASK_REPEAT','TASK_FOLLOWUP'),
('25-zheka.png','WORKER_TASK_FIRST','TASK_GUIDANCE'),
('28-zheka.png','WORKER_TASK_FIRST','TASK_HANDOFF'),
('29-zheka.png','WORKER_CORRECTION_REPORTED','CORRECTION_REPORTED'),
('30-zheka.png','WORKER_ACCOUNT_BLOCKED_TASKS','ACCOUNT_ACCESS'),
('31-zheka.png','WORKER_PROGRESS_SLOWED','BEFORE_22'),
('33-zheka.png','WORKER_TEXT_GUIDE','TEXT_CORRECTION'),
('34-zheka.png','WORKER_ACCOUNT_BLOCKED_TASKS','ACCOUNT_ACCESS'),
('35-zheka.png','WORKER_PROGRESS_SLOWED','BROKEN_STREAK'),
('36-zheka.png','WORKER_ACCOUNT_SHORTAGE','ACCOUNT_SHORTAGE'),
('37-zheka.png','STAFF_PAYMENT_DISPUTE','DISPUTABLE_RECEIPT'),
('38-zheka.png','WORKER_ACCOUNT_BLOCKED_TASKS','CHANGE_ACCOUNT_BUTTON'),
('41-zheka.png','WORKER_RATING_GUIDE','BAD_TASK_TEXT'),
('42-zheka.png','WORKER_FRESH_ACCOUNT_GUIDE','NEW_PROFILE');

-- The account-blocker rule was seeded disabled for this batch. Preserve unrelated rules.
UPDATE notification_media_rules r SET enabled=1, updated_at=NOW(6)
WHERE event_code='WORKER_ACCOUNT_BLOCKED_TASKS' AND enabled=0
  AND EXISTS (SELECT 1 FROM notification_media_assets a WHERE a.rule_id=r.rule_id
              AND a.storage_key='notification-media/worker_account_blocked_tasks/zheka-20261113-context/38-zheka.png');

UPDATE notification_media_assets a
JOIN staff_picture_mapping m ON SUBSTRING_INDEX(a.storage_key,'/',-1)=m.filename
JOIN notification_media_rules r ON r.event_code=m.event_code
SET a.rule_id=r.rule_id, a.context_condition=m.condition_code, a.active=1, a.updated_at=NOW(6)
WHERE SUBSTRING_INDEX(SUBSTRING_INDEX(a.storage_key,'/',3),'/',-1)='zheka-20261113-context'
  AND a.storage_key LIKE 'notification-media/%';

DROP TEMPORARY TABLE staff_picture_mapping;
