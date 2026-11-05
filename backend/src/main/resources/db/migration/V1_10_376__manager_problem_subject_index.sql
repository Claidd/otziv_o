-- Problem diagnosis scans (2026-11-04 audit, PERF-14).
-- The scan looked for the latest card of every problem subject by grouping
-- the whole card history twice a minute. The subject key below is the same
-- grouping expression (type family + entity id or key); with an index the
-- scan starts from the few unresolved cards and checks for a later card of
-- the same subject instead. VIRTUAL: no table rebuild, values live in the index.
ALTER TABLE manager_daily_control_concrete_items
    ADD COLUMN problem_subject VARCHAR(240) GENERATED ALWAYS AS (CONCAT(
        CASE WHEN entity_type LIKE 'COMMON_INVOICE%' THEN 'COMMON_INVOICE'
             WHEN entity_type LIKE 'CLIENT_CHAT%' THEN 'CLIENT_CHAT'
             ELSE entity_type END,
        ':',
        COALESCE(CAST(entity_id AS CHAR), entity_key))) VIRTUAL,
    ADD INDEX idx_mdcci_problem_subject (problem_subject, control_entity_id);
