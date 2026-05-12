CREATE TABLE IF NOT EXISTS reputation_research_snapshots
(
    snapshot_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id    BIGINT      NOT NULL,
    company_title VARCHAR(255) NULL,
    provider      VARCHAR(64) NOT NULL,
    source_count  INT         NOT NULL DEFAULT 0,
    snapshot_json LONGTEXT    NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_reputation_research_snapshot_company FOREIGN KEY (company_id) REFERENCES companies (company_id) ON DELETE CASCADE,
    INDEX idx_reputation_research_snapshot_company_created (company_id, created_at)
) ENGINE = InnoDB;
