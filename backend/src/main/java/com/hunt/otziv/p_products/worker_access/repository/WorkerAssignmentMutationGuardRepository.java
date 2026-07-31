package com.hunt.otziv.p_products.worker_access.repository;

import com.hunt.otziv.u_users.model.Worker;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Fresh ownership checks for mutations initiated from a worker browser.
 *
 * <p>These queries intentionally bypass already-loaded Hibernate objects. A tab
 * opened before an atomic company transfer must not be able to mutate the former
 * worker's order after ownership has changed.</p>
 */
public interface WorkerAssignmentMutationGuardRepository
        extends Repository<Worker, Long> {

    @Query(value = """
            SELECT COUNT(*)
            FROM orders orders
            JOIN workers worker
              ON worker.worker_id = orders.order_worker
            JOIN users user
              ON user.id = worker.user_id
            WHERE orders.order_id = :orderId
              AND user.username = :username
              AND COALESCE(orders.order_complete, 0) = 0
            """, nativeQuery = true)
    long countOwnedOrder(
            @Param("orderId") long orderId,
            @Param("username") String username
    );

    @Query(value = """
            SELECT orders.order_id
            FROM orders orders
            JOIN workers worker
              ON worker.worker_id = orders.order_worker
            JOIN users user
              ON user.id = worker.user_id
            WHERE orders.order_id = :orderId
              AND user.username = :username
              AND COALESCE(orders.order_complete, 0) = 0
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> lockOwnedOrder(
            @Param("orderId") long orderId,
            @Param("username") String username
    );

    @Query(value = """
            SELECT COUNT(*)
            FROM reviews review
            JOIN workers review_worker
              ON review_worker.worker_id = review.review_worker
            JOIN users user
              ON user.id = review_worker.user_id
            JOIN order_details detail
              ON detail.order_detail_id = review.review_order_details
            JOIN orders orders
              ON orders.order_id = detail.order_detail_order
            WHERE review.review_id = :reviewId
              AND user.username = :username
              AND COALESCE(review.review_publish, 0) = 0
              AND COALESCE(orders.order_complete, 0) = 0
            """, nativeQuery = true)
    long countOwnedReview(
            @Param("reviewId") long reviewId,
            @Param("username") String username
    );

    @Query(value = """
            SELECT review.review_id
            FROM reviews review
            JOIN order_details detail
              ON detail.order_detail_id = review.review_order_details
            JOIN orders orders
              ON orders.order_id = detail.order_detail_order
            JOIN workers review_worker
              ON review_worker.worker_id = review.review_worker
            JOIN users user
              ON user.id = review_worker.user_id
            WHERE review.review_id = :reviewId
              AND user.username = :username
              AND COALESCE(review.review_publish, 0) = 0
              AND COALESCE(orders.order_complete, 0) = 0
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> lockOwnedReview(
            @Param("reviewId") long reviewId,
            @Param("username") String username
    );

    @Query(value = """
            SELECT COUNT(*)
            FROM bad_review_tasks task
            JOIN workers worker
              ON worker.worker_id = task.bad_review_task_worker
            JOIN users user
              ON user.id = worker.user_id
            JOIN orders orders
              ON orders.order_id = task.bad_review_task_order
             AND orders.order_worker = task.bad_review_task_worker
            WHERE task.bad_review_task_id = :taskId
              AND user.username = :username
              AND task.bad_review_task_status = 'NEW'
              AND COALESCE(orders.order_complete, 0) = 0
            """, nativeQuery = true)
    long countOwnedBadTask(
            @Param("taskId") long taskId,
            @Param("username") String username
    );

    @Query(value = """
            SELECT task.bad_review_task_id
            FROM bad_review_tasks task
            JOIN orders orders
              ON orders.order_id = task.bad_review_task_order
             AND orders.order_worker = task.bad_review_task_worker
            JOIN workers worker
              ON worker.worker_id = task.bad_review_task_worker
            JOIN users user
              ON user.id = worker.user_id
            WHERE task.bad_review_task_id = :taskId
              AND user.username = :username
              AND task.bad_review_task_status = 'NEW'
              AND COALESCE(orders.order_complete, 0) = 0
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> lockOwnedBadTask(
            @Param("taskId") long taskId,
            @Param("username") String username
    );

    @Query(value = """
            SELECT COUNT(*)
            FROM review_recovery_tasks task
            JOIN review_recovery_batches batch
              ON batch.review_recovery_batch_id =
                 task.review_recovery_task_batch
            JOIN workers worker
              ON worker.worker_id = task.review_recovery_task_worker
            JOIN users user
              ON user.id = worker.user_id
            LEFT JOIN orders orders
              ON orders.order_id = task.review_recovery_task_order
            WHERE task.review_recovery_task_id = :taskId
              AND user.username = :username
              AND task.review_recovery_task_status = 'PLANNED'
              AND batch.review_recovery_batch_status = 'OPEN'
              AND (
                    orders.order_id IS NULL
                    OR (
                        orders.order_worker =
                            task.review_recovery_task_worker
                    )
              )
            """, nativeQuery = true)
    long countOwnedRecoveryTask(
            @Param("taskId") long taskId,
            @Param("username") String username
    );

    @Query(value = """
            SELECT task.review_recovery_task_id
            FROM review_recovery_tasks task
            JOIN review_recovery_batches batch
              ON batch.review_recovery_batch_id =
                 task.review_recovery_task_batch
            JOIN workers worker
              ON worker.worker_id = task.review_recovery_task_worker
            JOIN users user
              ON user.id = worker.user_id
            LEFT JOIN orders orders
              ON orders.order_id = task.review_recovery_task_order
            WHERE task.review_recovery_task_id = :taskId
              AND user.username = :username
              AND task.review_recovery_task_status = 'PLANNED'
              AND batch.review_recovery_batch_status = 'OPEN'
              AND (
                    orders.order_id IS NULL
                    OR (
                        orders.order_worker =
                            task.review_recovery_task_worker
                    )
              )
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> lockOwnedRecoveryTask(
            @Param("taskId") long taskId,
            @Param("username") String username
    );
}
