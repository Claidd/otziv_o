CREATE TABLE IF NOT EXISTS client_chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    platform VARCHAR(24) NOT NULL,
    direction VARCHAR(24) NOT NULL,
    sender_role VARCHAR(24) NOT NULL,
    chat_id VARCHAR(160) NOT NULL,
    chat_title VARCHAR(255) NULL,
    external_message_id VARCHAR(255) NULL,
    company_id BIGINT NULL,
    manager_id BIGINT NULL,
    sender_external_id VARCHAR(160) NULL,
    sender_name VARCHAR(255) NULL,
    message_text TEXT NULL,
    message_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_client_chat_messages_company
        FOREIGN KEY (company_id) REFERENCES companies (company_id),
    CONSTRAINT fk_client_chat_messages_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id),
    UNIQUE KEY uk_client_chat_message_external (platform, chat_id, external_message_id),
    INDEX idx_client_chat_messages_company (company_id, message_at),
    INDEX idx_client_chat_messages_manager (manager_id, message_at),
    INDEX idx_client_chat_messages_chat (platform, chat_id, message_at)
);

CREATE TABLE IF NOT EXISTS client_chat_unanswered_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    platform VARCHAR(24) NOT NULL,
    chat_id VARCHAR(160) NOT NULL,
    chat_title VARCHAR(255) NULL,
    company_id BIGINT NULL,
    manager_id BIGINT NULL,
    last_client_message_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    sender_external_id VARCHAR(160) NULL,
    sender_name VARCHAR(255) NULL,
    last_message_text TEXT NULL,
    first_opened_at DATETIME(6) NOT NULL,
    last_client_message_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6) NULL,
    close_reason VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_client_chat_unanswered_company
        FOREIGN KEY (company_id) REFERENCES companies (company_id),
    CONSTRAINT fk_client_chat_unanswered_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id),
    CONSTRAINT fk_client_chat_unanswered_last_message
        FOREIGN KEY (last_client_message_id) REFERENCES client_chat_messages (id),
    INDEX idx_client_chat_unanswered_manager_status (manager_id, status, last_client_message_at),
    INDEX idx_client_chat_unanswered_chat_status (platform, chat_id, status),
    INDEX idx_client_chat_unanswered_company_status (company_id, status)
);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('manager-control.unanswered-client-messages.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.warning-minutes', '30', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.example-limit', '50', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.auto-ignore-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.auto-ignore-max-length', '60', CURRENT_TIMESTAMP(6)),
    ('manager-control.unanswered-client-messages.auto-ignore-phrases', 'ок,окей,хорошо,спасибо,спасибо большое,благодарю,да,нет,понял,поняла,поняли,принято,договорились,отлично,супер,ясно,ладно,хорошо спасибо,спс', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
