CREATE TABLE IF NOT EXISTS gamification_score_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NULL,
    event_type VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NULL,
    actor_role VARCHAR(40) NULL,
    actor_name VARCHAR(180) NULL,
    points INT NOT NULL,
    rule_points INT NOT NULL,
    order_id BIGINT NULL,
    review_id BIGINT NULL,
    unique_score_key VARCHAR(190) NOT NULL,
    source_event_created_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gamification_score_ledger_unique_key (unique_score_key),
    KEY idx_gamification_score_ledger_created_at (created_at),
    KEY idx_gamification_score_ledger_actor_user_id (actor_user_id),
    KEY idx_gamification_score_ledger_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO gamification_settings (setting_key, setting_value, updated_at)
VALUES ('gamification.shadow-scoring.enabled', 'false', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_key = setting_key;
