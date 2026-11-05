-- Owner decision 06.11.2026: start the manager audit from a clean slate.
-- Flyway runs before the new application starts, so every audit that is still open at this
-- moment was produced by the previous question and evidence rules. Close them the same way
-- the settings switch closes audits (ManagerReportReviewSettingsService.closeReviews): no
-- Telegram message is sent, restrictions are released, pending questions and unresolved
-- disputes are withdrawn, and one AUDIT_RESET event documents each closed session.
-- Test-mode audits are left untouched.

CREATE TEMPORARY TABLE audit_reset_sessions (
    review_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

INSERT INTO audit_reset_sessions (review_id)
SELECT session.review_id
FROM manager_report_review_sessions session
WHERE session.test_mode = 0
  AND (
      session.completed_at IS NULL
      OR EXISTS (
          SELECT 1
          FROM manager_report_review_issues issue
          JOIN manager_report_review_disputes dispute ON dispute.issue_id = issue.issue_id
          WHERE issue.review_id = session.review_id
            AND dispute.status IN ('DRAFT', 'OPEN', 'NEEDS_CONTEXT')
      )
  );

-- An unresolved dispute is closed in the manager's favour: its remark is withdrawn below.
UPDATE manager_report_review_disputes dispute
JOIN manager_report_review_issues issue ON issue.issue_id = dispute.issue_id
JOIN audit_reset_sessions reset ON reset.review_id = issue.review_id
SET dispute.status = 'ACCEPTED',
    dispute.resolved_at = CURRENT_TIMESTAMP(6),
    dispute.resolved_by_user_id = NULL,
    dispute.owner_comment = 'Спор закрыт без решения владельца: при запуске обновлённого аудита прежние разборы закрыты, замечание снято. Разбор начат заново.'
WHERE dispute.status IN ('DRAFT', 'OPEN', 'NEEDS_CONTEXT');

UPDATE manager_report_review_issues issue
JOIN audit_reset_sessions reset ON reset.review_id = issue.review_id
SET issue.status = 'WITHDRAWN'
WHERE issue.status IN ('PENDING', 'DISPUTE_PENDING', 'DISPUTED', 'NEEDS_CONTEXT');

INSERT INTO manager_report_review_events (review_id, event_type, actor_user_id, actor_role, source, payload_text)
SELECT reset.review_id,
       'AUDIT_RESET',
       NULL,
       'SYSTEM',
       'migration',
       'Закрыт при запуске обновлённого аудита: разбор начат заново'
FROM audit_reset_sessions reset;

-- Single-table UPDATE: assignments run left to right, so the AI pause is added up before it is cleared.
UPDATE manager_report_review_sessions session
SET session.status = 'COMPLETED',
    session.completed_at = COALESCE(session.completed_at, CURRENT_TIMESTAMP(6)),
    session.restriction_released_at = CASE
        WHEN session.restricted_at IS NOT NULL AND session.restriction_released_at IS NULL
            THEN CURRENT_TIMESTAMP(6)
        ELSE session.restriction_released_at
    END,
    session.audit_required = 0,
    session.reply_prompt_message_id = NULL,
    session.current_question_index = session.issue_count,
    session.ai_unavailable_seconds = session.ai_unavailable_seconds + CASE
        WHEN session.ai_unavailable_started_at IS NULL THEN 0
        ELSE GREATEST(0, TIMESTAMPDIFF(SECOND, session.ai_unavailable_started_at, CURRENT_TIMESTAMP(6)))
    END,
    session.ai_unavailable_started_at = NULL,
    session.recovery_awaiting_context = 0,
    session.recovery_next_attempt_at = NULL,
    session.recovery_attempts = 0,
    session.answer_quality = 'AUDIT_RESET',
    session.answer_quality_reason = 'Закрыт при запуске обновлённого аудита: разбор начат заново'
WHERE session.review_id IN (SELECT reset.review_id FROM audit_reset_sessions reset);

DROP TEMPORARY TABLE audit_reset_sessions;
