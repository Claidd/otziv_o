-- Closed analytics months must remain rebuildable after operational rows move
-- to the archive. Live rows win when an order has been restored, preventing a
-- restored check/reward from being counted twice.

CREATE OR REPLACE VIEW analytics_payment_source AS
SELECT
    payment.check_id,
    payment.check_order,
    payment.check_manager,
    payment.check_worker,
    payment.check_date,
    payment.check_sum,
    payment.check_active
FROM payment_check payment
WHERE payment.check_active = 1

UNION ALL

SELECT
    archived.check_id,
    archived.check_order,
    archived.check_manager,
    archived.check_worker,
    archived.check_date,
    archived.check_sum,
    archived.check_active
FROM archive_payment_check archived
WHERE archived.check_active = 1
  AND NOT EXISTS (
      SELECT 1
      FROM payment_check live_payment
      WHERE live_payment.check_id = archived.check_id
  );

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
  );

-- V268 rebuilt its affected months from live-only sources. Run its durable
-- repair once more after the archive-aware views exist so any zeroed closed
-- month is restored and verified automatically.
INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES (
    'financial-integrity.v268-analytics-rebuild-pending',
    'true',
    CURRENT_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = VALUES(updated_at);
