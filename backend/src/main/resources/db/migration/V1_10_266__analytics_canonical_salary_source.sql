-- Analytics must use the same final attribution that drives contractor balances.
-- Legacy active ZP rows remain a fallback only when they have never been
-- synchronized into the contractor reward ledger.

CREATE OR REPLACE VIEW analytics_salary_source AS
SELECT
    z.zp_date AS metric_date,
    z.zp_user AS user_id,
    z.zp_id AS source_zp_id,
    z.zp_sum AS salary_sum,
    1 AS salary_entry_count,
    z.zp_amount AS salary_review_count
FROM zp z
WHERE z.zp_active = 1
  AND z.zp_user IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM contractor_reward_ledger ledger
      WHERE ledger.source_zp_id = z.zp_id
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
  AND profile.user_id IS NOT NULL;

ALTER TABLE contractor_reward_ledger
    ADD INDEX idx_contractor_reward_ledger_active_date_profile (active, occurred_on, profile_id);
