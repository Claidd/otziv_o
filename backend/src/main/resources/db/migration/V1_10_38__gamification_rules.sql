CREATE TABLE IF NOT EXISTS gamification_rules (
    event_type VARCHAR(80) NOT NULL,
    enabled BIT(1) NOT NULL,
    points INT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO gamification_rules (event_type, enabled, points, updated_at)
VALUES
    ('REVIEW_PUBLISHED', b'1', 10, CURRENT_TIMESTAMP(6)),
    ('ORDER_PAID', b'1', 25, CURRENT_TIMESTAMP(6)),
    ('BAD_REVIEW_TASK_DONE', b'1', 15, CURRENT_TIMESTAMP(6)),
    ('REVIEW_RECOVERY_TASK_DONE', b'1', 20, CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    event_type = event_type;
