CREATE TABLE IF NOT EXISTS review_recovery_bot_exclusions (
    review_recovery_task_id BIGINT NOT NULL,
    bot_id BIGINT NOT NULL,
    reason VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (review_recovery_task_id, bot_id),
    KEY idx_review_recovery_bot_exclusions_bot (bot_id),
    CONSTRAINT fk_review_recovery_bot_exclusions_task
        FOREIGN KEY (review_recovery_task_id)
        REFERENCES review_recovery_tasks (review_recovery_task_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_review_recovery_bot_exclusions_bot
        FOREIGN KEY (bot_id)
        REFERENCES bots (bot_id)
        ON DELETE CASCADE
);

UPDATE bots b
JOIN (
    SELECT DISTINCT t.review_recovery_task_bot AS bot_id
    FROM review_recovery_tasks t
    WHERE t.review_recovery_task_status = 'PLANNED'
      AND t.review_recovery_task_bot IS NOT NULL
) active_recovery_bots ON active_recovery_bots.bot_id = b.bot_id
SET b.bot_cooldown_until = '9999-12-31'
WHERE b.bot_active = 1;
