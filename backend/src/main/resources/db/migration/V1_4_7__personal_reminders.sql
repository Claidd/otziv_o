CREATE TABLE IF NOT EXISTS personal_reminders (
    personal_reminder_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    text VARCHAR(1000) NULL,
    reminder_mode VARCHAR(20) NOT NULL DEFAULT 'none',
    remind_at DATETIME(6) NULL,
    timer_minutes INT NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (personal_reminder_id),
    INDEX idx_personal_reminders_user_completed (user_id, completed_at),
    INDEX idx_personal_reminders_user_remind_at (user_id, remind_at),
    CONSTRAINT fk_personal_reminders_user
        FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
) ENGINE=InnoDB;
