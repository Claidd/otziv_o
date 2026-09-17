CREATE TABLE client_offer_campaign (
    id CHAR(36) NOT NULL PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    message TEXT NOT NULL,
    daily_limit INT NOT NULL,
    interval_minutes INT NOT NULL,
    window_start CHAR(5) NOT NULL,
    window_end CHAR(5) NOT NULL,
    include_active BOOLEAN NOT NULL,
    include_stopped BOOLEAN NOT NULL,
    include_banned BOOLEAN NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    next_at DATETIME(6) NULL,
    budget_day DATE NULL,
    budget_used INT NOT NULL DEFAULT 0,
    file_name VARCHAR(200) NULL,
    file_type VARCHAR(120) NULL,
    file_mode VARCHAR(16) NOT NULL DEFAULT 'ATTACHMENT',
    file_token CHAR(36) NOT NULL,
    UNIQUE KEY uq_offer_file_token (file_token),
    INDEX ix_offer_campaign_due (state, next_at)
) ENGINE=InnoDB;

CREATE TABLE client_offer_campaign_file (
    campaign_id CHAR(36) NOT NULL PRIMARY KEY,
    content MEDIUMBLOB NOT NULL,
    max_upload_token TEXT NULL,
    CONSTRAINT fk_offer_file_campaign FOREIGN KEY (campaign_id) REFERENCES client_offer_campaign(id)
) ENGINE=InnoDB;

CREATE TABLE client_offer_recipient (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    campaign_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    company_title VARCHAR(500) NOT NULL,
    audience VARCHAR(16) NOT NULL,
    priority INT NOT NULL,
    destination_key VARCHAR(200) NOT NULL,
    chat_url VARCHAR(500) NULL,
    client_id VARCHAR(128) NULL,
    group_id VARCHAR(128) NULL,
    telegram_chat_id BIGINT NULL,
    max_chat_id BIGINT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    operation_id VARCHAR(128) NOT NULL,
    claimed_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    error_message VARCHAR(1000) NULL,
    message_id VARCHAR(512) NULL,
    UNIQUE KEY uq_offer_destination (campaign_id, destination_key),
    UNIQUE KEY uq_offer_operation (operation_id),
    INDEX ix_offer_queue (campaign_id, state, priority, company_id),
    CONSTRAINT fk_offer_recipient_campaign FOREIGN KEY (campaign_id) REFERENCES client_offer_campaign(id)
) ENGINE=InnoDB;
