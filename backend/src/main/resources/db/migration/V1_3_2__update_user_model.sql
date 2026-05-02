-- V1_3_2__add_keycloak_user_mapping.sql

ALTER TABLE users
    ADD COLUMN keycloak_id VARCHAR(64) NULL AFTER id;

ALTER TABLE users
    ADD COLUMN auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL' AFTER keycloak_id;

ALTER TABLE users
    ADD COLUMN last_login_at DATETIME NULL AFTER auth_provider;

ALTER TABLE users
    MODIFY password VARCHAR(80) NULL;

ALTER TABLE users
    MODIFY username VARCHAR(100) NOT NULL;

ALTER TABLE users
    MODIFY fio VARCHAR(255) NULL;

ALTER TABLE users
    MODIFY email VARCHAR(255) NULL;

ALTER TABLE users
    MODIFY phone_number VARCHAR(50) NULL;

CREATE UNIQUE INDEX ux_users_keycloak_id ON users (keycloak_id);
CREATE INDEX idx_users_auth_provider ON users (auth_provider);
