-- V227 repair-state rows did not have a foreign key and were cleared in a
-- transaction separate from the reward repair. Remove only rows whose work is
-- demonstrably complete (or whose source order no longer exists). Future
-- repairs clear the state in the same transaction as their immutable markers.
DELETE repair
FROM contractor_completion_reward_repair_state repair
LEFT JOIN orders source_order ON source_order.order_id = repair.order_id
WHERE source_order.order_id IS NULL
   OR (
       (
           SELECT COUNT(DISTINCT marker.logical_source)
           FROM contractor_completion_reward_markers marker
           WHERE marker.order_id = repair.order_id
             AND marker.logical_source IN (
                 'ORDER_COMPLETION_MANAGER',
                 'ORDER_COMPLETION_SPECIALIST',
                 'PERFORMER_PRODUCT_COMPLETION'
             )
       ) = 3
       AND NOT EXISTS (
           SELECT 1
           FROM bad_review_tasks task
           WHERE task.bad_review_task_order = repair.order_id
             AND task.bad_review_task_status = 'DONE'
             AND NOT EXISTS (
                 SELECT 1
                 FROM contractor_completion_reward_markers marker
                 WHERE marker.order_id = repair.order_id
                   AND marker.logical_source = CONCAT('BAD_REVIEW_DONE:', task.bad_review_task_id)
             )
       )
       AND NOT EXISTS (
           SELECT 1
           FROM bad_review_tasks task
           WHERE task.bad_review_task_order = repair.order_id
             AND task.bad_review_task_status = 'CANCELED'
             AND NOT EXISTS (
                 SELECT 1
                 FROM contractor_completion_reward_markers cancel_marker
                 WHERE cancel_marker.order_id = repair.order_id
                   AND cancel_marker.logical_source = CONCAT('BAD_REVIEW_CANCEL:', task.bad_review_task_id)
             )
             AND (
                 EXISTS (
                     SELECT 1
                     FROM contractor_completion_reward_markers done_marker
                     WHERE done_marker.order_id = repair.order_id
                       AND done_marker.logical_source = CONCAT('BAD_REVIEW_DONE:', task.bad_review_task_id)
                 )
                 OR EXISTS (
                     SELECT 1
                     FROM zp reward
                     WHERE reward.zp_order = repair.order_id
                       AND reward.zp_active = 1
                       AND reward.zp_source IN (
                           CONCAT('BAD_REVIEW_DONE_MANAGER:', task.bad_review_task_id),
                           CONCAT('BAD_REVIEW_DONE_SPECIALIST:', task.bad_review_task_id)
                       )
                 )
             )
       )
   );
