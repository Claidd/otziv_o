CREATE TABLE payment_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    terminal_key VARCHAR(64) NOT NULL,
    password_env_key VARCHAR(160) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    test_mode BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_profiles_code UNIQUE (code),
    CONSTRAINT uk_payment_profiles_terminal_key UNIQUE (terminal_key),
    INDEX idx_payment_profiles_enabled (enabled),
    INDEX idx_payment_profiles_default (is_default)
);

INSERT INTO payment_profiles (
    code,
    provider,
    name,
    terminal_key,
    password_env_key,
    enabled,
    is_default,
    test_mode
) VALUES
    (
        'primary',
        'T_BANK',
        'Основной магазин',
        '1779443245436DEMO',
        'OTZIV_PAYMENTS_TBANK_PASSWORD',
        TRUE,
        TRUE,
        TRUE
    ),
    (
        'secondary',
        'T_BANK',
        'Второй магазин',
        '1779507476572DEMO',
        'OTZIV_PAYMENTS_TBANK_SECONDARY_PASSWORD',
        TRUE,
        FALSE,
        TRUE
    );

ALTER TABLE managers
    ADD COLUMN payment_profile_id BIGINT NULL,
    ADD CONSTRAINT fk_managers_payment_profile
        FOREIGN KEY (payment_profile_id) REFERENCES payment_profiles (id);

UPDATE managers
SET payment_profile_id = (SELECT id FROM payment_profiles WHERE code = 'secondary')
WHERE manager_id IN (2, 3);

ALTER TABLE payment_links
    ADD COLUMN payment_profile_id BIGINT NULL,
    ADD COLUMN payment_profile_code VARCHAR(64) NULL,
    ADD COLUMN payment_profile_name VARCHAR(120) NULL,
    ADD CONSTRAINT fk_payment_links_payment_profile
        FOREIGN KEY (payment_profile_id) REFERENCES payment_profiles (id),
    ADD INDEX idx_payment_links_payment_profile (payment_profile_id);

UPDATE payment_links
SET payment_profile_id = (SELECT id FROM payment_profiles WHERE code = 'primary'),
    payment_profile_code = 'primary',
    payment_profile_name = 'Основной магазин'
WHERE payment_profile_id IS NULL;
