package com.hunt.otziv.contractor_payments.repository;

/**
 * Shared historical evidence, with separate activation and runtime policies.
 * Runtime may accept a later task only when its expected paid salary and direct
 * ledger entries are present. The activation query remains deliberately strict.
 */
final class ContractorLegacyRewardConflictSql {
    private ContractorLegacyRewardConflictSql() { }

    private static final String BEFORE_TASK_POLICY = """
        SELECT COUNT(DISTINCT reward.zp_order)
        FROM zp reward
        WHERE reward.zp_active = 1
          AND reward.zp_order IS NOT NULL
          AND reward.zp_order > 0
          AND (
              reward.zp_source IS NULL
              OR TRIM(reward.zp_source) = ''
              OR NOT (
                  CAST(reward.zp_source AS BINARY) IN (
                      CAST('ORDER_COMPLETION_MANAGER' AS BINARY),
                      CAST('ORDER_COMPLETION_SPECIALIST' AS BINARY),
                      CAST('PERFORMER_PRODUCT_COMPLETION' AS BINARY)
                  )
                  OR EXISTS (
                      SELECT 1
                      FROM bad_review_tasks classified_task
                      WHERE classified_task.bad_review_task_order = reward.zp_order
                        AND CAST(reward.zp_source AS BINARY) IN (
                            CAST(CONCAT('BAD_REVIEW_DONE_MANAGER:', classified_task.bad_review_task_id) AS BINARY),
                            CAST(CONCAT('BAD_REVIEW_DONE_SPECIALIST:', classified_task.bad_review_task_id) AS BINARY),
                            CAST(CONCAT('BAD_REVIEW_CANCEL_MANAGER:', classified_task.bad_review_task_id) AS BINARY),
                            CAST(CONCAT('BAD_REVIEW_CANCEL_SPECIALIST:', classified_task.bad_review_task_id) AS BINARY)
                        )
                  )
              )
          )
          AND (
              reward.zp_source IS NULL
              OR TRIM(reward.zp_source) = ''
              OR CAST(reward.zp_source AS BINARY) NOT IN (
                  CAST('ORDER_MANAGER_REWARD' AS BINARY),
                  CAST('ORDER_SPECIALIST_REWARD' AS BINARY),
                  CAST('PERFORMER_PRODUCT_REWARD' AS BINARY)
              )
              OR NOT EXISTS (
                  SELECT 1
                  FROM orders old_order
                  WHERE old_order.order_id = reward.zp_order
                    AND old_order.order_amount > 0
                    AND (
                        (reward.zp_date IS NOT NULL AND reward.zp_date < :startDate)
                        OR EXISTS (
                            SELECT 1
                            FROM contractor_completion_reward_markers bridge_marker
                            WHERE bridge_marker.order_id = reward.zp_order
                              AND bridge_marker.occurred_on < :startDate
                              AND bridge_marker.logical_source = CASE
                                  WHEN CAST(reward.zp_source AS BINARY) = CAST('ORDER_MANAGER_REWARD' AS BINARY)
                                      THEN 'ORDER_COMPLETION_MANAGER'
                                  WHEN CAST(reward.zp_source AS BINARY) = CAST('ORDER_SPECIALIST_REWARD' AS BINARY)
                                      THEN 'ORDER_COMPLETION_SPECIALIST'
                                  WHEN CAST(reward.zp_source AS BINARY) = CAST('PERFORMER_PRODUCT_REWARD' AS BINARY)
                                      THEN 'PERFORMER_PRODUCT_COMPLETION'
                                  ELSE ''
                              END
                        )
                    )
                    AND (
                        SELECT COUNT(*)
                        FROM order_details completed_detail
                        JOIN reviews completed_review
                          ON completed_review.review_order_details = completed_detail.order_detail_id
                        WHERE completed_detail.order_detail_order = old_order.order_id
                          AND completed_review.review_publish = 1
                    ) = old_order.order_amount
                    AND NOT EXISTS (
                        SELECT 1
                        FROM order_details undated_detail
                        JOIN reviews undated_review
                          ON undated_review.review_order_details = undated_detail.order_detail_id
                        WHERE undated_detail.order_detail_order = old_order.order_id
                          AND undated_review.review_publish = 1
                          AND (
                              undated_review.review_publish_date IS NULL
                              OR undated_review.review_publish_date >= :startDate
                          )
                    )
                    AND NOT EXISTS (
                        SELECT 1
                        FROM bad_review_tasks ambiguous_task
                        WHERE ambiguous_task.bad_review_task_order = old_order.order_id
                          AND ambiguous_task.bad_review_task_status = 'DONE'
                          AND (
                              ambiguous_task.bad_review_task_completed_date IS NULL
                              OR (ambiguous_task.bad_review_task_completed_date >= :startDate AND (
        """;

    private static final String AFTER_TASK_POLICY = """
                              ))
                          )
                    )
              )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM contractor_legacy_reward_reconciliation_items attested
              WHERE attested.reconciliation_order_id = reward.zp_order
                AND attested.reconciliation_kind = 'MANUAL'
                AND attested.reconciliation_status = 'APPLIED'
                AND attested.manual_completed_on < :startDate
                AND attested.resolved_at IS NOT NULL
                AND NULLIF(TRIM(attested.resolved_by), '') IS NOT NULL
                AND NULLIF(TRIM(attested.manual_evidence_reference), '') IS NOT NULL
                AND NULLIF(TRIM(attested.resolution_reason), '') IS NOT NULL
                AND attested.reconciliation_run_id = (
                    SELECT MAX(latest_attested.reconciliation_run_id)
                    FROM contractor_legacy_reward_reconciliation_items latest_attested
                    WHERE latest_attested.reconciliation_order_id = reward.zp_order
                      AND latest_attested.reconciliation_kind = 'MANUAL'
                      AND latest_attested.reconciliation_status = 'APPLIED'
                      AND latest_attested.manual_completed_on < :startDate
                )
                AND (
                    SELECT COUNT(*)
                    FROM contractor_legacy_reward_reconciliation_items exact_item
                    JOIN zp exact_zp ON exact_zp.zp_id = exact_item.reconciliation_zp_id
                    WHERE exact_item.reconciliation_run_id = attested.reconciliation_run_id
                      AND exact_item.reconciliation_order_id = reward.zp_order
                      AND exact_item.reconciliation_kind = 'MANUAL'
                      AND exact_item.reconciliation_status = 'APPLIED'
                      AND exact_item.reconciliation_group_hash = attested.reconciliation_group_hash
                      AND exact_item.manual_completed_on = attested.manual_completed_on
                      AND exact_item.resolved_at IS NOT NULL
                      AND NULLIF(TRIM(exact_item.resolved_by), '') IS NOT NULL
                      AND NULLIF(TRIM(exact_item.manual_evidence_reference), '') IS NOT NULL
                      AND NULLIF(TRIM(exact_item.resolution_reason), '') IS NOT NULL
                      AND exact_zp.zp_active = 1
                      AND exact_zp.zp_order = exact_item.reconciliation_order_id
                      AND exact_zp.zp_user = exact_item.original_zp_user
                      AND exact_zp.zp_profession = exact_item.original_zp_profession
                      AND exact_zp.zp_sum <=> exact_item.original_zp_sum
                      AND exact_zp.zp_amount = exact_item.original_zp_amount
                      AND exact_zp.zp_date <=> exact_item.original_zp_date
                      AND exact_zp.zp_reward_basis <=> exact_item.original_zp_reward_basis
                      AND SHA2(COALESCE(exact_zp.zp_attribution_snapshot, ''), 256)
                          <=> exact_item.original_zp_attribution_snapshot_hash
                      AND exact_zp.zp_source <=> exact_item.target_zp_source
                      AND exact_zp.zp_contractor_role <=> exact_item.target_zp_contractor_role
                      AND exact_zp.zp_attribution_final = exact_item.target_zp_attribution_final
                ) = (
                    SELECT COUNT(*) FROM zp active_exact
                    WHERE active_exact.zp_order = reward.zp_order
                      AND active_exact.zp_active = 1
                )
                AND (
                    SELECT COUNT(*)
                    FROM contractor_legacy_reward_reconciliation_items all_attested
                    WHERE all_attested.reconciliation_run_id = attested.reconciliation_run_id
                      AND all_attested.reconciliation_order_id = reward.zp_order
                      AND all_attested.reconciliation_kind = 'MANUAL'
                      AND all_attested.reconciliation_status = 'APPLIED'
                ) = (
                    SELECT COUNT(*) FROM zp all_active_exact
                    WHERE all_active_exact.zp_order = reward.zp_order
                      AND all_active_exact.zp_active = 1
                )
          )
        """;

    private static final String MISSING_PAID_TASK_ACCOUNTING = """
        NOT EXISTS (
            SELECT 1
            FROM orders paid_order
            JOIN order_statuses paid_status ON paid_status.order_status_id = paid_order.order_status
            JOIN managers task_manager ON task_manager.manager_id = paid_order.order_manager
            JOIN users manager_user ON manager_user.id = task_manager.user_id
            JOIN workers task_worker ON task_worker.worker_id = ambiguous_task.bad_review_task_worker
            JOIN users specialist_user ON specialist_user.id = task_worker.user_id
            WHERE paid_order.order_id = old_order.order_id
              AND paid_status.order_status_title = 'Оплачено'
              AND paid_order.order_pay_day >= :startDate
              AND reward.zp_date = paid_order.order_pay_day
              AND reward.zp_attribution_final = 1
              AND ambiguous_task.bad_review_task_price > 0
              AND EXISTS (
                  SELECT 1 FROM contractor_completion_reward_markers task_marker
                  WHERE task_marker.order_id = paid_order.order_id
                    AND CAST(task_marker.logical_source AS BINARY) =
                        CAST(CONCAT('BAD_REVIEW_DONE:', ambiguous_task.bad_review_task_id) AS BINARY)
                    AND task_marker.occurred_on = GREATEST(
                        paid_order.order_pay_day, ambiguous_task.bad_review_task_completed_date)
              )
              AND NOT EXISTS (
                  SELECT 1 FROM zp canceled_task_reward
                  WHERE canceled_task_reward.zp_order = paid_order.order_id
                    AND canceled_task_reward.zp_active = 1
                    AND CAST(canceled_task_reward.zp_source AS BINARY) IN (
                        CAST(CONCAT('BAD_REVIEW_CANCEL_MANAGER:', ambiguous_task.bad_review_task_id) AS BINARY),
                        CAST(CONCAT('BAD_REVIEW_CANCEL_SPECIALIST:', ambiguous_task.bad_review_task_id) AS BINARY)
                    )
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM (SELECT 'MANAGER' AS role UNION ALL SELECT 'SPECIALIST') expected_role
                  WHERE (
                      ROUND(ambiguous_task.bad_review_task_price * COALESCE(
                          CASE WHEN expected_role.role = 'MANAGER'
                              THEN manager_user.coefficient ELSE specialist_user.coefficient END, 0), 2) > 0
                      OR EXISTS (
                          SELECT 1 FROM zp existing_task_reward
                          WHERE existing_task_reward.zp_order = paid_order.order_id
                            AND existing_task_reward.zp_active = 1
                            AND CAST(existing_task_reward.zp_source AS BINARY) = CAST(CONCAT(
                                'BAD_REVIEW_DONE_', expected_role.role, ':', ambiguous_task.bad_review_task_id) AS BINARY)
                      )
                      OR EXISTS (
                          SELECT 1 FROM contractor_reward_ledger existing_task_ledger
                          WHERE existing_task_ledger.order_id = paid_order.order_id
                            AND existing_task_ledger.active = 1
                            AND CAST(existing_task_ledger.source_code AS BINARY) = CAST(CONCAT(
                                'BAD_REVIEW_DONE_', expected_role.role, ':', ambiguous_task.bad_review_task_id) AS BINARY)
                      )
                  )
                    AND (
                        SELECT COUNT(*) FROM zp task_reward
                        WHERE task_reward.zp_order = paid_order.order_id
                          AND task_reward.zp_active = 1
                          AND CAST(task_reward.zp_source AS BINARY) = CAST(CONCAT(
                              'BAD_REVIEW_DONE_', expected_role.role, ':', ambiguous_task.bad_review_task_id) AS BINARY)
                          AND CAST(task_reward.zp_contractor_role AS BINARY) = CAST(expected_role.role AS BINARY)
                          AND task_reward.zp_user = CASE WHEN expected_role.role = 'MANAGER'
                              THEN manager_user.id ELSE specialist_user.id END
                          AND task_reward.zp_profession = CASE WHEN expected_role.role = 'MANAGER'
                              THEN task_manager.manager_id ELSE task_worker.worker_id END
                          AND task_reward.zp_attribution_final = 1
                          AND task_reward.zp_reward_basis = ambiguous_task.bad_review_task_price
                          AND task_reward.zp_sum > 0
                          AND task_reward.zp_amount = 1
                          AND task_reward.zp_date = GREATEST(
                              paid_order.order_pay_day, ambiguous_task.bad_review_task_completed_date)
                          AND (
                              SELECT COUNT(*) FROM zp same_task_source
                              WHERE same_task_source.zp_order = paid_order.order_id
                                AND same_task_source.zp_active = 1
                                AND CAST(same_task_source.zp_source AS BINARY) = CAST(task_reward.zp_source AS BINARY)
                          ) = 1
                          AND (
                              SELECT COUNT(*) FROM contractor_reward_ledger source_ledger
                              WHERE source_ledger.source_zp_id = task_reward.zp_id AND source_ledger.active = 1
                          ) = 1
                          AND EXISTS (
                              SELECT 1 FROM contractor_reward_ledger task_ledger
                              JOIN contractor_payment_profiles task_profile ON task_profile.id = task_ledger.profile_id
                              WHERE task_ledger.source_zp_id = task_reward.zp_id
                                AND task_ledger.active = 1
                                AND task_ledger.order_id = paid_order.order_id
                                AND task_profile.user_id = task_reward.zp_user
                                AND CAST(task_profile.contractor_role AS BINARY) = CAST(expected_role.role AS BINARY)
                                AND task_ledger.amount_kopecks = ROUND(task_reward.zp_sum * 100)
                                AND task_ledger.work_units = task_reward.zp_amount
                                AND task_ledger.occurred_on = task_reward.zp_date
                                AND CAST(task_ledger.source_code AS BINARY) = CAST(task_reward.zp_source AS BINARY)
                          )
                    ) <> 1
              )
        )
        """;

    static final String ACTIVATION = BEFORE_TASK_POLICY + "TRUE" + AFTER_TASK_POLICY;
    static final String RUNTIME = BEFORE_TASK_POLICY + MISSING_PAID_TASK_ACCOUNTING + AFTER_TASK_POLICY;
}
