ALTER TABLE worker_risk_incidents
    ADD COLUMN assigned_manager_id BIGINT NULL AFTER worker_name,
    ADD COLUMN response_due_at DATETIME(6) NULL AFTER explanation_prompted_at,
    ADD COLUMN explanation_reminder_at DATETIME(6) NULL AFTER response_due_at,
    ADD COLUMN explanation_quality VARCHAR(32) NULL AFTER worker_explanation_by_user_id,
    ADD COLUMN explanation_quality_confidence DECIMAL(5,4) NULL AFTER explanation_quality,
    ADD COLUMN explanation_quality_reason VARCHAR(1000) NULL AFTER explanation_quality_confidence,
    ADD COLUMN explanation_clarification_question VARCHAR(1000) NULL AFTER explanation_quality_reason,
    ADD COLUMN explanation_evaluated_at DATETIME(6) NULL AFTER explanation_clarification_question,
    ADD COLUMN explanation_accepted_at DATETIME(6) NULL AFTER explanation_evaluated_at,
    ADD COLUMN explanation_attempt_count INT NOT NULL DEFAULT 0 AFTER explanation_accepted_at,
    ADD COLUMN section_restricted_at DATETIME(6) NULL AFTER explanation_attempt_count,
    ADD COLUMN section_restriction_released_at DATETIME(6) NULL AFTER section_restricted_at,
    ADD COLUMN manager_resolution_comment VARCHAR(1000) NULL AFTER resolved_by_username,
    ADD COLUMN decision_quality VARCHAR(32) NULL AFTER manager_resolution_comment,
    ADD COLUMN decision_quality_reason VARCHAR(1000) NULL AFTER decision_quality,
    ADD COLUMN audit_required TINYINT(1) NOT NULL DEFAULT 0 AFTER decision_quality_reason,
    ADD INDEX idx_worker_risk_response_due (worker_user_id, status, response_due_at, explanation_accepted_at),
    ADD INDEX idx_worker_risk_manager_created (assigned_manager_id, created_at),
    ADD INDEX idx_worker_risk_audit (audit_required, resolved_at),
    ADD CONSTRAINT fk_worker_risk_assigned_manager
        FOREIGN KEY (assigned_manager_id) REFERENCES managers (manager_id) ON DELETE SET NULL;

UPDATE worker_risk_incidents incident
LEFT JOIN orders risk_order ON risk_order.order_id = incident.order_id
LEFT JOIN companies company ON company.company_id = risk_order.order_company
SET incident.assigned_manager_id = COALESCE(risk_order.order_manager, company.company_manager)
WHERE incident.assigned_manager_id IS NULL
  AND COALESCE(risk_order.order_manager, company.company_manager) IS NOT NULL;

UPDATE worker_risk_incidents incident
SET incident.assigned_manager_id = (
    SELECT MIN(manager_user.manager_id)
    FROM managers_users manager_user
    WHERE manager_user.user_id = incident.worker_user_id
)
WHERE incident.assigned_manager_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM managers_users manager_user
    WHERE manager_user.user_id = incident.worker_user_id
);

CREATE TABLE IF NOT EXISTS worker_risk_events (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    incident_id BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    actor_user_id BIGINT NULL,
    actor_role VARCHAR(24) NOT NULL,
    source VARCHAR(32) NOT NULL,
    payload_json TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    INDEX idx_worker_risk_event_incident_created (incident_id, created_at),
    INDEX idx_worker_risk_event_actor_created (actor_user_id, created_at),
    INDEX idx_worker_risk_event_type_created (event_type, created_at),
    CONSTRAINT fk_worker_risk_event_incident
        FOREIGN KEY (incident_id) REFERENCES worker_risk_incidents(incident_id) ON DELETE CASCADE
);

UPDATE worker_risk_incidents
SET explanation_quality = 'NEEDS_REVIEW',
    explanation_quality_reason = 'Историческое пояснение: автоматическая оценка не выполнялась',
    explanation_evaluated_at = COALESCE(worker_explanation_at, updated_at),
    explanation_accepted_at = COALESCE(worker_explanation_at, updated_at),
    explanation_attempt_count = CASE WHEN worker_explanation_at IS NULL THEN 0 ELSE 1 END
WHERE worker_explanation_at IS NOT NULL
  AND explanation_accepted_at IS NULL;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('worker-risk.explanation.auto-request-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('worker-risk.explanation.quality-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('worker-risk.explanation.reminder-minutes', '120', CURRENT_TIMESTAMP(6)),
    ('worker-risk.explanation.deadline-minutes', '180', CURRENT_TIMESTAMP(6)),
    ('worker-risk.explanation.max-clarifications', '1', CURRENT_TIMESTAMP(6)),
    ('worker-risk.explanation.ai-timeout-seconds', '20', CURRENT_TIMESTAMP(6)),
    ('worker-risk.specialist-section-restriction-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('manager.summary.ai-analysis-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('manager.summary.ai-analysis-timeout-seconds', '30', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('manager.summary.enabled', 'true', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = VALUES(updated_at);
