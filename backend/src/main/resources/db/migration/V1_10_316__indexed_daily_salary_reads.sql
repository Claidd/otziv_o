-- Scope-first covering indexes support profile history and team month reads.
-- Additive, online indexes preserve all existing financial constraints.
ALTER TABLE zp ADD INDEX idx_zp_user_active_date_salary
    (zp_user,zp_active,zp_date,zp_id,zp_sum,zp_amount), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE archive_zp ADD INDEX idx_archive_zp_user_active_date_salary
    (zp_user,zp_active,zp_date,zp_id,zp_sum,zp_amount), ALGORITHM=INPLACE, LOCK=NONE;

-- Aggregate within each disjoint canonical branch before UNION materialization.
-- Live/ledger/archive exclusions are identical to V269. source_zp_id sets of the
-- three branches are disjoint, so their per-day distinct counts may be summed.
-- This is a live read model: no stale financial snapshot or new writer contract.
CREATE VIEW analytics_salary_daily_source AS
SELECT reward.zp_date AS metric_date, reward.zp_user AS user_id,
       COALESCE(SUM(reward.zp_sum),0) AS salary_sum,
       COUNT(DISTINCT reward.zp_id) AS salary_entry_count,
       COALESCE(SUM(reward.zp_amount),0) AS salary_review_count
FROM zp reward
WHERE reward.zp_active=1 AND reward.zp_user IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM contractor_reward_ledger ledger WHERE ledger.source_zp_id=reward.zp_id)
GROUP BY reward.zp_user,reward.zp_date
UNION ALL
SELECT ledger.occurred_on, profile.user_id,
       COALESCE(SUM(CAST(ledger.amount_kopecks AS DECIMAL(20,2))/100),0),
       COUNT(DISTINCT ledger.source_zp_id), COALESCE(SUM(ledger.work_units),0)
FROM contractor_reward_ledger ledger
JOIN contractor_payment_profiles profile ON profile.id=ledger.profile_id
WHERE ledger.active=1 AND profile.user_id IS NOT NULL
GROUP BY profile.user_id,ledger.occurred_on
UNION ALL
SELECT archived.zp_date, archived.zp_user,
       COALESCE(SUM(archived.zp_sum),0), COUNT(DISTINCT archived.zp_id), COALESCE(SUM(archived.zp_amount),0)
FROM archive_zp archived
WHERE archived.zp_active=1 AND archived.zp_user IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM zp live_reward WHERE live_reward.zp_id=archived.zp_id)
  AND NOT EXISTS (SELECT 1 FROM contractor_reward_ledger ledger WHERE ledger.source_zp_id=archived.zp_id)
GROUP BY archived.zp_user,archived.zp_date;
