CREATE TABLE IF NOT EXISTS manager_report_review_issues (
    issue_id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    question_index INT NOT NULL,
    title VARCHAR(220) NOT NULL,
    question_text TEXT NOT NULL,
    question_json LONGTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    answered_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (issue_id),
    UNIQUE KEY uk_manager_review_issue_index (review_id, question_index),
    INDEX idx_manager_review_issue_status (review_id, status, question_index),
    CONSTRAINT fk_manager_review_issue_session
        FOREIGN KEY (review_id) REFERENCES manager_report_review_sessions (review_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS manager_report_review_disputes (
    dispute_id BIGINT NOT NULL AUTO_INCREMENT,
    issue_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    previous_issue_status VARCHAR(32) NOT NULL,
    previous_session_status VARCHAR(32) NOT NULL,
    manager_text VARCHAR(2000) NULL,
    owner_comment VARCHAR(2000) NULL,
    requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    submitted_at DATETIME(6) NULL,
    resolved_at DATETIME(6) NULL,
    resolved_by_user_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (dispute_id),
    INDEX idx_manager_review_dispute_review (issue_id, status, created_at),
    CONSTRAINT fk_manager_review_dispute_issue
        FOREIGN KEY (issue_id) REFERENCES manager_report_review_issues (issue_id) ON DELETE CASCADE
);
