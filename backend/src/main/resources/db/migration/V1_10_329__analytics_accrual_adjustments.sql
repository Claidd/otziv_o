-- Accrual adjustments affect salary money, but are not completed orders or
-- reviews. Preserve all three archive-aware reward branches from V269.
CREATE OR REPLACE VIEW analytics_salary_source AS
SELECT
    reward.zp_date AS metric_date,
    reward.zp_user AS user_id,
    reward.zp_id AS source_zp_id,
    reward.zp_sum AS salary_sum,
    1 AS salary_entry_count,
    reward.zp_amount AS salary_review_count
FROM zp reward
WHERE reward.zp_active = 1
  AND reward.zp_user IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM contractor_reward_ledger ledger
      WHERE ledger.source_zp_id = reward.zp_id
  )

UNION ALL

SELECT
    ledger.occurred_on AS metric_date,
    profile.user_id AS user_id,
    ledger.source_zp_id AS source_zp_id,
    CAST(ledger.amount_kopecks AS DECIMAL(20, 2)) / 100 AS salary_sum,
    1 AS salary_entry_count,
    ledger.work_units AS salary_review_count
FROM contractor_reward_ledger ledger
JOIN contractor_payment_profiles profile ON profile.id = ledger.profile_id
WHERE ledger.active = 1
  AND profile.user_id IS NOT NULL

UNION ALL

SELECT
    archived.zp_date AS metric_date,
    archived.zp_user AS user_id,
    archived.zp_id AS source_zp_id,
    archived.zp_sum AS salary_sum,
    1 AS salary_entry_count,
    archived.zp_amount AS salary_review_count
FROM archive_zp archived
WHERE archived.zp_active = 1
  AND archived.zp_user IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM zp live_reward
      WHERE live_reward.zp_id = archived.zp_id
  )
  AND NOT EXISTS (
      SELECT 1
      FROM contractor_reward_ledger ledger
      WHERE ledger.source_zp_id = archived.zp_id
  )

UNION ALL

SELECT
    adjustment.effective_on AS metric_date,
    profile.user_id AS user_id,
    NULL AS source_zp_id,
    CAST(adjustment.signed_amount_kopecks AS DECIMAL(20, 2)) / 100 AS salary_sum,
    0 AS salary_entry_count,
    0 AS salary_review_count
FROM contractor_accrual_adjustments adjustment
JOIN contractor_payment_profiles profile ON profile.id = adjustment.profile_id
WHERE profile.user_id IS NOT NULL;

-- V316's daily source remains scoped by user and day. Adjustments get their
-- own disjoint branch, with zero work and review counts even on bonus-only days.
CREATE OR REPLACE VIEW analytics_salary_daily_source AS
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
GROUP BY archived.zp_user,archived.zp_date
UNION ALL
SELECT adjustment.effective_on, profile.user_id,
       COALESCE(SUM(CAST(adjustment.signed_amount_kopecks AS DECIMAL(20,2))/100),0),
       0 AS salary_entry_count, 0 AS salary_review_count
FROM contractor_accrual_adjustments adjustment
JOIN contractor_payment_profiles profile ON profile.id=adjustment.profile_id
WHERE profile.user_id IS NOT NULL
GROUP BY profile.user_id,adjustment.effective_on;
