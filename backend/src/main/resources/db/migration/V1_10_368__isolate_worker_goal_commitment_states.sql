-- A previous application version reads the three legacy active states and expects
-- a control-task id and deadline. Worker plans must remain invisible to that job.
UPDATE manager_review_commitments
SET status = CONCAT('WORKER_', status)
WHERE worker_id IS NOT NULL AND status IN ('PLANNED', 'OVERDUE', 'NEEDS_CONTEXT');

ALTER TABLE manager_review_commitments
    ADD CONSTRAINT chk_manager_commitment_target CHECK (
        (source_task_id IS NOT NULL AND worker_id IS NULL AND due_at IS NOT NULL)
        OR (source_task_id IS NULL AND worker_id IS NOT NULL AND goal_date IS NOT NULL AND goal_remaining > 0)
    );
