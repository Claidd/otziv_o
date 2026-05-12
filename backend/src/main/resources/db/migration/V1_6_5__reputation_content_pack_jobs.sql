CREATE TABLE IF NOT EXISTS reputation_content_pack_jobs
(
    job_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id    BIGINT       NOT NULL,
    company_title VARCHAR(255) NULL,
    status        VARCHAR(24)  NOT NULL,
    provider      VARCHAR(64)  NULL,
    model         VARCHAR(128) NULL,
    request_json  LONGTEXT     NULL,
    pack_json     LONGTEXT     NULL,
    error_message LONGTEXT     NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at    DATETIME(6)  NULL,
    completed_at  DATETIME(6)  NULL,
    CONSTRAINT fk_reputation_content_pack_job_company FOREIGN KEY (company_id) REFERENCES companies (company_id) ON DELETE CASCADE,
    CONSTRAINT uk_reputation_content_pack_job_company UNIQUE (company_id),
    INDEX idx_reputation_content_pack_job_status_updated (status, updated_at)
) ENGINE = InnoDB;
