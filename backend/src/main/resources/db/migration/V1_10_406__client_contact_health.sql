CREATE TABLE client_contact_health (
    company_id BIGINT NOT NULL PRIMARY KEY,
    route_hash CHAR(64) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    check_status VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    unavailable_reason VARCHAR(64) NULL,
    checked_at DATETIME(6) NOT NULL,
    INDEX ix_client_contact_check (checked_at)
);
