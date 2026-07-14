ALTER TABLE manager_performance_daily
    ADD COLUMN problem_action_taken_count BIGINT NOT NULL DEFAULT 0 AFTER problem_resolved_count,
    ADD COLUMN problem_open_count BIGINT NOT NULL DEFAULT 0 AFTER problem_action_taken_count;
