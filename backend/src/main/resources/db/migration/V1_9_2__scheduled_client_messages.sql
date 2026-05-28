SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'orders'
      AND COLUMN_NAME = 'order_status_changed_at'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE orders ADD COLUMN order_status_changed_at DATETIME(6) NULL',
    'SELECT ''order_status_changed_at exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE orders
SET order_status_changed_at = TIMESTAMP(COALESCE(order_changed, order_created, CURRENT_DATE()))
WHERE order_status_changed_at IS NULL;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'orders'
      AND INDEX_NAME = 'idx_orders_status_changed'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_status_changed (order_status, order_status_changed_at, order_id)',
    'SELECT ''idx_orders_status_changed exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'companies'
      AND COLUMN_NAME = 'company_status_changed_at'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE companies ADD COLUMN company_status_changed_at DATETIME(6) NULL',
    'SELECT ''company_status_changed_at exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE companies
SET company_status_changed_at = TIMESTAMP(COALESCE(update_status, create_date, CURRENT_DATE()))
WHERE company_status_changed_at IS NULL;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'companies'
      AND INDEX_NAME = 'idx_companies_status_changed'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE companies ADD INDEX idx_companies_status_changed (company_status, company_status_changed_at, company_id)',
    'SELECT ''idx_companies_status_changed exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS scheduled_client_message_state (
    state_id BIGINT NOT NULL AUTO_INCREMENT,
    scenario VARCHAR(60) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_key VARCHAR(100) NOT NULL,
    company_id BIGINT NULL,
    order_id BIGINT NULL,
    archive_order_id BIGINT NULL,
    state_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    next_attempt_at DATETIME(6) NULL,
    last_attempt_at DATETIME(6) NULL,
    last_success_at DATETIME(6) NULL,
    last_error_code VARCHAR(100) NULL,
    last_error_message VARCHAR(1000) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    sent_count INT NOT NULL DEFAULT 0,
    locked_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (state_id),
    UNIQUE KEY uk_scheduled_message_scenario_target (scenario, target_key),
    INDEX idx_scheduled_message_due (state_status, next_attempt_at, locked_until, state_id),
    INDEX idx_scheduled_message_company (company_id, scenario, state_status),
    INDEX idx_scheduled_message_order (order_id, scenario, state_status),
    INDEX idx_scheduled_message_archive_order (archive_order_id, scenario, state_status)
);

CREATE TABLE IF NOT EXISTS scheduled_client_message_attempts (
    attempt_id BIGINT NOT NULL AUTO_INCREMENT,
    state_id BIGINT NULL,
    scenario VARCHAR(60) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_key VARCHAR(100) NOT NULL,
    company_id BIGINT NULL,
    order_id BIGINT NULL,
    archive_order_id BIGINT NULL,
    attempt_status VARCHAR(30) NOT NULL,
    channel VARCHAR(40) NULL,
    error_code VARCHAR(100) NULL,
    error_message VARCHAR(1000) NULL,
    message_preview VARCHAR(500) NULL,
    duration_ms BIGINT NULL,
    attempted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (attempt_id),
    INDEX idx_scheduled_attempts_state (state_id, attempted_at),
    INDEX idx_scheduled_attempts_target (scenario, target_key, attempted_at),
    INDEX idx_scheduled_attempts_attempted_at (attempted_at)
);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('client.messages.worker.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('client.messages.live.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('client.messages.review-check.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('client.messages.payment-reminder.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('client.messages.archive-reorder.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('client.messages.payment-overdue.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('client.messages.payment-overdue.live-enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('client.messages.review-check.interval-days', '2', CURRENT_TIMESTAMP(6)),
    ('client.messages.payment-reminder.interval-days', '2', CURRENT_TIMESTAMP(6)),
    ('client.messages.payment-overdue-days', '30', CURRENT_TIMESTAMP(6)),
    ('client.messages.archive-reorder.months', '3', CURRENT_TIMESTAMP(6)),
    ('client.messages.retention-days', '90', CURRENT_TIMESTAMP(6)),
    ('client.messages.tick.batch-size', '5', CURRENT_TIMESTAMP(6)),
    ('client.messages.candidate-limit', '200', CURRENT_TIMESTAMP(6)),
    ('client.messages.default-gap-seconds', '180', CURRENT_TIMESTAMP(6)),
    ('client.messages.telegram-gap-seconds', '90', CURRENT_TIMESTAMP(6)),
    ('client.messages.max-gap-seconds', '90', CURRENT_TIMESTAMP(6)),
    ('client.messages.whatsapp-gap-seconds', '180', CURRENT_TIMESTAMP(6)),
    ('client.messages.daily-limit', '140', CURRENT_TIMESTAMP(6)),
    ('client.messages.business-windows', '10:00-12:00,14:00-17:00,19:00-21:00', CURRENT_TIMESTAMP(6)),
    ('client.messages.review-check.statuses', 'На проверке', CURRENT_TIMESTAMP(6)),
    ('client.messages.payment-reminder.statuses', 'Выставлен счет,Напоминание', CURRENT_TIMESTAMP(6)),
    ('client.messages.payment-overdue.statuses', 'Выставлен счет,Напоминание', CURRENT_TIMESTAMP(6)),
    ('client.messages.closed-order.statuses', 'Оплачено,Архив,Бан,Не оплачено', CURRENT_TIMESTAMP(6)),
    ('client.messages.payment-overdue.target-status', 'Не оплачено', CURRENT_TIMESTAMP(6)),
    ('client.messages.archive.company-status', 'На стопе', CURRENT_TIMESTAMP(6)),
    ('client.messages.archive.inactive-order-statuses', 'Оплачено,Архив,Бан', CURRENT_TIMESTAMP(6)),
    ('client.messages.open-next-order-request-statuses', 'PENDING,FAILED', CURRENT_TIMESTAMP(6)),
    ('client.messages.review-link-base-url', 'https://o-ogo.ru/review/editReviews', CURRENT_TIMESTAMP(6)),
    ('client.messages.review-reminder-text', '{companyAndFilial}\n\nЗдравствуйте! Напоминаем, пожалуйста, проверьте шаблоны отзывов и внесите правки, если они нужны.\n\nСсылка на проверку отзывов: {reviewLink}', CURRENT_TIMESTAMP(6)),
    ('client.messages.payment-reminder-text', '{companyAndFilial}\n\n{managerPayText} К оплате: {sum} руб.', CURRENT_TIMESTAMP(6)),
    ('client.messages.archive-offer-text', '{company}\n\nЗдравствуйте! Давно не запускали новый заказ. Можем подготовить новую аккуратную серию отзывов и обновить карточку компании. Если актуально, напишите, пожалуйста, сколько отзывов нужно в этот раз.', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;
