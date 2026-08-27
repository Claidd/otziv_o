-- A payment arriving after completion could re-enter the pre-cutover payment bridge
-- when historical review dates preceded cutover, even though post-cutover completion
-- markers and canonical reward rows had already been frozen. Preserve the duplicate
-- sources for audit, but remove them from active salary and contractor-ledger totals.
CREATE TEMPORARY TABLE tmp_duplicate_post_cutover_legacy_rewards (
    zp_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

INSERT IGNORE INTO tmp_duplicate_post_cutover_legacy_rewards (zp_id)
SELECT duplicate_reward.zp_id
FROM zp duplicate_reward
JOIN contractor_completion_cutover_state cutover
  ON cutover.id = 1
JOIN contractor_completion_reward_markers completion_marker
  ON completion_marker.order_id = duplicate_reward.zp_order
 AND completion_marker.logical_source = CASE duplicate_reward.zp_source
      WHEN 'ORDER_MANAGER_REWARD' THEN 'ORDER_COMPLETION_MANAGER'
      WHEN 'ORDER_SPECIALIST_REWARD' THEN 'ORDER_COMPLETION_SPECIALIST'
      WHEN 'PERFORMER_PRODUCT_REWARD' THEN 'PERFORMER_PRODUCT_COMPLETION'
      ELSE ''
 END
 AND completion_marker.occurred_on >= cutover.attribution_start_date
JOIN zp canonical_reward
  ON canonical_reward.zp_order = duplicate_reward.zp_order
 AND canonical_reward.zp_user = duplicate_reward.zp_user
 AND canonical_reward.zp_profession = duplicate_reward.zp_profession
 AND canonical_reward.zp_contractor_role <=> duplicate_reward.zp_contractor_role
 AND canonical_reward.zp_sum <=> duplicate_reward.zp_sum
 AND canonical_reward.zp_amount = duplicate_reward.zp_amount
 AND canonical_reward.zp_reward_basis <=> duplicate_reward.zp_reward_basis
 AND canonical_reward.zp_source = completion_marker.logical_source
 AND canonical_reward.zp_active = 1
 AND canonical_reward.zp_id < duplicate_reward.zp_id
WHERE duplicate_reward.zp_active = 1
  AND duplicate_reward.zp_attribution_final = 1
  AND duplicate_reward.zp_date >= cutover.attribution_start_date
  AND duplicate_reward.zp_source IN (
      'ORDER_MANAGER_REWARD',
      'ORDER_SPECIALIST_REWARD',
      'PERFORMER_PRODUCT_REWARD'
  );

UPDATE zp duplicate_reward
JOIN tmp_duplicate_post_cutover_legacy_rewards duplicate
  ON duplicate.zp_id = duplicate_reward.zp_id
SET duplicate_reward.zp_active = 0;

UPDATE contractor_reward_ledger ledger
JOIN tmp_duplicate_post_cutover_legacy_rewards duplicate
  ON duplicate.zp_id = ledger.source_zp_id
SET ledger.active = 0,
    ledger.updated_at = CURRENT_TIMESTAMP(6);

UPDATE contractor_reward_sync_markers sync_marker
JOIN zp duplicate_reward
  ON duplicate_reward.zp_id = sync_marker.source_zp_id
JOIN tmp_duplicate_post_cutover_legacy_rewards duplicate
  ON duplicate.zp_id = duplicate_reward.zp_id
SET sync_marker.source_active = 0,
    sync_marker.source_updated_at = duplicate_reward.zp_updated_at,
    sync_marker.processed_at = CURRENT_TIMESTAMP(6);

DROP TEMPORARY TABLE tmp_duplicate_post_cutover_legacy_rewards;
