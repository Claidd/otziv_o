-- Independent admission ledger: no FK locks on users/orders are held during card mutations.
CREATE TABLE worker_account_action_cooldowns (
    worker_user_id BIGINT NOT NULL,
    available_at_epoch_millis BIGINT NOT NULL,
    PRIMARY KEY (worker_user_id)
) ENGINE=InnoDB;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('worker.account-action.cooldown-seconds', '60', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
