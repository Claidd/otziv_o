CREATE TABLE notification_media_rules (
    rule_id BIGINT NOT NULL AUTO_INCREMENT,
    event_code VARCHAR(80) NOT NULL,
    recipient_type VARCHAR(20) NOT NULL,
    enabled BIT NOT NULL DEFAULT 1,
    image_probability_percent INT NOT NULL DEFAULT 100,
    cooldown_minutes INT NOT NULL DEFAULT 0,
    created_by_user_id BIGINT NULL,
    updated_by_user_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (rule_id),
    UNIQUE KEY uk_notification_media_rule_event_recipient (event_code, recipient_type),
    KEY idx_notification_media_rule_enabled (enabled, event_code),
    CONSTRAINT fk_notification_media_rule_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES users (id)
        ON DELETE SET NULL,
    CONSTRAINT fk_notification_media_rule_updated_by
        FOREIGN KEY (updated_by_user_id) REFERENCES users (id)
        ON DELETE SET NULL
);

CREATE TABLE notification_media_assets (
    asset_id BIGINT NOT NULL AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    storage_key VARCHAR(600) NOT NULL,
    image_url VARCHAR(1000) NOT NULL,
    original_filename VARCHAR(255) NULL,
    content_type VARCHAR(80) NOT NULL,
    active BIT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    created_by_user_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (asset_id),
    UNIQUE KEY uk_notification_media_asset_storage_key (storage_key),
    KEY idx_notification_media_asset_rule_active (rule_id, active, sort_order),
    CONSTRAINT fk_notification_media_asset_rule
        FOREIGN KEY (rule_id) REFERENCES notification_media_rules (rule_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_notification_media_asset_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES users (id)
        ON DELETE SET NULL
);

CREATE TABLE notification_media_deliveries (
    delivery_id BIGINT NOT NULL AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    asset_id BIGINT NULL,
    event_code VARCHAR(80) NOT NULL,
    recipient_user_id BIGINT NULL,
    chat_id BIGINT NOT NULL,
    photo_sent BIT NOT NULL DEFAULT 0,
    delivery_note VARCHAR(255) NULL,
    sent_at DATETIME(6) NOT NULL,
    PRIMARY KEY (delivery_id),
    KEY idx_notification_media_delivery_last (event_code, chat_id, photo_sent, sent_at),
    KEY idx_notification_media_delivery_asset (asset_id),
    KEY idx_notification_media_delivery_recipient (recipient_user_id, sent_at),
    CONSTRAINT fk_notification_media_delivery_rule
        FOREIGN KEY (rule_id) REFERENCES notification_media_rules (rule_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_notification_media_delivery_asset
        FOREIGN KEY (asset_id) REFERENCES notification_media_assets (asset_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_notification_media_delivery_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users (id)
        ON DELETE SET NULL
);
