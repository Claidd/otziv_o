INSERT IGNORE INTO roles (name) VALUES ('ROLE_PERFORMER');

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'products'
      AND COLUMN_NAME = 'product_requires_performer'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE products ADD COLUMN product_requires_performer BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT ''products.product_requires_performer exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'products'
      AND COLUMN_NAME = 'product_target_platform'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE products ADD COLUMN product_target_platform VARCHAR(32) NULL',
    'SELECT ''products.product_target_platform exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS performer_profiles (
    performer_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    city_id BIGINT NULL,
    gender VARCHAR(32) NOT NULL DEFAULT 'NOT_SPECIFIED',
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    rating DECIMAL(5, 2) NOT NULL DEFAULT 0,
    reliability_score DECIMAL(5, 2) NOT NULL DEFAULT 0,
    completed_count INT NOT NULL DEFAULT 0,
    cancelled_count INT NOT NULL DEFAULT 0,
    expired_offer_count INT NOT NULL DEFAULT 0,
    failed_check_count INT NOT NULL DEFAULT 0,
    max_active_tasks INT NOT NULL DEFAULT 3,
    preferred_channel VARCHAR(32) NOT NULL DEFAULT 'TELEGRAM',
    telegram_link_token VARCHAR(128) NULL,
    telegram_linked_at DATETIME(6) NULL,
    registered_source VARCHAR(64) NULL,
    personal_data_accepted_at DATETIME(6) NULL,
    rules_accepted_at DATETIME(6) NULL,
    honest_review_accepted_at DATETIME(6) NULL,
    moderated_at DATETIME(6) NULL,
    moderated_by_user_id BIGINT NULL,
    block_reason VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_active_at DATETIME(6) NULL,
    PRIMARY KEY (performer_id),
    UNIQUE KEY uk_performer_profiles_user (user_id),
    UNIQUE KEY uk_performer_profiles_telegram_token (telegram_link_token),
    INDEX idx_performer_profiles_city_status (city_id, status),
    INDEX idx_performer_profiles_status_rating (status, rating, reliability_score),
    CONSTRAINT fk_performer_profiles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_performer_profiles_city FOREIGN KEY (city_id) REFERENCES cities (city_id),
    CONSTRAINT fk_performer_profiles_moderated_by FOREIGN KEY (moderated_by_user_id) REFERENCES users (id)
);

CREATE TABLE IF NOT EXISTS performer_cities (
    performer_city_id BIGINT NOT NULL AUTO_INCREMENT,
    performer_id BIGINT NOT NULL,
    city_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    priority INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (performer_city_id),
    UNIQUE KEY uk_performer_cities_performer_city (performer_id, city_id),
    INDEX idx_performer_cities_city_active (city_id, active),
    CONSTRAINT fk_performer_cities_performer FOREIGN KEY (performer_id) REFERENCES performer_profiles (performer_id),
    CONSTRAINT fk_performer_cities_city FOREIGN KEY (city_id) REFERENCES cities (city_id)
);

CREATE TABLE IF NOT EXISTS review_performer_assignments (
    assignment_id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_details_id BINARY(16) NULL,
    review_id BIGINT NOT NULL,
    performer_id BIGINT NULL,
    city_id BIGINT NULL,
    filial_id BIGINT NULL,
    platform VARCHAR(32) NOT NULL DEFAULT 'OTHER',
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    accepted_at DATETIME(6) NULL,
    walked_at DATETIME(6) NULL,
    publish_available_at DATETIME(6) NULL,
    published_claimed_at DATETIME(6) NULL,
    verified_at DATETIME(6) NULL,
    paid_at DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    payout_amount DECIMAL(10, 2) NULL,
    client_approved_text_snapshot TEXT NULL,
    performer_final_text TEXT NULL,
    text_changed_by_performer BOOLEAN NOT NULL DEFAULT FALSE,
    publication_url VARCHAR(1000) NULL,
    instruction VARCHAR(3000) NULL,
    reject_reason VARCHAR(1000) NULL,
    manager_note VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (assignment_id),
    UNIQUE KEY uk_review_performer_assignments_review (review_id),
    INDEX idx_review_performer_assignments_order_status (order_id, status),
    INDEX idx_review_performer_assignments_performer_status (performer_id, status),
    INDEX idx_review_performer_assignments_city_status (city_id, status),
    INDEX idx_review_performer_assignments_publish_due (status, publish_available_at),
    CONSTRAINT fk_review_performer_assignments_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_review_performer_assignments_order_details FOREIGN KEY (order_details_id) REFERENCES order_details (order_detail_id),
    CONSTRAINT fk_review_performer_assignments_review FOREIGN KEY (review_id) REFERENCES reviews (review_id),
    CONSTRAINT fk_review_performer_assignments_performer FOREIGN KEY (performer_id) REFERENCES performer_profiles (performer_id),
    CONSTRAINT fk_review_performer_assignments_city FOREIGN KEY (city_id) REFERENCES cities (city_id),
    CONSTRAINT fk_review_performer_assignments_filial FOREIGN KEY (filial_id) REFERENCES filial (filial_id)
);

CREATE TABLE IF NOT EXISTS review_performer_offers (
    offer_id BIGINT NOT NULL AUTO_INCREMENT,
    assignment_id BIGINT NOT NULL,
    performer_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OFFERED',
    offered_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    responded_at DATETIME(6) NULL,
    telegram_chat_id BIGINT NULL,
    telegram_message_id INT NULL,
    decline_reason VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (offer_id),
    INDEX idx_review_performer_offers_assignment_status (assignment_id, status),
    INDEX idx_review_performer_offers_performer_status (performer_id, status),
    INDEX idx_review_performer_offers_expiry (status, expires_at),
    CONSTRAINT fk_review_performer_offers_assignment FOREIGN KEY (assignment_id) REFERENCES review_performer_assignments (assignment_id),
    CONSTRAINT fk_review_performer_offers_performer FOREIGN KEY (performer_id) REFERENCES performer_profiles (performer_id)
);

CREATE TABLE IF NOT EXISTS performer_task_evidence (
    evidence_id BIGINT NOT NULL AUTO_INCREMENT,
    assignment_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    comment VARCHAR(2000) NULL,
    screenshot_url VARCHAR(1000) NULL,
    file_url VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (evidence_id),
    INDEX idx_performer_task_evidence_assignment (assignment_id, created_at),
    CONSTRAINT fk_performer_task_evidence_assignment FOREIGN KEY (assignment_id) REFERENCES review_performer_assignments (assignment_id)
);

CREATE TABLE IF NOT EXISTS performer_payouts (
    payout_id BIGINT NOT NULL AUTO_INCREMENT,
    performer_id BIGINT NOT NULL,
    assignment_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    approved_at DATETIME(6) NULL,
    paid_at DATETIME(6) NULL,
    payment_comment VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (payout_id),
    UNIQUE KEY uk_performer_payouts_assignment (assignment_id),
    INDEX idx_performer_payouts_performer_status (performer_id, status),
    CONSTRAINT fk_performer_payouts_performer FOREIGN KEY (performer_id) REFERENCES performer_profiles (performer_id),
    CONSTRAINT fk_performer_payouts_assignment FOREIGN KEY (assignment_id) REFERENCES review_performer_assignments (assignment_id)
);

UPDATE products
SET product_requires_performer = TRUE,
    product_target_platform = 'YANDEX'
WHERE LOWER(TRIM(product_title)) IN ('яндекс с исполнителями', 'яндекс исполнители', 'яндекс тайный покупатель');
