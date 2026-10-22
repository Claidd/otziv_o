CREATE TABLE manager_problem_cases (
    case_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_key VARCHAR(240) NOT NULL,
    manager_id BIGINT NOT NULL,
    source_task_id BIGINT NOT NULL,
    current_version BIGINT NOT NULL DEFAULT 0,
    episode BIGINT NOT NULL DEFAULT 1,
    closed BOOLEAN NOT NULL DEFAULT FALSE,
    evidence_hash CHAR(64),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_manager_problem_case_key (case_key)
);

CREATE TABLE manager_problem_evidence_versions (
    case_id BIGINT NOT NULL,
    evidence_version BIGINT NOT NULL,
    episode BIGINT NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    diagnosis_json MEDIUMTEXT,
    model VARCHAR(100),
    prompt_version VARCHAR(60) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_token VARCHAR(36),
    lease_until DATETIME(6),
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    completed_at DATETIME(6),
    PRIMARY KEY (case_id, evidence_version),
    KEY idx_manager_problem_pending (status, next_attempt_at),
    CONSTRAINT fk_manager_problem_evidence_case FOREIGN KEY (case_id) REFERENCES manager_problem_cases(case_id)
);
