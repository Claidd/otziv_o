CREATE TABLE IF NOT EXISTS gamification_rewards (
    reward_id BIGINT NOT NULL AUTO_INCREMENT,
    reward_code VARCHAR(80) NOT NULL,
    title VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NULL,
    reward_type VARCHAR(32) NOT NULL,
    icon VARCHAR(80) NULL,
    image_url VARCHAR(600) NULL,
    token_cost INT NOT NULL DEFAULT 0,
    required_level INT NOT NULL DEFAULT 1,
    stock_quantity INT NULL,
    active BIT(1) NOT NULL DEFAULT b'0',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reward_id),
    UNIQUE KEY uk_gamification_reward_code (reward_code),
    KEY idx_gamification_reward_catalog (active, sort_order, required_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS gamification_token_ledger (
    token_entry_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    amount INT NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    description VARCHAR(500) NULL,
    unique_entry_key VARCHAR(190) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (token_entry_id),
    UNIQUE KEY uk_gamification_token_entry (unique_entry_key),
    KEY idx_gamification_token_user_created (user_id, created_at),
    CONSTRAINT fk_gamification_token_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS gamification_reward_claims (
    claim_id BIGINT NOT NULL AUTO_INCREMENT,
    reward_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    token_cost INT NOT NULL,
    comment VARCHAR(1000) NULL,
    admin_comment VARCHAR(1000) NULL,
    requested_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    fulfilled_at DATETIME(6) NULL,
    PRIMARY KEY (claim_id),
    KEY idx_gamification_claim_user (user_id, requested_at),
    KEY idx_gamification_claim_status (status, requested_at),
    CONSTRAINT fk_gamification_claim_reward FOREIGN KEY (reward_id) REFERENCES gamification_rewards (reward_id),
    CONSTRAINT fk_gamification_claim_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS manager_queue_state_events (
    state_event_id BIGINT NOT NULL AUTO_INCREMENT,
    manager_id BIGINT NOT NULL,
    state_code VARCHAR(24) NOT NULL,
    open_action_count BIGINT NOT NULL DEFAULT 0,
    within_target_count BIGINT NOT NULL DEFAULT 0,
    target_missed_count BIGINT NOT NULL DEFAULT 0,
    overdue_count BIGINT NOT NULL DEFAULT 0,
    observed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (state_event_id),
    KEY idx_manager_queue_state_manager_time (manager_id, observed_at),
    KEY idx_manager_queue_state_created (created_at),
    CONSTRAINT fk_manager_queue_state_manager FOREIGN KEY (manager_id) REFERENCES managers (manager_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE manager_performance_daily
    ADD COLUMN lead_action_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN target_sla_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN target_sla_met_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN hard_sla_breach_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN controlled_seconds BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN clean_queue_seconds BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN day_stars INT NOT NULL DEFAULT 0,
    ADD COLUMN day_status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    ADD COLUMN xp_earned BIGINT NOT NULL DEFAULT 0;

ALTER TABLE manager_performance_monthly
    ADD COLUMN controlled_seconds BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN clean_queue_seconds BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN completed_days INT NOT NULL DEFAULT 0,
    ADD COLUMN ideal_days INT NOT NULL DEFAULT 0,
    ADD COLUMN xp_earned BIGINT NOT NULL DEFAULT 0;

ALTER TABLE manager_performance_yearly
    ADD COLUMN controlled_seconds BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN clean_queue_seconds BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN completed_days INT NOT NULL DEFAULT 0,
    ADD COLUMN ideal_days INT NOT NULL DEFAULT 0,
    ADD COLUMN xp_earned BIGINT NOT NULL DEFAULT 0;

INSERT INTO gamification_rules (event_type, enabled, points, updated_at)
VALUES
    ('MANAGER_CLIENT_REPLY', b'1', 10, CURRENT_TIMESTAMP(6)),
    ('MANAGER_LEAD_HANDLED', b'1', 15, CURRENT_TIMESTAMP(6)),
    ('MANAGER_CONTROL_ACTION', b'1', 20, CURRENT_TIMESTAMP(6)),
    ('MANAGER_QUEUE_CLEARED', b'1', 30, CURRENT_TIMESTAMP(6)),
    ('MANAGER_DAY_COMPLETED', b'1', 60, CURRENT_TIMESTAMP(6)),
    ('MANAGER_IDEAL_DAY', b'1', 100, CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE event_type = event_type;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('manager.sla.enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('manager.sla.target.message-minutes', '30', CURRENT_TIMESTAMP(6)),
    ('manager.sla.hard.message-minutes', '480', CURRENT_TIMESTAMP(6)),
    ('manager.sla.target.lead-minutes', '60', CURRENT_TIMESTAMP(6)),
    ('manager.sla.hard.lead-minutes', '480', CURRENT_TIMESTAMP(6)),
    ('manager.sla.target.risk-minutes', '30', CURRENT_TIMESTAMP(6)),
    ('manager.sla.hard.risk-minutes', '240', CURRENT_TIMESTAMP(6)),
    ('manager.sla.target.default-minutes', '120', CURRENT_TIMESTAMP(6)),
    ('manager.sla.hard.default-minutes', '720', CURRENT_TIMESTAMP(6)),
    ('manager.sla.control-target-hours', '14', CURRENT_TIMESTAMP(6)),
    ('manager.sla.day-target-percent', '90', CURRENT_TIMESTAMP(6)),
    ('manager.gamification.rewards-enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('manager.gamification.competition-enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('manager.gamification.level-xp', '500', CURRENT_TIMESTAMP(6)),
    ('manager.gamification.token-level-step', '5', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
