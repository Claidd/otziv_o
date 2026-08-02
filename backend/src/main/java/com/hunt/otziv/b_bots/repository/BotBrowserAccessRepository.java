package com.hunt.otziv.b_bots.repository;

import com.hunt.otziv.b_bots.model.Bot;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Fresh, password-free projections used by the browser endpoints.
 *
 * <p>The worker query deliberately checks the current database state in the
 * same query that returns the safe bot metadata. This prevents a stale page or
 * already-loaded Hibernate entity from retaining access after reassignment.</p>
 */
public interface BotBrowserAccessRepository extends Repository<Bot, Long> {

    interface BrowserBotRow {
        Long getBotId();

        String getLogin();

        String getFio();
    }

    @Query(value = """
            SELECT bot.bot_id AS botId,
                   bot.bot_login AS login,
                   bot.bot_fio AS fio
            FROM bots bot
            WHERE bot.bot_id = :botId
              AND EXISTS (
                    SELECT 1
                    FROM users access_user
                    JOIN users_roles access_user_role
                      ON access_user_role.user_id = access_user.id
                    JOIN roles access_role
                      ON access_role.id = access_user_role.role_id
                    WHERE access_user.username = :username
                      AND access_user.active = 1
                      AND access_role.name IN ('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER')
              )
            """, nativeQuery = true)
    Optional<BrowserBotRow> findGloballyAccessibleBrowserBot(
            @Param("botId") long botId,
            @Param("username") String username
    );

    @Query(value = """
            SELECT bot.bot_id AS botId,
                   bot.bot_login AS login,
                   bot.bot_fio AS fio
            FROM bots bot
            WHERE bot.bot_id = :botId
              AND EXISTS (
                    SELECT 1
                    FROM users access_user
                    JOIN users_roles access_user_role
                      ON access_user_role.user_id = access_user.id
                    JOIN roles access_role
                      ON access_role.id = access_user_role.role_id
                    WHERE access_user.username = :username
                      AND access_user.active = 1
                      AND access_role.name = 'ROLE_WORKER'
              )
              AND (
                    EXISTS (
                        SELECT 1
                        FROM workers bot_owner
                        JOIN users owner_user
                          ON owner_user.id = bot_owner.user_id
                        WHERE bot_owner.worker_id = bot.bot_worker
                          AND owner_user.username = :username
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM reviews review
                        JOIN workers review_worker
                          ON review_worker.worker_id = review.review_worker
                        JOIN users review_user
                          ON review_user.id = review_worker.user_id
                        JOIN order_details detail
                          ON detail.order_detail_id = review.review_order_details
                        JOIN orders review_order
                          ON review_order.order_id = detail.order_detail_order
                        WHERE review.review_bot = bot.bot_id
                          AND review_user.username = :username
                          AND COALESCE(review.review_publish, 0) = 0
                          AND COALESCE(review_order.order_complete, 0) = 0
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM bad_review_tasks bad_task
                        JOIN workers bad_worker
                          ON bad_worker.worker_id = bad_task.bad_review_task_worker
                        JOIN users bad_user
                          ON bad_user.id = bad_worker.user_id
                        JOIN orders bad_order
                          ON bad_order.order_id = bad_task.bad_review_task_order
                         AND bad_order.order_worker = bad_task.bad_review_task_worker
                        WHERE bad_task.bad_review_task_bot = bot.bot_id
                          AND bad_user.username = :username
                          AND bad_task.bad_review_task_status = 'NEW'
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM review_recovery_tasks recovery_task
                        JOIN review_recovery_batches recovery_batch
                          ON recovery_batch.review_recovery_batch_id =
                             recovery_task.review_recovery_task_batch
                        JOIN workers recovery_worker
                          ON recovery_worker.worker_id = recovery_task.review_recovery_task_worker
                        JOIN users recovery_user
                          ON recovery_user.id = recovery_worker.user_id
                        LEFT JOIN orders recovery_order
                          ON recovery_order.order_id = recovery_task.review_recovery_task_order
                        WHERE recovery_task.review_recovery_task_bot = bot.bot_id
                          AND recovery_user.username = :username
                          AND recovery_task.review_recovery_task_status = 'PLANNED'
                          AND recovery_batch.review_recovery_batch_status = 'OPEN'
                          AND (
                                recovery_order.order_id IS NULL
                                OR recovery_order.order_worker =
                                   recovery_task.review_recovery_task_worker
                          )
                    )
              )
            """, nativeQuery = true)
    Optional<BrowserBotRow> findWorkerAccessibleBrowserBot(
            @Param("botId") long botId,
            @Param("username") String username
    );
}
