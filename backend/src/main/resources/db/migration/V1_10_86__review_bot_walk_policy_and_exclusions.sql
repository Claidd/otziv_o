INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('review.account.walked-counter-threshold', '2', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = VALUES(updated_at);

CREATE TABLE IF NOT EXISTS review_bot_assignment_exclusions (
    review_id BIGINT NOT NULL,
    bot_id BIGINT NOT NULL,
    reason VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (review_id, bot_id),
    KEY idx_review_bot_assignment_exclusions_bot (bot_id),
    CONSTRAINT fk_review_bot_assignment_exclusions_review
        FOREIGN KEY (review_id) REFERENCES reviews (review_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_review_bot_assignment_exclusions_bot
        FOREIGN KEY (bot_id) REFERENCES bots (bot_id)
        ON DELETE CASCADE
);

UPDATE reviews r
JOIN bots b ON b.bot_id = r.review_bot
SET r.review_vigul = 1
WHERE r.review_publish = 0
  AND r.review_vigul = 0
  AND b.bot_active = 1
  AND b.bot_counter >= 2
  AND b.bot_login IS NOT NULL
  AND TRIM(b.bot_login) <> ''
  AND b.bot_password IS NOT NULL
  AND TRIM(b.bot_password) <> ''
  AND b.bot_fio IS NOT NULL
  AND TRIM(b.bot_fio) <> ''
  AND LOWER(TRIM(b.bot_fio)) NOT IN (
      'впишите имя фамилию',
      'впиши имя фамилию',
      'впишите фамилию имя',
      'нет доступных аккаунтов'
  );
