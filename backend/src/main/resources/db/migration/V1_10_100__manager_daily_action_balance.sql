ALTER TABLE manager_performance_daily
    ADD COLUMN task_auto_closed BIGINT NOT NULL DEFAULT 0 AFTER task_open,
    ADD COLUMN task_resolved BIGINT NOT NULL DEFAULT 0 AFTER task_auto_closed,
    ADD COLUMN task_action_taken BIGINT NOT NULL DEFAULT 0 AFTER task_resolved,
    ADD COLUMN task_deferred BIGINT NOT NULL DEFAULT 0 AFTER task_action_taken,
    ADD COLUMN task_acknowledged BIGINT NOT NULL DEFAULT 0 AFTER task_deferred,
    ADD COLUMN task_other_open BIGINT NOT NULL DEFAULT 0 AFTER unanswered_count,
    ADD COLUMN snapshot_at DATETIME NULL AFTER finalized_at;

ALTER TABLE manager_daily_control_items
    ADD COLUMN automatic_resolution TINYINT(1) NOT NULL DEFAULT 0 AFTER resolved_at,
    ADD COLUMN resolved_episode_count BIGINT NOT NULL DEFAULT 0 AFTER automatic_resolution,
    ADD COLUMN action_taken_episode_count BIGINT NOT NULL DEFAULT 0 AFTER resolved_episode_count,
    ADD COLUMN deferred_episode_count BIGINT NOT NULL DEFAULT 0 AFTER action_taken_episode_count,
    ADD COLUMN acknowledged_episode_count BIGINT NOT NULL DEFAULT 0 AFTER deferred_episode_count,
    ADD COLUMN auto_closed_episode_count BIGINT NOT NULL DEFAULT 0 AFTER acknowledged_episode_count;

ALTER TABLE manager_daily_control_concrete_items
    ADD COLUMN automatic_resolution TINYINT(1) NOT NULL DEFAULT 0 AFTER resolved_at,
    ADD COLUMN resolved_episode_count BIGINT NOT NULL DEFAULT 0 AFTER automatic_resolution,
    ADD COLUMN action_taken_episode_count BIGINT NOT NULL DEFAULT 0 AFTER resolved_episode_count,
    ADD COLUMN deferred_episode_count BIGINT NOT NULL DEFAULT 0 AFTER action_taken_episode_count,
    ADD COLUMN acknowledged_episode_count BIGINT NOT NULL DEFAULT 0 AFTER deferred_episode_count,
    ADD COLUMN auto_closed_episode_count BIGINT NOT NULL DEFAULT 0 AFTER acknowledged_episode_count;
