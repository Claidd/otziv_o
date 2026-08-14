package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadTransferOfferEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadTransferOfferRepository
        extends Repository<WorkloadTransferOfferEntity, Long> {

    /**
     * Atomically removes stale candidates from the head of live queues.
     * Candidate ranking is a snapshot, while eligibility and Telegram routing
     * are live facts and must be revalidated before an offer is staged.
     */
    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflow_candidates candidate
            JOIN workload_transfer_workflows workflow
              ON workflow.workload_transfer_workflow_id =
                 candidate.workflow_id
            LEFT JOIN workload_shadow_worker_current candidate_current
              ON candidate_current.worker_id = candidate.worker_id
             AND candidate_current.manager_id = workflow.manager_id
            LEFT JOIN workers candidate_worker
              ON candidate_worker.worker_id = candidate.worker_id
            LEFT JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
            SET candidate.status = 'UNAVAILABLE',
                candidate.last_responded_at = :now,
                candidate.response_reason = CASE
                    WHEN candidate_current.worker_id IS NULL
                    THEN 'Исключён: сотрудник больше не состоит в команде менеджера'
                    WHEN candidate_current.recipient_eligible <> TRUE
                    THEN 'Исключён: сотрудник не проходит текущие критерии получения компаний'
                    WHEN candidate_current.accepts_company_transfers <> TRUE
                    THEN 'Исключён: сотрудник отключил получение компаний'
                    WHEN candidate_current.worker_group_connected <> TRUE
                    THEN 'Исключён: рабочая Telegram-группа сотрудника не подключена'
                    WHEN candidate_user.id IS NULL
                    THEN 'Исключён: профиль сотрудника недоступен'
                    WHEN COALESCE(candidate_user.worker_telegram_group_chat_id, 0)
                         <> candidate.target_group_chat_id
                    THEN 'Исключён: изменилась рабочая Telegram-группа сотрудника'
                    WHEN COALESCE(candidate_user.telegram_chat_id, 0)
                         <> candidate.candidate_telegram_id
                    THEN 'Исключён: изменился Telegram-профиль сотрудника'
                    ELSE 'Исключён: сотрудник временно недоступен для передачи'
                END,
                candidate.updated_at = :now
            WHERE workflow.active = TRUE
              AND workflow.status = 'READY_TO_OFFER'
              AND candidate.status = 'WAITING'
              AND (
                    candidate_current.worker_id IS NULL
                    OR candidate_current.recipient_eligible <> TRUE
                    OR candidate_current.accepts_company_transfers <> TRUE
                    OR candidate_current.worker_group_connected <> TRUE
                    OR candidate_user.id IS NULL
                    OR COALESCE(
                         candidate_user.worker_telegram_group_chat_id,
                         0
                       ) <> candidate.target_group_chat_id
                    OR COALESCE(candidate_user.telegram_chat_id, 0)
                       <> candidate.candidate_telegram_id
              )
            """, nativeQuery = true)
    int skipUnavailableWaitingCandidates(@Param("now") LocalDateTime now);

    /**
     * Revalidates a staged, but not yet delivered, offer against current
     * eligibility and Telegram routing.  The workflow, offer and candidate are
     * released in one statement so another candidate can be staged safely.
     */
    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workflow_id =
                 workflow.workload_transfer_workflow_id
             AND workflow.current_offer_id =
                 offer.workload_transfer_offer_id
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id =
                 offer.workflow_candidate_id
            LEFT JOIN workload_shadow_worker_current candidate_current
              ON candidate_current.worker_id = candidate.worker_id
             AND candidate_current.manager_id = workflow.manager_id
            LEFT JOIN workers candidate_worker
              ON candidate_worker.worker_id = candidate.worker_id
            LEFT JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
            SET offer.status = 'CANCELLED',
                offer.responded_at = :now,
                offer.response_reason =
                    'Получатель исключён до доставки предложения',
                offer.next_attempt_at = NULL,
                offer.processing_token = NULL,
                offer.processing_lease_until = NULL,
                offer.last_error_code = 'RECIPIENT_UNAVAILABLE',
                offer.last_error =
                    'Получатель исключён до доставки предложения',
                offer.updated_at = :now,
                workflow.status = 'READY_TO_OFFER',
                workflow.current_offer_id = NULL,
                workflow.workflow_version = workflow.workflow_version + 1,
                workflow.last_transition_at = :now,
                workflow.updated_at = :now,
                candidate.status = 'UNAVAILABLE',
                candidate.last_responded_at = :now,
                candidate.response_reason = CASE
                    WHEN candidate_current.worker_id IS NULL
                    THEN 'Исключён: сотрудник больше не состоит в команде менеджера'
                    WHEN candidate_current.recipient_eligible <> TRUE
                    THEN 'Исключён: сотрудник не проходит текущие критерии получения компаний'
                    WHEN candidate_current.accepts_company_transfers <> TRUE
                    THEN 'Исключён: сотрудник отключил получение компаний'
                    WHEN candidate_current.worker_group_connected <> TRUE
                    THEN 'Исключён: рабочая Telegram-группа сотрудника не подключена'
                    WHEN candidate_user.id IS NULL
                    THEN 'Исключён: профиль сотрудника недоступен'
                    WHEN COALESCE(candidate_user.worker_telegram_group_chat_id, 0)
                         <> candidate.target_group_chat_id
                    THEN 'Исключён: изменилась рабочая Telegram-группа сотрудника'
                    WHEN COALESCE(candidate_user.telegram_chat_id, 0)
                         <> candidate.candidate_telegram_id
                    THEN 'Исключён: изменился Telegram-профиль сотрудника'
                    ELSE 'Исключён: сотрудник временно недоступен для передачи'
                END,
                candidate.updated_at = :now
            WHERE workflow.active = TRUE
              AND workflow.status = 'OFFERED'
              AND candidate.status = 'OFFERED'
              AND offer.status IN ('READY', 'RETRY', 'SENDING')
              AND (
                    offer.status <> 'SENDING'
                    OR offer.processing_lease_until IS NULL
                    OR offer.processing_lease_until <= :now
              )
              AND (
                    candidate_current.worker_id IS NULL
                    OR candidate_current.recipient_eligible <> TRUE
                    OR candidate_current.accepts_company_transfers <> TRUE
                    OR candidate_current.worker_group_connected <> TRUE
                    OR candidate_user.id IS NULL
                    OR COALESCE(
                         candidate_user.worker_telegram_group_chat_id,
                         0
                       ) <> candidate.target_group_chat_id
                    OR COALESCE(candidate_user.telegram_chat_id, 0)
                       <> candidate.candidate_telegram_id
              )
            """, nativeQuery = true)
    int releaseUnavailableUndeliveredOffers(@Param("now") LocalDateTime now);

    @Query(value = """
            SELECT workflow.workload_transfer_workflow_id AS workflowId,
                   workflow.manager_id AS managerId,
                   workflow.source_worker_id AS sourceWorkerId,
                   workflow.company_id AS companyId,
                   workflow.company_title AS companyTitle,
                   workflow.problem_units AS problemUnits,
                   workflow.estimated_minutes AS estimatedMinutes,
                   workflow.active_order_count AS activeOrderCount,
                   workflow.new_unit_count AS newUnitCount,
                   workflow.correction_count AS correctionCount,
                   workflow.nagul_count AS nagulCount,
                   workflow.publish_count AS publishCount,
                   workflow.recovery_count AS recoveryCount,
                   workflow.bad_count AS badCount,
                   candidate.workload_transfer_workflow_candidate_id AS candidateId,
                   candidate.worker_id AS candidateWorkerId,
                   candidate.sequence_number AS sequenceNumber,
                   COALESCE(
                     NULLIF(TRIM(candidate_user.fio), ''),
                     candidate_user.username,
                     CONCAT('Специалист #', candidate.worker_id)
                   ) AS candidateWorkerName,
                   candidate.target_group_chat_id AS targetGroupChatId,
                   candidate.candidate_telegram_id AS candidateTelegramId
            FROM workload_transfer_workflows workflow
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workflow_id =
                 workflow.workload_transfer_workflow_id
            JOIN workload_shadow_worker_current candidate_current
              ON candidate_current.worker_id = candidate.worker_id
             AND candidate_current.manager_id = workflow.manager_id
            JOIN workers candidate_worker
              ON candidate_worker.worker_id = candidate.worker_id
            JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
            WHERE workflow.active = TRUE
              AND workflow.status = 'READY_TO_OFFER'
              AND candidate.status = 'WAITING'
              AND candidate_current.recipient_eligible = TRUE
              AND candidate_current.accepts_company_transfers = TRUE
              AND candidate_current.worker_group_connected = TRUE
              AND candidate_user.worker_telegram_group_chat_id =
                  candidate.target_group_chat_id
              AND candidate_user.telegram_chat_id =
                  candidate.candidate_telegram_id
              AND NOT EXISTS (
                  SELECT 1
                  FROM workload_transfer_workflow_candidates earlier
                  WHERE earlier.workflow_id = candidate.workflow_id
                    AND earlier.sequence_number < candidate.sequence_number
                    AND earlier.status IN ('WAITING', 'OFFERED', 'ACCEPTED')
              )
            ORDER BY workflow.manager_id,
                     workflow.failure_number DESC,
                     workflow.selection_rank,
                     candidate.sequence_number,
                     workflow.workload_transfer_workflow_id
            """, nativeQuery = true)
    List<StageCandidateProjection> findStageCandidates();

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO workload_transfer_offers (
                workflow_id,
                workflow_candidate_id,
                candidate_worker_id,
                sequence_number,
                offer_token,
                status,
                workflow_version,
                target_group_chat_id,
                delivery_deadline_at,
                expires_at,
                next_attempt_at,
                created_at,
                updated_at
            )
            SELECT workflow.workload_transfer_workflow_id,
                   candidate.workload_transfer_workflow_candidate_id,
                   candidate.worker_id,
                   candidate.sequence_number,
                   :offerToken,
                   'READY',
                   workflow.workflow_version + 1,
                   candidate.target_group_chat_id,
                   :deliveryDeadlineAt,
                   NULL,
                   :now,
                   :now,
                   :now
            FROM workload_transfer_workflows workflow
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id = :candidateId
             AND candidate.workflow_id =
                 workflow.workload_transfer_workflow_id
            JOIN workload_shadow_worker_current candidate_current
              ON candidate_current.worker_id = candidate.worker_id
             AND candidate_current.manager_id = workflow.manager_id
            JOIN workers candidate_worker
              ON candidate_worker.worker_id = candidate.worker_id
            JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
            WHERE workflow.workload_transfer_workflow_id = :workflowId
              AND workflow.active = TRUE
              AND workflow.status = 'READY_TO_OFFER'
              AND candidate.status = 'WAITING'
              AND candidate_current.recipient_eligible = TRUE
              AND candidate_current.accepts_company_transfers = TRUE
              AND candidate_current.worker_group_connected = TRUE
              AND candidate_user.worker_telegram_group_chat_id =
                  candidate.target_group_chat_id
              AND candidate_user.telegram_chat_id =
                  candidate.candidate_telegram_id
            """, nativeQuery = true)
    int insertOffer(
            @Param("workflowId") long workflowId,
            @Param("candidateId") long candidateId,
            @Param("offerToken") String offerToken,
            @Param("now") LocalDateTime now,
            @Param("deliveryDeadlineAt") LocalDateTime deliveryDeadlineAt
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workflow_id =
                 workflow.workload_transfer_workflow_id
             AND offer.offer_token = :offerToken
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id =
                 offer.workflow_candidate_id
            SET workflow.status = 'OFFERED',
                workflow.current_offer_id = offer.workload_transfer_offer_id,
                workflow.workflow_version = workflow.workflow_version + 1,
                workflow.last_transition_at = :now,
                workflow.updated_at = :now,
                candidate.status = 'OFFERED',
                candidate.last_offered_at = :now,
                candidate.response_reason = NULL,
                candidate.updated_at = :now
            WHERE workflow.status = 'READY_TO_OFFER'
              AND offer.status = 'READY'
            """, nativeQuery = true)
    int markWorkflowOffered(
            @Param("offerToken") String offerToken,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO workload_transfer_offers (
                workflow_id,
                workflow_candidate_id,
                candidate_worker_id,
                sequence_number,
                offer_token,
                status,
                workflow_version,
                staging_batch_token,
                target_group_chat_id,
                delivery_deadline_at,
                expires_at,
                next_attempt_at,
                created_at,
                updated_at
            )
            SELECT workflow.workload_transfer_workflow_id,
                   candidate.workload_transfer_workflow_candidate_id,
                   candidate.worker_id,
                   candidate.sequence_number,
                   UUID(),
                   'READY',
                   workflow.workflow_version + 1,
                   :stagingBatchToken,
                   candidate.target_group_chat_id,
                   :deliveryDeadlineAt,
                   NULL,
                   :now,
                   :now,
                   :now
            FROM workload_transfer_workflows workflow
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workflow_id =
                 workflow.workload_transfer_workflow_id
            JOIN workload_shadow_worker_current candidate_current
              ON candidate_current.worker_id = candidate.worker_id
             AND candidate_current.manager_id = workflow.manager_id
            JOIN workers candidate_worker
              ON candidate_worker.worker_id = candidate.worker_id
            JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
            WHERE workflow.active = TRUE
              AND workflow.status = 'READY_TO_OFFER'
              AND workflow.current_offer_id IS NULL
              AND candidate.status = 'WAITING'
              AND candidate_current.recipient_eligible = TRUE
              AND candidate_current.accepts_company_transfers = TRUE
              AND candidate_current.worker_group_connected = TRUE
              AND candidate_user.worker_telegram_group_chat_id =
                  candidate.target_group_chat_id
              AND candidate_user.telegram_chat_id =
                  candidate.candidate_telegram_id
              AND (
                    :allManagers = TRUE
                    OR EXISTS (
                        SELECT 1
                        FROM JSON_TABLE(
                            :managerIdsJson,
                            '$[*]' COLUMNS (
                                manager_id BIGINT PATH '$'
                            )
                        ) allowed_manager
                        WHERE allowed_manager.manager_id =
                              workflow.manager_id
                    )
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM workload_transfer_workflow_candidates earlier
                  WHERE earlier.workflow_id = candidate.workflow_id
                    AND earlier.sequence_number < candidate.sequence_number
                    AND earlier.status IN ('WAITING', 'OFFERED', 'ACCEPTED')
              )
            ORDER BY workflow.manager_id,
                     workflow.failure_number DESC,
                     workflow.selection_rank,
                     candidate.sequence_number,
                     workflow.workload_transfer_workflow_id
            LIMIT :rowLimit
            """, nativeQuery = true)
    int insertEligibleOfferBatch(
            @Param("allManagers") boolean allManagers,
            @Param("managerIdsJson") String managerIdsJson,
            @Param("stagingBatchToken") String stagingBatchToken,
            @Param("now") LocalDateTime now,
            @Param("deliveryDeadlineAt") LocalDateTime deliveryDeadlineAt,
            @Param("rowLimit") int rowLimit
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workflow_id =
                 workflow.workload_transfer_workflow_id
             AND offer.status = 'READY'
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id =
                 offer.workflow_candidate_id
            JOIN workload_shadow_worker_current candidate_current
              ON candidate_current.worker_id = candidate.worker_id
             AND candidate_current.manager_id = workflow.manager_id
            JOIN workers candidate_worker
              ON candidate_worker.worker_id = candidate.worker_id
            JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
            SET workflow.status = 'OFFERED',
                workflow.current_offer_id =
                    offer.workload_transfer_offer_id,
                workflow.workflow_version = workflow.workflow_version + 1,
                workflow.last_transition_at = :now,
                workflow.updated_at = :now,
                candidate.status = 'OFFERED',
                candidate.last_offered_at = :now,
                candidate.response_reason = NULL,
                candidate.updated_at = :now
            WHERE workflow.active = TRUE
              AND workflow.status = 'READY_TO_OFFER'
              AND workflow.current_offer_id IS NULL
              AND offer.staging_batch_token = :stagingBatchToken
              AND candidate.status = 'WAITING'
              AND candidate_current.recipient_eligible = TRUE
              AND candidate_current.accepts_company_transfers = TRUE
              AND candidate_current.worker_group_connected = TRUE
              AND candidate_user.worker_telegram_group_chat_id =
                  candidate.target_group_chat_id
              AND candidate_user.telegram_chat_id =
                  candidate.candidate_telegram_id
              AND (
                    :allManagers = TRUE
                    OR EXISTS (
                        SELECT 1
                        FROM JSON_TABLE(
                            :managerIdsJson,
                            '$[*]' COLUMNS (
                                manager_id BIGINT PATH '$'
                            )
                        ) allowed_manager
                        WHERE allowed_manager.manager_id =
                              workflow.manager_id
                    )
              )
            """, nativeQuery = true)
    int markReadyOfferBatchOffered(
            @Param("allManagers") boolean allManagers,
            @Param("managerIdsJson") String managerIdsJson,
            @Param("stagingBatchToken") String stagingBatchToken,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_offers
            SET status = 'SENDING',
                processing_token = :processingToken,
                processing_lease_until = :leaseUntil,
                delivery_attempts = delivery_attempts + 1,
                updated_at = :now
            WHERE status IN ('READY', 'RETRY')
              AND next_attempt_at <= :now
              AND delivery_deadline_at > :now
              AND EXISTS (
                  SELECT 1
                  FROM workload_transfer_workflows allowed_workflow
                  WHERE allowed_workflow.workload_transfer_workflow_id =
                        workload_transfer_offers.workflow_id
                    AND allowed_workflow.active = TRUE
                    AND allowed_workflow.status = 'OFFERED'
                    AND (
                          :allManagers = TRUE
                          OR EXISTS (
                              SELECT 1
                              FROM JSON_TABLE(
                                  :managerIdsJson,
                                  '$[*]' COLUMNS (
                                      manager_id BIGINT PATH '$'
                                  )
                              ) allowed_manager
                              WHERE allowed_manager.manager_id =
                                    allowed_workflow.manager_id
                          )
                    )
              )
              AND (
                    processing_lease_until IS NULL
                    OR processing_lease_until < :now
              )
            ORDER BY next_attempt_at, workload_transfer_offer_id
            LIMIT :rowLimit
            """, nativeQuery = true)
    int claimDueOffers(
            @Param("processingToken") String processingToken,
            @Param("allManagers") boolean allManagers,
            @Param("managerIdsJson") String managerIdsJson,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("rowLimit") int rowLimit
    );

    @Query(value = """
            SELECT offer.workload_transfer_offer_id AS offerId,
                   offer.offer_token AS offerToken,
                   offer.target_group_chat_id AS targetGroupChatId,
                   offer.delivery_attempts AS deliveryAttempts,
                   workflow.manager_id AS managerId,
                   workflow.company_title AS companyTitle,
                   workflow.problem_units AS problemUnits,
                   workflow.estimated_minutes AS estimatedMinutes,
                   workflow.active_order_count AS activeOrderCount,
                   workflow.new_unit_count AS newUnitCount,
                   workflow.correction_count AS correctionCount,
                   workflow.nagul_count AS nagulCount,
                   workflow.publish_count AS publishCount,
                   workflow.recovery_count AS recoveryCount,
                   workflow.bad_count AS badCount,
                   COALESCE(
                     NULLIF(TRIM(source_user.fio), ''),
                     source_user.username,
                     CONCAT('Специалист #', workflow.source_worker_id)
                   ) AS sourceWorkerName,
                   COALESCE(
                     NULLIF(TRIM(candidate_user.fio), ''),
                     candidate_user.username,
                     CONCAT('Специалист #', offer.candidate_worker_id)
                   ) AS candidateWorkerName,
                   offer.delivery_deadline_at AS deliveryDeadlineAt
            FROM workload_transfer_offers offer
            JOIN workload_transfer_workflows workflow
              ON workflow.workload_transfer_workflow_id = offer.workflow_id
            JOIN workers source_worker
              ON source_worker.worker_id = workflow.source_worker_id
            JOIN users source_user
              ON source_user.id = source_worker.user_id
            JOIN workers candidate_worker
              ON candidate_worker.worker_id = offer.candidate_worker_id
            JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
            WHERE offer.processing_token = :processingToken
              AND offer.status = 'SENDING'
            ORDER BY offer.workload_transfer_offer_id
            """, nativeQuery = true)
    List<DeliveryProjection> findClaimedOffers(
            @Param("processingToken") String processingToken
    );

    /**
     * Cancels one claimed delivery subset in a single guarded statement when
     * the manager left the current LIVE/CANARY scope after the batch was
     * claimed. The caller computes the subset from one current settings
     * snapshot, avoiding a settings query per offer.
     */
    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workflow_id =
                 workflow.workload_transfer_workflow_id
             AND workflow.current_offer_id =
                 offer.workload_transfer_offer_id
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id =
                 offer.workflow_candidate_id
            SET offer.status = 'CANCELLED',
                offer.responded_at = CURRENT_TIMESTAMP(6),
                offer.response_reason = :reason,
                offer.next_attempt_at = NULL,
                offer.processing_token = NULL,
                offer.processing_lease_until = NULL,
                offer.last_error_code = 'MANAGER_OUTSIDE_LIVE_SCOPE',
                offer.last_error = :reason,
                offer.updated_at = CURRENT_TIMESTAMP(6),
                workflow.status = 'CANCELLED',
                workflow.active = FALSE,
                workflow.current_offer_id = NULL,
                workflow.accepted_worker_id = NULL,
                workflow.workflow_version = workflow.workflow_version + 1,
                workflow.last_transition_at = CURRENT_TIMESTAMP(6),
                workflow.resolved_at = CURRENT_TIMESTAMP(6),
                workflow.updated_at = CURRENT_TIMESTAMP(6),
                candidate.status = 'CANCELLED',
                candidate.last_responded_at = CURRENT_TIMESTAMP(6),
                candidate.response_reason = :reason,
                candidate.updated_at = CURRENT_TIMESTAMP(6)
            WHERE offer.processing_token = :processingToken
              AND offer.workload_transfer_offer_id IN (:offerIds)
              AND offer.status = 'SENDING'
              AND workflow.active = TRUE
              AND workflow.status = 'OFFERED'
            """, nativeQuery = true)
    int cancelClaimedOffersOutsideScope(
            @Param("processingToken") String processingToken,
            @Param("offerIds") List<Long> offerIds,
            @Param("reason") String reason
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_offers
            SET status = 'OFFERED',
                telegram_message_id = :messageId,
                keyboard_activated = FALSE,
                offered_at = :now,
                expires_at = :expiresAt,
                processing_token = NULL,
                processing_lease_until = NULL,
                last_error_code = NULL,
                last_error = NULL,
                updated_at = :now
            WHERE workload_transfer_offer_id = :offerId
              AND processing_token = :processingToken
              AND status = 'SENDING'
            """, nativeQuery = true)
    int markDelivered(
            @Param("offerId") long offerId,
            @Param("processingToken") String processingToken,
            @Param("messageId") int messageId,
            @Param("now") LocalDateTime now,
            @Param("expiresAt") LocalDateTime expiresAt
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_offers
            SET keyboard_activated = TRUE,
                updated_at = :now
            WHERE workload_transfer_offer_id = :offerId
              AND telegram_message_id = :messageId
              AND status = 'OFFERED'
              AND keyboard_activated = FALSE
            """, nativeQuery = true)
    int markKeyboardActivated(
            @Param("offerId") long offerId,
            @Param("messageId") int messageId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_offers
            SET status = CASE
                    WHEN delivery_attempts >= :maxAttempts
                         OR delivery_deadline_at <= :now
                    THEN 'DELIVERY_FAILED'
                    ELSE 'RETRY'
                END,
                next_attempt_at = CASE
                    WHEN delivery_attempts >= :maxAttempts
                         OR delivery_deadline_at <= :now
                    THEN NULL
                    ELSE :nextAttemptAt
                END,
                processing_token = NULL,
                processing_lease_until = NULL,
                last_error_code = :errorCode,
                last_error = :errorMessage,
                updated_at = :now
            WHERE workload_transfer_offer_id = :offerId
              AND processing_token = :processingToken
              AND status = 'SENDING'
            """, nativeQuery = true)
    int markDeliveryFailure(
            @Param("offerId") long offerId,
            @Param("processingToken") String processingToken,
            @Param("maxAttempts") int maxAttempts,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("now") LocalDateTime now
    );


    @Modifying
    @Query(value = """
            UPDATE workload_transfer_offers
            SET status = 'DELIVERY_FAILED',
                keyboard_activated = FALSE,
                responded_at = :now,
                response_reason = 'Не удалось активировать кнопки Telegram',
                next_attempt_at = NULL,
                last_error_code = 'TELEGRAM_KEYBOARD_ACTIVATION_FAILED',
                last_error = 'Сообщение сохранено, но кнопки не активированы',
                updated_at = :now
            WHERE workload_transfer_offer_id = :offerId
              AND telegram_message_id = :messageId
              AND status = 'OFFERED'
              AND keyboard_activated = FALSE
            """, nativeQuery = true)
    int markKeyboardActivationFailure(
            @Param("offerId") long offerId,
            @Param("messageId") int messageId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_offers
            SET status = 'DELIVERY_FAILED',
                keyboard_activated = FALSE,
                responded_at = :now,
                response_reason = 'Истёк срок активации кнопок Telegram',
                next_attempt_at = NULL,
                last_error_code = 'TELEGRAM_KEYBOARD_ACTIVATION_TIMEOUT',
                last_error = 'Кнопки не были активированы после доставки',
                processing_token = NULL,
                processing_lease_until = NULL,
                updated_at = :now
            WHERE status = 'OFFERED'
              AND keyboard_activated = FALSE
              AND offered_at <= :activationDeadline
            """, nativeQuery = true)
    int failInactiveKeyboardOffers(
            @Param("activationDeadline") LocalDateTime activationDeadline,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workflow_id =
                 workflow.workload_transfer_workflow_id
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id =
                 offer.workflow_candidate_id
            SET workflow.status = 'READY_TO_OFFER',
                workflow.current_offer_id = NULL,
                workflow.last_transition_at = :now,
                workflow.updated_at = :now,
                candidate.status = 'DELIVERY_FAILED',
                candidate.last_responded_at = :now,
                candidate.response_reason = offer.last_error,
                candidate.updated_at = :now
            WHERE offer.status = 'DELIVERY_FAILED'
              AND workflow.status = 'OFFERED'
              AND workflow.current_offer_id =
                  offer.workload_transfer_offer_id
            """, nativeQuery = true)
    int releaseDeliveryFailedWorkflows(@Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workflow_id =
                 workflow.workload_transfer_workflow_id
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id =
                 offer.workflow_candidate_id
            SET offer.status = 'DELIVERY_FAILED',
                offer.responded_at = :now,
                offer.response_reason =
                    'Не удалось доставить предложение до технического срока',
                offer.next_attempt_at = NULL,
                offer.processing_token = NULL,
                offer.processing_lease_until = NULL,
                offer.last_error_code = 'DELIVERY_DEADLINE_EXPIRED',
                offer.last_error =
                    'Не удалось доставить предложение до технического срока',
                offer.updated_at = :now,
                workflow.status = 'READY_TO_OFFER',
                workflow.current_offer_id = NULL,
                workflow.last_transition_at = :now,
                workflow.updated_at = :now,
                candidate.status = 'DELIVERY_FAILED',
                candidate.last_responded_at = :now,
                candidate.response_reason =
                    'Не удалось доставить предложение до технического срока',
                candidate.updated_at = :now
            WHERE offer.status IN ('READY', 'RETRY', 'SENDING')
              AND offer.delivery_deadline_at <= :now
              AND (
                    offer.status <> 'SENDING'
                    OR offer.processing_lease_until IS NULL
                    OR offer.processing_lease_until <= :now
              )
              AND workflow.status = 'OFFERED'
              AND workflow.current_offer_id =
                  offer.workload_transfer_offer_id
            """, nativeQuery = true)
    int expireUndeliveredOffers(@Param("now") LocalDateTime now);

    @Query(value = """
            SELECT offer.workload_transfer_offer_id
            FROM workload_transfer_offers offer
            JOIN workload_transfer_workflows workflow
              ON workflow.workload_transfer_workflow_id =
                 offer.workflow_id
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id =
                 offer.workflow_candidate_id
            WHERE offer.status = 'OFFERED'
              AND offer.expires_at <= :now
              AND workflow.status = 'OFFERED'
              AND workflow.current_offer_id =
                  offer.workload_transfer_offer_id
            ORDER BY offer.workload_transfer_offer_id
            FOR UPDATE
            """, nativeQuery = true)
    List<Long> lockDueOfferIds(@Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workflow_id =
                 workflow.workload_transfer_workflow_id
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id =
                 offer.workflow_candidate_id
            SET offer.status = 'EXPIRED',
                offer.responded_at = :now,
                offer.response_reason = 'Истёк срок ответа',
                offer.updated_at = :now,
                workflow.status = 'READY_TO_OFFER',
                workflow.current_offer_id = NULL,
                workflow.last_transition_at = :now,
                workflow.updated_at = :now,
                candidate.status = 'EXPIRED',
                candidate.last_responded_at = :now,
                candidate.response_reason = 'Истёк срок ответа',
                candidate.updated_at = :now
            WHERE offer.status = 'OFFERED'
              AND offer.expires_at <= :now
              AND workflow.status = 'OFFERED'
              AND workflow.current_offer_id =
                  offer.workload_transfer_offer_id
            """, nativeQuery = true)
    int expireDueOffers(@Param("now") LocalDateTime now);

    @Query(value = """
            SELECT offer.workload_transfer_offer_id AS offerId,
                   offer.offer_token AS offerToken,
                   offer.status AS offerStatus,
                   offer.target_group_chat_id AS targetGroupChatId,
                   offer.telegram_message_id AS telegramMessageId,
                   offer.expires_at AS expiresAt,
                   offer.candidate_worker_id AS candidateWorkerId,
                   candidate_user.id AS candidateUserId,
                   candidate_user.telegram_chat_id AS candidateTelegramId,
                   workflow.workload_transfer_workflow_id AS workflowId,
                   workflow.manager_id AS managerId,
                   workflow.status AS workflowStatus,
                   workflow.mode AS mode,
                   workflow.company_title AS companyTitle
            FROM workload_transfer_offers offer
            JOIN workload_transfer_workflows workflow
              ON workflow.workload_transfer_workflow_id = offer.workflow_id
            JOIN workers candidate_worker
              ON candidate_worker.worker_id = offer.candidate_worker_id
            JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
            WHERE offer.offer_token = :offerToken
              AND offer.keyboard_activated = TRUE
            """, nativeQuery = true)
    Optional<CallbackProjection> findCallbackOffer(
            @Param("offerToken") String offerToken
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workflow_id =
                 workflow.workload_transfer_workflow_id
             AND offer.offer_token = :offerToken
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id =
                 offer.workflow_candidate_id
            JOIN workers candidate_worker
              ON candidate_worker.worker_id = offer.candidate_worker_id
            JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
            JOIN workload_shadow_worker_current candidate_current
              ON candidate_current.worker_id = candidate.worker_id
             AND candidate_current.manager_id = workflow.manager_id
            SET offer.status = 'ACCEPTED',
                offer.responded_at = :now,
                offer.response_actor_telegram_id = :actorTelegramId,
                offer.response_actor_user_id = candidate_user.id,
                offer.response_reason = NULL,
                offer.updated_at = :now,
                workflow.status = CASE
                    WHEN workflow.owner_confirmation_required = TRUE
                    THEN 'AWAITING_OWNER_CONFIRMATION'
                    ELSE 'ACCEPTED'
                END,
                workflow.accepted_worker_id = offer.candidate_worker_id,
                workflow.last_transition_at = :now,
                workflow.workflow_version = workflow.workflow_version + 1,
                workflow.updated_at = :now,
                candidate.status = 'ACCEPTED',
                candidate.last_responded_at = :now,
                candidate.response_reason = NULL,
                candidate.updated_at = :now
            WHERE offer.status = 'OFFERED'
              AND offer.keyboard_activated = TRUE
              AND offer.expires_at > :now
              AND offer.target_group_chat_id = :chatId
              AND offer.telegram_message_id = :messageId
              AND candidate_user.telegram_chat_id = :actorTelegramId
              AND candidate_current.recipient_eligible = TRUE
              AND candidate_current.accepts_company_transfers = TRUE
              AND candidate_current.worker_group_connected = TRUE
              AND workflow.status = 'OFFERED'
              AND workflow.manager_id = :managerId
              AND workflow.current_offer_id =
                  offer.workload_transfer_offer_id
              AND EXISTS (
                  SELECT 1
                  FROM app_settings live_revision
                  WHERE live_revision.setting_key =
                        'workload.live.settings-revision'
                    AND CAST(
                          TRIM(live_revision.setting_value) AS UNSIGNED
                        ) = :settingsRevision
              )
            """, nativeQuery = true)
    int accept(
            @Param("offerToken") String offerToken,
            @Param("chatId") long chatId,
            @Param("messageId") int messageId,
            @Param("actorTelegramId") long actorTelegramId,
            @Param("managerId") Long managerId,
            @Param("settingsRevision") long settingsRevision,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workflow_id =
                 workflow.workload_transfer_workflow_id
             AND offer.offer_token = :offerToken
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id =
                 offer.workflow_candidate_id
            JOIN workers candidate_worker
              ON candidate_worker.worker_id = offer.candidate_worker_id
            JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
            SET offer.status = 'DECLINED',
                offer.responded_at = :now,
                offer.response_actor_telegram_id = :actorTelegramId,
                offer.response_actor_user_id = candidate_user.id,
                offer.response_reason = 'Сотрудник отказался',
                offer.updated_at = :now,
                workflow.status = 'READY_TO_OFFER',
                workflow.current_offer_id = NULL,
                workflow.last_transition_at = :now,
                workflow.workflow_version = workflow.workflow_version + 1,
                workflow.updated_at = :now,
                candidate.status = 'DECLINED',
                candidate.last_responded_at = :now,
                candidate.response_reason = 'Сотрудник отказался',
                candidate.updated_at = :now
            WHERE offer.status = 'OFFERED'
              AND offer.keyboard_activated = TRUE
              AND offer.expires_at > :now
              AND offer.target_group_chat_id = :chatId
              AND offer.telegram_message_id = :messageId
              AND candidate_user.telegram_chat_id = :actorTelegramId
              AND workflow.status = 'OFFERED'
              AND workflow.manager_id = :managerId
              AND workflow.current_offer_id =
                  offer.workload_transfer_offer_id
              AND EXISTS (
                  SELECT 1
                  FROM app_settings live_revision
                  WHERE live_revision.setting_key =
                        'workload.live.settings-revision'
                    AND CAST(
                          TRIM(live_revision.setting_value) AS UNSIGNED
                        ) = :settingsRevision
              )
            """, nativeQuery = true)
    int decline(
            @Param("offerToken") String offerToken,
            @Param("chatId") long chatId,
            @Param("messageId") int messageId,
            @Param("actorTelegramId") long actorTelegramId,
            @Param("managerId") Long managerId,
            @Param("settingsRevision") long settingsRevision,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_offers
            SET status = 'CANCELLED',
                responded_at = :now,
                response_reason = :reason,
                processing_token = NULL,
                processing_lease_until = NULL,
                updated_at = :now
            WHERE status IN ('READY', 'RETRY', 'SENDING', 'OFFERED')
            """, nativeQuery = true)
    int cancelOpenOffers(
            @Param("now") LocalDateTime now,
            @Param("reason") String reason
    );

    interface StageCandidateProjection {
        Long getWorkflowId();
        Long getManagerId();
        Long getSourceWorkerId();
        Long getCompanyId();
        String getCompanyTitle();
        Long getProblemUnits();
        Long getEstimatedMinutes();
        Long getActiveOrderCount();
        Long getNewUnitCount();
        Long getCorrectionCount();
        Long getNagulCount();
        Long getPublishCount();
        Long getRecoveryCount();
        Long getBadCount();
        Long getCandidateId();
        Long getCandidateWorkerId();
        Integer getSequenceNumber();
        String getCandidateWorkerName();
        Long getTargetGroupChatId();
        Long getCandidateTelegramId();
    }

    interface DeliveryProjection {
        Long getOfferId();
        String getOfferToken();
        Long getTargetGroupChatId();
        Integer getDeliveryAttempts();
        Long getManagerId();
        String getCompanyTitle();
        Long getProblemUnits();
        Long getEstimatedMinutes();
        Long getActiveOrderCount();
        Long getNewUnitCount();
        Long getCorrectionCount();
        Long getNagulCount();
        Long getPublishCount();
        Long getRecoveryCount();
        Long getBadCount();
        String getSourceWorkerName();
        String getCandidateWorkerName();
        LocalDateTime getDeliveryDeadlineAt();
    }

    interface CallbackProjection {
        Long getOfferId();
        String getOfferToken();
        String getOfferStatus();
        Long getTargetGroupChatId();
        Integer getTelegramMessageId();
        LocalDateTime getExpiresAt();
        Long getCandidateWorkerId();
        Long getCandidateUserId();
        Long getCandidateTelegramId();
        Long getWorkflowId();
        Long getManagerId();
        String getWorkflowStatus();
        String getMode();
        String getCompanyTitle();
    }
}
