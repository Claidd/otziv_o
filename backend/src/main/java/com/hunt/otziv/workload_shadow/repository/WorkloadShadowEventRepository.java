package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadShadowEventEntity;
import com.hunt.otziv.workload_shadow.repository.projection.WorkloadShadowClaimedNotificationProjection;
import com.hunt.otziv.workload_shadow.repository.projection.WorkloadShadowHealthProjection;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadShadowEventRepository
        extends Repository<WorkloadShadowEventEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_events (
                deduplication_key,
                severity,
                event_type,
                manager_id,
                worker_id,
                company_id,
                transfer_case_id,
                title,
                message,
                target_group_type,
                target_group_chat_id,
                delivery_status,
                delivery_attempts,
                occurrence_count,
                first_seen_at,
                last_seen_at,
                next_attempt_at,
                active
            )
            SELECT CONCAT(
                       'LIVE_EXECUTION_FAILURE:',
                       workflow.workload_transfer_workflow_id,
                       ':',
                       :errorCode
                   ),
                   'CRITICAL',
                   'LIVE_EXECUTION_FAILURE',
                   workflow.manager_id,
                   workflow.source_worker_id,
                   workflow.company_id,
                   workflow.shadow_case_id,
                   'Ошибка применения LIVE-передачи',
                   CONCAT(
                       'LIVE. Передача компании «',
                       COALESCE(workflow.company_title, CONCAT('#', workflow.company_id)),
                       '» не выполнена. Workflow #',
                       workflow.workload_transfer_workflow_id,
                       '. Код: ',
                       :errorCode,
                       '. ',
                       :errorMessage,
                       ' Назначения не изменены; workflow заблокирован для проверки.'
                   ),
                   'ADMIN_OWNER_MONITORING',
                   :notificationGroupChatId,
                   CASE
                       WHEN :notificationsEnabled = FALSE THEN 'SKIPPED'
                       WHEN :notificationGroupChatId IS NULL
                         OR :notificationGroupChatId >= 0
                           THEN 'MISSING_GROUP_BINDING'
                       ELSE 'PENDING'
                   END,
                   0,
                   1,
                   :now,
                   :now,
                   CASE
                       WHEN :notificationsEnabled = TRUE
                        AND :notificationGroupChatId IS NOT NULL
                        AND :notificationGroupChatId < 0
                           THEN :now
                       ELSE NULL
                   END,
                   TRUE
            FROM workload_transfer_workflows workflow
            WHERE workflow.workload_transfer_workflow_id = :workflowId
            ON DUPLICATE KEY UPDATE
                severity = 'CRITICAL',
                title = 'Ошибка применения LIVE-передачи',
                message = CONCAT(
                    'LIVE. Повторная ошибка применения workflow #',
                    :workflowId,
                    '. Код: ',
                    :errorCode,
                    '. ',
                    :errorMessage,
                    ' Назначения не изменены; workflow заблокирован для проверки.'
                ),
                target_group_type = 'ADMIN_OWNER_MONITORING',
                target_group_chat_id = :notificationGroupChatId,
                delivery_attempts = CASE
                    WHEN workload_shadow_events.active = FALSE THEN 0
                    ELSE workload_shadow_events.delivery_attempts
                END,
                delivery_status = CASE
                    WHEN :notificationsEnabled = FALSE THEN 'SKIPPED'
                    WHEN :notificationGroupChatId IS NULL
                      OR :notificationGroupChatId >= 0
                        THEN 'MISSING_GROUP_BINDING'
                    WHEN workload_shadow_events.active = TRUE
                     AND workload_shadow_events.delivery_status IN (
                         'PENDING',
                         'RETRY',
                         'PROCESSING'
                     )
                        THEN workload_shadow_events.delivery_status
                    WHEN workload_shadow_events.active = TRUE
                     AND workload_shadow_events.delivery_status = 'DEAD'
                        THEN 'DEAD'
                    WHEN workload_shadow_events.active = TRUE
                     AND workload_shadow_events.delivery_status = 'SENT'
                     AND workload_shadow_events.delivered_at >= :cooldownStart
                        THEN 'SENT'
                    ELSE 'PENDING'
                END,
                next_attempt_at = CASE
                    WHEN :notificationsEnabled = FALSE
                      OR :notificationGroupChatId IS NULL
                      OR :notificationGroupChatId >= 0
                        THEN NULL
                    WHEN workload_shadow_events.active = TRUE
                     AND workload_shadow_events.delivery_status IN (
                         'PENDING',
                         'RETRY',
                         'PROCESSING',
                         'DEAD'
                     )
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.active = TRUE
                     AND workload_shadow_events.delivery_status = 'SENT'
                     AND workload_shadow_events.delivered_at >= :cooldownStart
                        THEN workload_shadow_events.next_attempt_at
                    ELSE :now
                END,
                processing_started_at = CASE
                    WHEN workload_shadow_events.active = TRUE
                     AND workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN workload_shadow_events.processing_started_at
                    ELSE NULL
                END,
                processing_lease_until = CASE
                    WHEN workload_shadow_events.active = TRUE
                     AND workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN workload_shadow_events.processing_lease_until
                    ELSE NULL
                END,
                last_error_code = CASE
                    WHEN :notificationsEnabled = FALSE
                        THEN 'NOTIFICATIONS_DISABLED'
                    WHEN :notificationGroupChatId IS NULL
                      OR :notificationGroupChatId >= 0
                        THEN 'MISSING_GROUP_BINDING'
                    ELSE NULL
                END,
                last_error = CASE
                    WHEN :notificationsEnabled = FALSE
                        THEN 'Telegram-уведомления выключены'
                    WHEN :notificationGroupChatId IS NULL
                      OR :notificationGroupChatId >= 0
                        THEN 'Не настроена общая Telegram-группа администраторов и владельцев'
                    ELSE NULL
                END,
                occurrence_count = workload_shadow_events.occurrence_count + 1,
                last_seen_at = :now,
                active = TRUE,
                resolved_at = NULL
            """, nativeQuery = true)
    int upsertLiveExecutionFailure(
            @Param("workflowId") long workflowId,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("notificationsEnabled") boolean notificationsEnabled,
            @Param("notificationGroupChatId") Long notificationGroupChatId,
            @Param("now") LocalDateTime now,
            @Param("cooldownStart") LocalDateTime cooldownStart
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_events (
                deduplication_key,
                severity,
                event_type,
                manager_id,
                worker_id,
                company_id,
                transfer_case_id,
                title,
                message,
                target_group_type,
                target_group_chat_id,
                delivery_status,
                delivery_attempts,
                occurrence_count,
                first_seen_at,
                last_seen_at,
                next_attempt_at,
                active
            )
            SELECT CONCAT(
                       'LIVE_SINGLE_RECIPIENT_FORCED:',
                       workflow.workload_transfer_workflow_id
                   ),
                   'WARNING',
                   'LIVE_SINGLE_RECIPIENT_FORCED',
                   workflow.manager_id,
                   workflow.accepted_worker_id,
                   workflow.company_id,
                   workflow.shadow_case_id,
                   'Нужен дополнительный получатель нагрузки',
                   CONCAT(
                       'LIVE. Компания «',
                       COALESCE(workflow.company_title, CONCAT('#', workflow.company_id)),
                       '» принудительно передана специалисту ',
                       COALESCE(
                         NULLIF(TRIM(target_user.fio), ''),
                         target_user.username,
                         CONCAT('#', workflow.accepted_worker_id)
                       ),
                       ': это единственный доступный получатель у менеджера ',
                       COALESCE(
                         NULLIF(TRIM(manager_user.fio), ''),
                         manager_user.username,
                         CONCAT('#', workflow.manager_id)
                       ),
                       '. Сотрудник не принял предложение, других вариантов нет. ',
                       'Нужно подключить ещё одного получателя нагрузки для этого менеджера.'
                   ),
                   'ADMIN_OWNER_MONITORING',
                   :notificationGroupChatId,
                   CASE
                       WHEN :notificationsEnabled = FALSE THEN 'SKIPPED'
                       WHEN :notificationGroupChatId IS NULL
                         OR :notificationGroupChatId >= 0
                           THEN 'MISSING_GROUP_BINDING'
                       ELSE 'PENDING'
                   END,
                   0,
                   1,
                   :now,
                   :now,
                   CASE
                       WHEN :notificationsEnabled = TRUE
                        AND :notificationGroupChatId IS NOT NULL
                        AND :notificationGroupChatId < 0
                           THEN :now
                       ELSE NULL
                   END,
                   TRUE
            FROM workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workload_transfer_offer_id = workflow.current_offer_id
             AND offer.workflow_id = workflow.workload_transfer_workflow_id
             AND offer.candidate_worker_id = workflow.accepted_worker_id
            JOIN workers target_worker
              ON target_worker.worker_id = workflow.accepted_worker_id
            JOIN users target_user
              ON target_user.id = target_worker.user_id
            JOIN managers manager
              ON manager.manager_id = workflow.manager_id
            JOIN users manager_user
              ON manager_user.id = manager.user_id
            WHERE workflow.active = TRUE
              AND workflow.status = 'ACCEPTED'
              AND workflow.last_transition_at = :transitionedAt
              AND offer.status = 'ACCEPTED'
              AND offer.response_reason = :reason
            ON DUPLICATE KEY UPDATE
                severity = 'WARNING',
                event_type = 'LIVE_SINGLE_RECIPIENT_FORCED',
                manager_id = VALUES(manager_id),
                worker_id = VALUES(worker_id),
                company_id = VALUES(company_id),
                transfer_case_id = VALUES(transfer_case_id),
                title = VALUES(title),
                message = VALUES(message),
                target_group_type = 'ADMIN_OWNER_MONITORING',
                target_group_chat_id = VALUES(target_group_chat_id),
                delivery_attempts = CASE
                    WHEN workload_shadow_events.active = FALSE THEN 0
                    WHEN workload_shadow_events.delivery_status IN (
                        'PENDING',
                        'RETRY',
                        'PROCESSING',
                        'SENT'
                    ) THEN workload_shadow_events.delivery_attempts
                    ELSE 0
                END,
                next_attempt_at = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                      OR VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                        THEN NULL
                    WHEN workload_shadow_events.active = TRUE
                     AND workload_shadow_events.delivery_status IN (
                        'PENDING',
                        'RETRY',
                        'PROCESSING',
                        'SENT'
                     ) THEN workload_shadow_events.next_attempt_at
                    ELSE VALUES(next_attempt_at)
                END,
                delivery_status = CASE
                    WHEN workload_shadow_events.active = TRUE
                     AND workload_shadow_events.delivery_status IN (
                        'PENDING',
                        'RETRY',
                        'PROCESSING',
                        'SENT'
                     ) THEN workload_shadow_events.delivery_status
                    ELSE VALUES(delivery_status)
                END,
                processing_started_at = CASE
                    WHEN workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN workload_shadow_events.processing_started_at
                    ELSE NULL
                END,
                processing_lease_until = CASE
                    WHEN workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN workload_shadow_events.processing_lease_until
                    ELSE NULL
                END,
                last_error_code = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 'NOTIFICATIONS_DISABLED'
                    WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                        THEN 'MISSING_GROUP_BINDING'
                    ELSE workload_shadow_events.last_error_code
                END,
                last_error = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 'Telegram-уведомления SHADOW выключены; событие доступно только в мониторинге'
                    WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                        THEN 'Не настроена общая Telegram-группа администраторов и владельцев'
                    ELSE workload_shadow_events.last_error
                END,
                occurrence_count = workload_shadow_events.occurrence_count + 1,
                last_seen_at = :now,
                active = TRUE,
                resolved_at = NULL
            """, nativeQuery = true)
    int upsertSingleRecipientForcedTransferEvents(
            @Param("transitionedAt") LocalDateTime transitionedAt,
            @Param("reason") String reason,
            @Param("notificationsEnabled") boolean notificationsEnabled,
            @Param("notificationGroupChatId") Long notificationGroupChatId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_events (
                deduplication_key,
                severity,
                event_type,
                manager_id,
                worker_id,
                company_id,
                transfer_case_id,
                title,
                message,
                target_group_type,
                target_group_chat_id,
                delivery_status,
                delivery_attempts,
                occurrence_count,
                first_seen_at,
                last_seen_at,
                next_attempt_at,
                active
            )
            SELECT CONCAT(
                       'LIVE_EXHAUSTED_QUEUE_FORCED:',
                       workflow.workload_transfer_workflow_id
                   ),
                   'WARNING',
                   'LIVE_EXHAUSTED_QUEUE_FORCED',
                   workflow.manager_id,
                   workflow.accepted_worker_id,
                   workflow.company_id,
                   workflow.shadow_case_id,
                   'Очередь получателей исчерпана — применена жеребьёвка',
                   CONCAT(
                       'LIVE. Все кандидаты по компании «',
                       COALESCE(workflow.company_title, CONCAT('#', workflow.company_id)),
                       '» отказались или не ответили. Система выбрала жеребьёвкой специалиста ',
                       COALESCE(
                         NULLIF(TRIM(target_user.fio), ''),
                         target_user.username,
                         CONCAT('#', workflow.accepted_worker_id)
                       ),
                       ' из очереди лучших доступных получателей и принудительно передала весь связанный заказный пакет. ',
                       'Задачи без связанного заказа не передаются. ',
                       'Нужно проверить загрузку команды и подключить ещё одного получателя нагрузки для менеджера ',
                       COALESCE(
                         NULLIF(TRIM(manager_user.fio), ''),
                         manager_user.username,
                         CONCAT('#', workflow.manager_id)
                       ),
                       '.'
                   ),
                   'ADMIN_OWNER_MONITORING',
                   :notificationGroupChatId,
                   CASE
                       WHEN :notificationsEnabled = FALSE THEN 'SKIPPED'
                       WHEN :notificationGroupChatId IS NULL
                         OR :notificationGroupChatId >= 0
                           THEN 'MISSING_GROUP_BINDING'
                       ELSE 'PENDING'
                   END,
                   0,
                   1,
                   :now,
                   :now,
                   CASE
                       WHEN :notificationsEnabled = TRUE
                        AND :notificationGroupChatId IS NOT NULL
                        AND :notificationGroupChatId < 0
                           THEN :now
                       ELSE NULL
                   END,
                   TRUE
            FROM workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workload_transfer_offer_id = workflow.current_offer_id
             AND offer.workflow_id = workflow.workload_transfer_workflow_id
             AND offer.candidate_worker_id = workflow.accepted_worker_id
            JOIN workers target_worker
              ON target_worker.worker_id = workflow.accepted_worker_id
            JOIN users target_user
              ON target_user.id = target_worker.user_id
            JOIN managers manager
              ON manager.manager_id = workflow.manager_id
            JOIN users manager_user
              ON manager_user.id = manager.user_id
            WHERE workflow.active = TRUE
              AND workflow.status = 'ACCEPTED'
              AND workflow.last_transition_at = :transitionedAt
              AND offer.status = 'ACCEPTED'
              AND offer.response_reason = :reason
            ON DUPLICATE KEY UPDATE
                severity = 'WARNING',
                event_type = 'LIVE_EXHAUSTED_QUEUE_FORCED',
                manager_id = VALUES(manager_id),
                worker_id = VALUES(worker_id),
                company_id = VALUES(company_id),
                transfer_case_id = VALUES(transfer_case_id),
                title = VALUES(title),
                message = VALUES(message),
                target_group_type = 'ADMIN_OWNER_MONITORING',
                target_group_chat_id = VALUES(target_group_chat_id),
                delivery_attempts = CASE
                    WHEN workload_shadow_events.active = FALSE THEN 0
                    WHEN workload_shadow_events.delivery_status IN (
                        'PENDING',
                        'RETRY',
                        'PROCESSING',
                        'SENT'
                    ) THEN workload_shadow_events.delivery_attempts
                    ELSE 0
                END,
                next_attempt_at = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                      OR VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                        THEN NULL
                    WHEN workload_shadow_events.active = TRUE
                     AND workload_shadow_events.delivery_status IN (
                        'PENDING',
                        'RETRY',
                        'PROCESSING',
                        'SENT'
                     ) THEN workload_shadow_events.next_attempt_at
                    ELSE VALUES(next_attempt_at)
                END,
                delivery_status = CASE
                    WHEN workload_shadow_events.active = TRUE
                     AND workload_shadow_events.delivery_status IN (
                        'PENDING',
                        'RETRY',
                        'PROCESSING',
                        'SENT'
                     ) THEN workload_shadow_events.delivery_status
                    ELSE VALUES(delivery_status)
                END,
                processing_started_at = CASE
                    WHEN workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN workload_shadow_events.processing_started_at
                    ELSE NULL
                END,
                processing_lease_until = CASE
                    WHEN workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN workload_shadow_events.processing_lease_until
                    ELSE NULL
                END,
                last_error_code = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 'NOTIFICATIONS_DISABLED'
                    WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                        THEN 'MISSING_GROUP_BINDING'
                    ELSE workload_shadow_events.last_error_code
                END,
                last_error = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 'Telegram-уведомления SHADOW выключены; событие доступно только в мониторинге'
                    WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                        THEN 'Не настроена общая Telegram-группа администраторов и владельцев'
                    ELSE workload_shadow_events.last_error
                END,
                occurrence_count = workload_shadow_events.occurrence_count + 1,
                last_seen_at = :now,
                active = TRUE,
                resolved_at = NULL
            """, nativeQuery = true)
    int upsertExhaustedQueueForcedTransferEvents(
            @Param("transitionedAt") LocalDateTime transitionedAt,
            @Param("reason") String reason,
            @Param("notificationsEnabled") boolean notificationsEnabled,
            @Param("notificationGroupChatId") Long notificationGroupChatId,
            @Param("now") LocalDateTime now
    );

    @Query(value = """
            SELECT workload_shadow_event_id
            FROM workload_shadow_events
            WHERE active = 1
              AND target_group_type = 'ADMIN_OWNER_MONITORING'
              AND delivery_status IN ('PENDING', 'RETRY')
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY next_attempt_at, first_seen_at, workload_shadow_event_id
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findDueEventIds(
            @Param("now") LocalDateTime now,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_events
            SET delivery_status = 'PROCESSING',
                processing_started_at = :processingStartedAt,
                processing_lease_until = :leaseUntil,
                next_attempt_at = NULL
            WHERE workload_shadow_event_id IN (:eventIds)
              AND active = 1
              AND delivery_status IN ('PENDING', 'RETRY')
              AND (next_attempt_at IS NULL OR next_attempt_at <= :processingStartedAt)
            """, nativeQuery = true)
    int claimDueEvents(
            @Param("eventIds") Collection<Long> eventIds,
            @Param("processingStartedAt") LocalDateTime processingStartedAt,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Query(value = """
            SELECT wse.workload_shadow_event_id AS id,
                   wse.severity AS severity,
                   wse.event_type AS eventType,
                   wse.manager_id AS managerId,
                   wse.title AS title,
                   wse.message AS message,
                   wse.target_group_type AS targetGroupType,
                   wse.target_group_chat_id AS targetGroupChatId,
                   wse.delivery_attempts AS deliveryAttempts
            FROM workload_shadow_events wse
            WHERE wse.workload_shadow_event_id IN (:eventIds)
              AND wse.delivery_status = 'PROCESSING'
              AND wse.processing_started_at = :processingStartedAt
              AND wse.processing_lease_until = :leaseUntil
            ORDER BY wse.workload_shadow_event_id
            """, nativeQuery = true)
    List<WorkloadShadowClaimedNotificationProjection> findClaimedEvents(
            @Param("eventIds") Collection<Long> eventIds,
            @Param("processingStartedAt") LocalDateTime processingStartedAt,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_events wse
            JOIN JSON_TABLE(
                :outcomesJson,
                '$[*]' COLUMNS (
                    event_id BIGINT PATH '$.eventId',
                    delivery_status VARCHAR(32)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.deliveryStatus',
                    delivery_attempts INT PATH '$.deliveryAttempts',
                    delivered_at DATETIME(6) PATH '$.deliveredAt' NULL ON EMPTY,
                    next_attempt_at DATETIME(6) PATH '$.nextAttemptAt' NULL ON EMPTY,
                    error_code VARCHAR(80)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.errorCode' NULL ON EMPTY,
                    error_message VARCHAR(1000)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.error' NULL ON EMPTY
                )
            ) outcome
              ON outcome.event_id = wse.workload_shadow_event_id
            SET wse.delivery_status = outcome.delivery_status,
                wse.delivery_attempts = outcome.delivery_attempts,
                wse.delivered_at = CASE
                    WHEN outcome.delivery_status = 'SENT' THEN outcome.delivered_at
                    ELSE wse.delivered_at
                END,
                wse.next_attempt_at = outcome.next_attempt_at,
                wse.processing_started_at = NULL,
                wse.processing_lease_until = NULL,
                wse.last_error_code = outcome.error_code,
                wse.last_error = outcome.error_message
            WHERE wse.delivery_status = 'PROCESSING'
              AND wse.processing_started_at = :processingStartedAt
              AND wse.processing_lease_until = :leaseUntil
            """, nativeQuery = true)
    int applyDeliveryOutcomes(
            @Param("outcomesJson") String outcomesJson,
            @Param("processingStartedAt") LocalDateTime processingStartedAt,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_events
            SET delivery_status = 'SKIPPED',
                delivery_attempts = 0,
                next_attempt_at = NULL,
                processing_started_at = NULL,
                processing_lease_until = NULL,
                last_error_code = 'NOTIFICATION_BASELINE',
                last_error = 'Событие уже существовало до включения Telegram-уведомлений'
            WHERE active = 1
              AND target_group_type = 'ADMIN_OWNER_MONITORING'
            """, nativeQuery = true)
    int baselineActiveAdminOwnerEvents();

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_events
            SET delivery_status = 'CANCELLED',
                next_attempt_at = NULL,
                processing_started_at = NULL,
                processing_lease_until = NULL,
                last_error_code = 'EVENT_RESOLVED',
                last_error = 'Событие закрыто до отправки',
                resolved_at = COALESCE(resolved_at, :now)
            WHERE active = 0
              AND (
                delivery_status IN ('PENDING', 'RETRY')
                OR (
                  delivery_status = 'PROCESSING'
                  AND (
                    processing_lease_until IS NULL
                    OR processing_lease_until < :now
                  )
                )
              )
            ORDER BY workload_shadow_event_id
            LIMIT :batchSize
            """, nativeQuery = true)
    int cancelInactiveDeliveries(
            @Param("now") LocalDateTime now,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_events
            SET delivery_status = 'RETRY',
                next_attempt_at = :now,
                processing_started_at = NULL,
                processing_lease_until = NULL,
                last_error_code = 'STALE_PROCESSING_LEASE',
                last_error = 'Зависшая доставка автоматически возвращена в очередь'
            WHERE active = 1
              AND delivery_status = 'PROCESSING'
              AND (
                processing_lease_until IS NULL
                OR processing_lease_until < :now
              )
            ORDER BY processing_lease_until
            LIMIT :batchSize
            """, nativeQuery = true)
    int retryStaleProcessingEvents(
            @Param("now") LocalDateTime now,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query(value = """
            DELETE FROM workload_shadow_events
            WHERE (
                    active = 0
                    OR (
                        active = 1
                        AND event_type = 'LIVE_EXECUTION_FAILURE'
                    )
              )
              AND delivery_status IN (
                'SENT',
                'CANCELLED',
                'SKIPPED',
                'RESOLVED',
                'DEAD',
                'MISSING_GROUP_BINDING'
              )
              AND last_seen_at < :cutoff
            ORDER BY last_seen_at
            LIMIT :batchSize
            """, nativeQuery = true)
    int deleteTerminalInactiveEvents(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("batchSize") int batchSize
    );

    @Query(value = """
            SELECT event_counts.due_events AS dueEvents,
                   event_counts.processing_events AS processingEvents,
                   event_counts.stale_processing_events AS staleProcessingEvents,
                   event_counts.dead_events AS deadEvents,
                   event_counts.missing_group_bindings AS missingGroupBindings,
                   run_counts.running_runs AS runningRuns,
                   run_counts.stale_running_runs AS staleRunningRuns,
                   graph_counts.graph_warning_cases AS graphWarningCases,
                   graph_counts.graph_error_cases AS graphErrorCases,
                   lock_counts.expired_recalculation_locks AS expiredRecalculationLocks,
                   event_counts.oldest_due_event_at AS oldestDueEventAt,
                   run_counts.last_successful_run_at AS lastSuccessfulRunAt,
                   snapshot_counts.last_snapshot_at AS lastSnapshotAt
            FROM (
                SELECT COUNT(CASE
                           WHEN active = 1
                            AND delivery_status IN ('PENDING', 'RETRY')
                            AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                           THEN 1
                       END) AS due_events,
                       COUNT(CASE
                           WHEN active = 1
                            AND delivery_status = 'PROCESSING'
                           THEN 1
                       END) AS processing_events,
                       COUNT(CASE
                           WHEN active = 1
                            AND delivery_status = 'PROCESSING'
                            AND (
                              processing_lease_until IS NULL
                              OR processing_lease_until < :now
                            )
                           THEN 1
                       END) AS stale_processing_events,
                       COUNT(CASE
                           WHEN active = 1
                            AND delivery_status = 'DEAD'
                           THEN 1
                       END) AS dead_events,
                       COUNT(CASE
                           WHEN active = 1
                            AND (
                              delivery_status = 'MISSING_GROUP_BINDING'
                              OR (
                                delivery_status = 'DEAD'
                                AND last_error_code = 'MISSING_GROUP_BINDING'
                              )
                            )
                           THEN 1
                       END) AS missing_group_bindings,
                       MIN(CASE
                           WHEN active = 1
                            AND delivery_status IN ('PENDING', 'RETRY')
                            AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                           THEN first_seen_at
                       END) AS oldest_due_event_at
                FROM workload_shadow_events
                WHERE event_type <> 'MISSING_MANAGER_GROUP'
            ) event_counts
            CROSS JOIN (
                SELECT COUNT(CASE
                           WHEN status = 'RUNNING'
                           THEN 1
                       END) AS running_runs,
                       COUNT(CASE
                           WHEN status = 'RUNNING'
                            AND started_at < :staleRunBefore
                           THEN 1
                       END) AS stale_running_runs,
                       MAX(CASE
                           WHEN status IN ('SUCCESS', 'SUCCEEDED')
                           THEN finished_at
                       END) AS last_successful_run_at
                FROM workload_shadow_runs
            ) run_counts
            CROSS JOIN (
                SELECT COUNT(CASE
                           WHEN active = 1
                            AND graph_warning_count > 0
                           THEN 1
                       END) AS graph_warning_cases,
                       COUNT(CASE
                           WHEN active = 1
                            AND graph_error_count > 0
                           THEN 1
                       END) AS graph_error_cases
                FROM workload_shadow_transfer_cases
            ) graph_counts
            CROSS JOIN (
                SELECT COUNT(*) AS expired_recalculation_locks
                FROM workload_shadow_recalculation_locks
                WHERE owner_token IS NOT NULL
                  AND lease_until <= CURRENT_TIMESTAMP(6)
            ) lock_counts
            CROSS JOIN (
                SELECT MAX(snapshot_at) AS last_snapshot_at
                FROM workload_shadow_worker_current
            ) snapshot_counts
            """, nativeQuery = true)
    WorkloadShadowHealthProjection healthData(
            @Param("now") LocalDateTime now,
            @Param("staleRunBefore") LocalDateTime staleRunBefore
    );
}
