package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.z_zp.model.Zp;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Read-only cutover checks kept separate from the legacy reward repository. */
public interface ContractorCompletionCutoverPreflightRepository extends JpaRepository<Zp, Long> {

    @Query(value = """
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
                    AND reward.zp_date IS NOT NULL
                    AND reward.zp_date < :startDate
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
                              OR ambiguous_task.bad_review_task_completed_date >= :startDate
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
        """, nativeQuery = true)
    long countActiveLegacyRewardCutoverConflicts(@Param("startDate") LocalDate startDate);
}
