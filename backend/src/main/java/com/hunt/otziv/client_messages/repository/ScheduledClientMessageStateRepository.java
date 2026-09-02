package com.hunt.otziv.client_messages.repository;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ScheduledClientMessageStateRepository extends CrudRepository<ScheduledClientMessageState, Long> {

    Optional<ScheduledClientMessageState> findByScenarioAndTargetKey(ClientMessageScenario scenario, String targetKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s
        FROM ScheduledClientMessageState s
        WHERE s.scenario = :scenario
          AND s.targetKey = :targetKey
    """)
    Optional<ScheduledClientMessageState> findByScenarioAndTargetKeyForUpdate(
            @Param("scenario") ClientMessageScenario scenario,
            @Param("targetKey") String targetKey
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ScheduledClientMessageState s WHERE s.id = :id")
    Optional<ScheduledClientMessageState> findByIdForUpdate(@Param("id") Long id);

    List<ScheduledClientMessageState> findByOrderIdIn(Collection<Long> orderIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ScheduledClientMessageState s WHERE s.orderId IN :orderIds ORDER BY s.id ASC")
    List<ScheduledClientMessageState> findByOrderIdInForUpdate(@Param("orderIds") Collection<Long> orderIds);

    @Query("""
        SELECT s
        FROM ScheduledClientMessageState s, Order o
        JOIN o.status status
        WHERE s.orderId = o.id
          AND s.status = :status
          AND s.scenario IN :scenarios
          AND status.title IN :orderStatuses
        ORDER BY s.id ASC
    """)
    List<ScheduledClientMessageState> findActiveOrderAutomationStatesByOrderStatuses(
            @Param("scenarios") Collection<ClientMessageScenario> scenarios,
            @Param("status") ScheduledMessageStateStatus status,
            @Param("orderStatuses") Collection<String> orderStatuses,
            Pageable pageable
    );

    long countByStatus(ScheduledMessageStateStatus status);

    @Query("""
        SELECT DISTINCT s.orderId
        FROM ScheduledClientMessageState s
        WHERE s.scenario = com.hunt.otziv.client_messages.model.ClientMessageScenario.PAYMENT_INVOICE_RETRY
          AND s.status = com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus.ACTIVE
          AND s.orderId IS NOT NULL
          AND LOWER(COALESCE(s.lastErrorMessage, '')) LIKE '%configured_but_blocked%'
        ORDER BY s.orderId ASC
    """)
    List<Long> findLiveRoutingBlockedPaymentOrderIds(Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ScheduledClientMessageState s
        SET s.nextAttemptAt = :now,
            s.updatedAt = :now,
            s.lockedUntil = NULL
        WHERE s.scenario = com.hunt.otziv.client_messages.model.ClientMessageScenario.PAYMENT_INVOICE_RETRY
          AND s.status = com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus.ACTIVE
          AND LOWER(COALESCE(s.lastErrorMessage, '')) LIKE '%configured_but_blocked%'
          AND (s.nextAttemptAt IS NULL OR s.nextAttemptAt > :now)
          AND (s.lockedUntil IS NULL OR s.lockedUntil < :now)
    """)
    int expediteLiveRoutingBlockedPaymentRetries(@Param("now") LocalDateTime now);

    @Query(value = """
        SELECT state.state_id
        FROM scheduled_client_message_state state
        LEFT JOIN orders linked_order ON linked_order.order_id = state.order_id
        LEFT JOIN companies company ON company.company_id = COALESCE(state.company_id, linked_order.order_company)
        WHERE state.state_status = 'ACTIVE'
          AND COALESCE(linked_order.order_manager, company.company_manager) = :managerId
          AND (
              state.consecutive_failures > 0
              OR (
                  state.last_error_code IS NOT NULL
                  AND TRIM(state.last_error_code) <> ''
              )
          )
        ORDER BY COALESCE(state.last_attempt_at, state.updated_at, state.created_at) ASC, state.state_id ASC
        """, nativeQuery = true)
    List<Long> findManagerControlCandidateIds(@Param("managerId") Long managerId, Pageable pageable);

    @Query("""
        SELECT s.scenario AS scenario, COUNT(s) AS total
        FROM ScheduledClientMessageState s
        WHERE s.status = :status
        GROUP BY s.scenario
    """)
    List<ScenarioCount> countByStatusGrouped(@Param("status") ScheduledMessageStateStatus status);

    @Query("""
        SELECT s.scenario AS scenario, COUNT(s) AS total
        FROM ScheduledClientMessageState s
        WHERE s.status = :status
          AND s.nextAttemptAt IS NOT NULL
          AND s.nextAttemptAt <= :now
          AND (s.lockedUntil IS NULL OR s.lockedUntil < :now)
        GROUP BY s.scenario
    """)
    List<ScenarioCount> countDueByScenario(@Param("status") ScheduledMessageStateStatus status,
                                           @Param("now") LocalDateTime now);

    @Query("""
        SELECT COUNT(s)
        FROM ScheduledClientMessageState s
        WHERE s.scenario = :scenario
          AND s.status = :status
          AND (
              (s.nextAttemptAt IS NOT NULL AND s.nextAttemptAt < :before)
              OR (s.lockedUntil IS NOT NULL AND s.lockedUntil > :now)
          )
    """)
    long countScheduledBefore(
            @Param("scenario") ClientMessageScenario scenario,
            @Param("status") ScheduledMessageStateStatus status,
            @Param("now") LocalDateTime now,
            @Param("before") LocalDateTime before
    );

    @Query("""
        SELECT COUNT(s)
        FROM ScheduledClientMessageState s
        WHERE s.scenario = :scenario
          AND s.status = :status
          AND s.lockedUntil IS NOT NULL
          AND s.lockedUntil > :now
    """)
    long countProcessingNow(
            @Param("scenario") ClientMessageScenario scenario,
            @Param("status") ScheduledMessageStateStatus status,
            @Param("now") LocalDateTime now
    );

    @Query(value = """
        SELECT COUNT(*)
        FROM scheduled_client_message_state state
        LEFT JOIN companies company ON company.company_id = state.company_id
        LEFT JOIN company_status company_status ON company_status.company_status_id = company.company_status
        WHERE state.state_status = :status
          AND state.scenario = :scenario
          AND state.next_attempt_at IS NOT NULL
          AND state.next_attempt_at < :before
          AND (company_status.status_title IS NULL OR LOWER(TRIM(company_status.status_title)) <> 'бан')
          AND (
              company.company_id IS NULL
              OR company.company_url_chat IS NULL
              OR TRIM(company.company_url_chat) = ''
              OR (
                  LOWER(company.company_url_chat) LIKE '%chat.whatsapp.com/%'
                  AND (company.company_group_id IS NULL OR TRIM(company.company_group_id) = '')
              )
              OR (
                  (
                      LOWER(company.company_url_chat) REGEXP '(^|//)(t\\\\.me|telegram\\\\.me|telegram\\\\.dog)/'
                      OR LOWER(company.company_url_chat) LIKE 'tg://resolve%'
                  )
                  AND company.company_telegram_group_chat_id IS NULL
              )
              OR (
                  LOWER(company.company_url_chat) REGEXP '(^|//)(web\\\\.)?max\\\\.ru/'
                  AND company.company_max_group_chat_id IS NULL
              )
              OR (
                  LOWER(company.company_url_chat) NOT LIKE '%chat.whatsapp.com/%'
                  AND LOWER(company.company_url_chat) NOT REGEXP '(^|//)(t\\\\.me|telegram\\\\.me|telegram\\\\.dog)/'
                  AND LOWER(company.company_url_chat) NOT LIKE 'tg://resolve%'
                  AND LOWER(company.company_url_chat) NOT REGEXP '(^|//)(web\\\\.)?max\\\\.ru/'
              )
          )
        """, nativeQuery = true)
    long countMissingChannelBindingsBefore(
            @Param("scenario") String scenario,
            @Param("status") String status,
            @Param("before") LocalDateTime before
    );

    @Query(value = """
        SELECT state.scenario AS scenario, COUNT(*) AS total
        FROM scheduled_client_message_state state
        LEFT JOIN companies company ON company.company_id = state.company_id
        LEFT JOIN company_status company_status ON company_status.company_status_id = company.company_status
        WHERE state.state_status = :status
          AND (company_status.status_title IS NULL OR LOWER(TRIM(company_status.status_title)) <> 'бан')
          AND state.scenario IN (
              'CLIENT_TEXT_REMINDER',
              'REVIEW_CHECK_REMINDER',
              'REVIEW_CHECK_DELIVERY_RETRY',
              'PAYMENT_INVOICE_RETRY',
              'PAYMENT_REMINDER',
              'ARCHIVE_REORDER_OFFER',
              'BAD_REVIEW_INVOICE',
              'REVIEW_RECOVERY_NOTICE'
          )
          AND (
              company.company_id IS NULL
              OR company.company_url_chat IS NULL
              OR TRIM(company.company_url_chat) = ''
              OR (
                  LOWER(company.company_url_chat) LIKE '%chat.whatsapp.com/%'
                  AND (company.company_group_id IS NULL OR TRIM(company.company_group_id) = '')
              )
              OR (
                  (
                      LOWER(company.company_url_chat) REGEXP '(^|//)(t\\\\.me|telegram\\\\.me|telegram\\\\.dog)/'
                      OR LOWER(company.company_url_chat) LIKE 'tg://resolve%'
                  )
                  AND company.company_telegram_group_chat_id IS NULL
              )
              OR (
                  LOWER(company.company_url_chat) REGEXP '(^|//)(web\\\\.)?max\\\\.ru/'
                  AND company.company_max_group_chat_id IS NULL
              )
              OR (
                  LOWER(company.company_url_chat) NOT LIKE '%chat.whatsapp.com/%'
                  AND LOWER(company.company_url_chat) NOT REGEXP '(^|//)(t\\\\.me|telegram\\\\.me|telegram\\\\.dog)/'
                  AND LOWER(company.company_url_chat) NOT LIKE 'tg://resolve%'
                  AND LOWER(company.company_url_chat) NOT REGEXP '(^|//)(web\\\\.)?max\\\\.ru/'
              )
          )
        GROUP BY state.scenario
        """, nativeQuery = true)
    List<NativeScenarioCount> countMissingChannelBindingsByScenario(@Param("status") String status);

    @Query(value = """
        SELECT state.scenario AS scenario, COUNT(*) AS total
        FROM scheduled_client_message_state state
        LEFT JOIN companies company ON company.company_id = state.company_id
        LEFT JOIN company_status company_status ON company_status.company_status_id = company.company_status
        WHERE state.state_status = :status
          AND state.next_attempt_at IS NOT NULL
          AND state.next_attempt_at <= :now
          AND (state.locked_until IS NULL OR state.locked_until < :now)
          AND state.delivery_status IS NULL
          AND (company_status.status_title IS NULL OR LOWER(TRIM(company_status.status_title)) <> 'бан')
          AND state.scenario IN (
              'CLIENT_TEXT_REMINDER',
              'REVIEW_CHECK_REMINDER',
              'REVIEW_CHECK_DELIVERY_RETRY',
              'PAYMENT_INVOICE_RETRY',
              'PAYMENT_REMINDER',
              'ARCHIVE_REORDER_OFFER',
              'BAD_REVIEW_INVOICE',
              'REVIEW_RECOVERY_NOTICE'
          )
          AND (
              company.company_id IS NULL
              OR company.company_url_chat IS NULL
              OR TRIM(company.company_url_chat) = ''
              OR (
                  LOWER(company.company_url_chat) LIKE '%chat.whatsapp.com/%'
                  AND (company.company_group_id IS NULL OR TRIM(company.company_group_id) = '')
              )
              OR (
                  (
                      LOWER(company.company_url_chat) REGEXP '(^|//)(t\\\\.me|telegram\\\\.me|telegram\\\\.dog)/'
                      OR LOWER(company.company_url_chat) LIKE 'tg://resolve%'
                  )
                  AND company.company_telegram_group_chat_id IS NULL
              )
              OR (
                  LOWER(company.company_url_chat) REGEXP '(^|//)(web\\\\.)?max\\\\.ru/'
                  AND company.company_max_group_chat_id IS NULL
              )
              OR (
                  LOWER(company.company_url_chat) NOT LIKE '%chat.whatsapp.com/%'
                  AND LOWER(company.company_url_chat) NOT REGEXP '(^|//)(t\\\\.me|telegram\\\\.me|telegram\\\\.dog)/'
                  AND LOWER(company.company_url_chat) NOT LIKE 'tg://resolve%'
                  AND LOWER(company.company_url_chat) NOT REGEXP '(^|//)(web\\\\.)?max\\\\.ru/'
              )
          )
        GROUP BY state.scenario
        """, nativeQuery = true)
    List<NativeScenarioCount> countDueMissingChannelBindingsByScenario(@Param("status") String status,
                                                                       @Param("now") LocalDateTime now);

    @Query(value = """
        SELECT COUNT(DISTINCT state.state_id)
        FROM scheduled_client_message_state state
        LEFT JOIN companies company ON company.company_id = state.company_id
        LEFT JOIN company_status company_status ON company_status.company_status_id = company.company_status
        WHERE state.state_status = :status
          AND (company_status.status_title IS NULL OR LOWER(TRIM(company_status.status_title)) <> 'бан')
          AND (
              (
                  state.scenario IN (
                      'CLIENT_TEXT_REMINDER',
                      'REVIEW_CHECK_REMINDER',
                      'REVIEW_CHECK_DELIVERY_RETRY',
                      'PAYMENT_INVOICE_RETRY',
                      'PAYMENT_REMINDER',
                      'ARCHIVE_REORDER_OFFER',
                      'BAD_REVIEW_INVOICE',
                      'REVIEW_RECOVERY_NOTICE'
                  )
                  AND (
                      company.company_id IS NULL
                      OR company.company_url_chat IS NULL
                      OR TRIM(company.company_url_chat) = ''
                      OR (
                          LOWER(company.company_url_chat) LIKE '%chat.whatsapp.com/%'
                          AND (company.company_group_id IS NULL OR TRIM(company.company_group_id) = '')
                      )
                      OR (
                          (
                              LOWER(company.company_url_chat) REGEXP '(^|//)(t\\\\.me|telegram\\\\.me|telegram\\\\.dog)/'
                              OR LOWER(company.company_url_chat) LIKE 'tg://resolve%'
                          )
                          AND company.company_telegram_group_chat_id IS NULL
                      )
                      OR (
                          LOWER(company.company_url_chat) REGEXP '(^|//)(web\\\\.)?max\\\\.ru/'
                          AND company.company_max_group_chat_id IS NULL
                      )
                      OR (
                          LOWER(company.company_url_chat) NOT LIKE '%chat.whatsapp.com/%'
                          AND LOWER(company.company_url_chat) NOT REGEXP '(^|//)(t\\\\.me|telegram\\\\.me|telegram\\\\.dog)/'
                          AND LOWER(company.company_url_chat) NOT LIKE 'tg://resolve%'
                          AND LOWER(company.company_url_chat) NOT REGEXP '(^|//)(web\\\\.)?max\\\\.ru/'
                      )
                  )
              )
              OR (
                  state.last_error_code IS NOT NULL
                  AND TRIM(state.last_error_code) <> ''
                  AND LOWER(state.last_error_code) NOT LIKE '%dry_run%'
                  AND LOWER(state.last_error_code) NOT LIKE '%review_recovery_active%'
                  AND LOWER(state.last_error_code) NOT LIKE '%order_status_changed%'
                  AND LOWER(state.last_error_code) NOT LIKE '%status_change%'
                  AND LOWER(state.last_error_code) NOT LIKE '%auto_archive%'
                  AND LOWER(state.last_error_code) NOT LIKE '%auto_ban%'
                  AND LOWER(state.last_error_code) <> 'client_message_state_auto_recovered'
                  AND (
                      LOWER(state.last_error_code) IN (
                          'whatsapp_group_missing',
                          'telegram_group_missing',
                          'max_group_missing',
                          'chat_platform_unknown',
                          'whatsapp_client_missing',
                          'unknown_client',
                          'missing_client',
                          'empty_client_url',
                          'missing_group_id',
                          'message_empty',
                          'missing_message',
                          'company_missing'
                      )
                      OR state.consecutive_failures >= :failureThreshold
                      OR (
                          state.last_attempt_at IS NOT NULL
                          AND state.last_attempt_at <= :manualControlCutoff
                      )
                  )
              )
          )
        """, nativeQuery = true)
    long countManualControlCandidates(@Param("status") String status,
                                      @Param("failureThreshold") int failureThreshold,
                                      @Param("manualControlCutoff") LocalDateTime manualControlCutoff);

    @Query(value = """
        SELECT COUNT(*)
        FROM scheduled_client_message_state state
        WHERE state.state_status = :status
          AND state.last_error_code IS NOT NULL
          AND TRIM(state.last_error_code) <> ''
          AND state.next_attempt_at IS NOT NULL
          AND state.next_attempt_at > :now
          AND LOWER(state.last_error_code) NOT LIKE '%dry_run%'
          AND LOWER(state.last_error_code) NOT LIKE '%review_recovery_active%'
          AND LOWER(state.last_error_code) NOT LIKE '%order_status_changed%'
          AND LOWER(state.last_error_code) NOT LIKE '%status_change%'
          AND LOWER(state.last_error_code) NOT LIKE '%auto_archive%'
          AND LOWER(state.last_error_code) NOT LIKE '%auto_ban%'
          AND LOWER(state.last_error_code) <> 'client_message_state_auto_recovered'
          AND LOWER(state.last_error_code) NOT IN (
              'whatsapp_group_missing',
              'telegram_group_missing',
              'max_group_missing',
              'chat_platform_unknown',
              'whatsapp_client_missing',
              'unknown_client',
              'missing_client',
              'empty_client_url',
              'missing_group_id',
              'message_empty',
              'missing_message',
              'company_missing'
          )
          AND state.consecutive_failures < :failureThreshold
          AND (
              state.last_attempt_at IS NULL
              OR state.last_attempt_at > :manualControlCutoff
          )
        """, nativeQuery = true)
    long countRetryWaitingCandidates(@Param("status") String status,
                                     @Param("now") LocalDateTime now,
                                     @Param("failureThreshold") int failureThreshold,
                                     @Param("manualControlCutoff") LocalDateTime manualControlCutoff);

    @Query(value = """
        SELECT COUNT(*)
        FROM scheduled_client_message_state state
        WHERE state.state_status = :status
          AND LOWER(COALESCE(state.last_error_code, '')) = 'review_recovery_active'
        """, nativeQuery = true)
    long countReviewRecoveryHolds(@Param("status") String status);

    @Modifying
    @Query(value = """
        UPDATE scheduled_client_message_state state
        SET state.next_attempt_at = :now,
            state.locked_until = NULL,
            state.updated_at = :now
        WHERE state.order_id = :orderId
          AND state.state_status = 'ACTIVE'
          AND LOWER(COALESCE(state.last_error_code, '')) = 'review_recovery_active'
        """, nativeQuery = true)
    int releaseReviewRecoveryHolds(@Param("orderId") Long orderId,
                                   @Param("now") LocalDateTime now);

    @Query("""
        SELECT COUNT(s)
        FROM ScheduledClientMessageState s
        WHERE s.status = :status
          AND s.nextAttemptAt IS NOT NULL
          AND s.nextAttemptAt <= :now
          AND (s.lockedUntil IS NULL OR s.lockedUntil < :now)
    """)
    long countDue(@Param("status") ScheduledMessageStateStatus status,
                  @Param("now") LocalDateTime now);

    @Query("""
        SELECT s
        FROM ScheduledClientMessageState s
        WHERE s.status = :status
          AND s.nextAttemptAt IS NOT NULL
        ORDER BY s.nextAttemptAt ASC, s.id ASC
    """)
    List<ScheduledClientMessageState> findNextAttempt(@Param("status") ScheduledMessageStateStatus status,
                                                      Pageable pageable);

    @Query("""
        SELECT s
        FROM ScheduledClientMessageState s
        WHERE s.status = :status
          AND s.nextAttemptAt IS NOT NULL
          AND s.nextAttemptAt <= :now
          AND (s.lockedUntil IS NULL OR s.lockedUntil < :now)
        ORDER BY
          CASE s.scenario
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.PAYMENT_INVOICE_RETRY THEN 0
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.BAD_REVIEW_INVOICE THEN 1
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.PAYMENT_REMINDER THEN 2
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION THEN 3
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.REVIEW_RECOVERY_NOTICE THEN 4
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.CLIENT_TEXT_REMINDER THEN 5
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY THEN 6
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.REVIEW_CHECK_REMINDER THEN 7
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.ARCHIVE_REORDER_OFFER THEN 20
            ELSE 10
          END ASC,
          s.nextAttemptAt ASC,
          s.id ASC
    """)
    List<ScheduledClientMessageState> findDue(@Param("status") ScheduledMessageStateStatus status,
                                              @Param("now") LocalDateTime now,
                                              Pageable pageable);

    @Query("""
        SELECT s.id
        FROM ScheduledClientMessageState s
        WHERE s.status = :status
          AND s.nextAttemptAt IS NOT NULL
          AND s.nextAttemptAt <= :now
          AND (s.lockedUntil IS NULL OR s.lockedUntil < :now)
        ORDER BY
          CASE s.scenario
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.PAYMENT_INVOICE_RETRY THEN 0
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.BAD_REVIEW_INVOICE THEN 1
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.PAYMENT_REMINDER THEN 2
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION THEN 3
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.REVIEW_RECOVERY_NOTICE THEN 4
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.CLIENT_TEXT_REMINDER THEN 5
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY THEN 6
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.REVIEW_CHECK_REMINDER THEN 7
            WHEN com.hunt.otziv.client_messages.model.ClientMessageScenario.ARCHIVE_REORDER_OFFER THEN 20
            ELSE 10
          END ASC,
          s.nextAttemptAt ASC,
          s.id ASC
    """)
    List<Long> findDueIds(@Param("status") ScheduledMessageStateStatus status,
                          @Param("now") LocalDateTime now,
                          Pageable pageable);

    @Query("""
        SELECT s
        FROM ScheduledClientMessageState s
        WHERE s.status = :status
        ORDER BY
          CASE WHEN s.nextAttemptAt IS NULL THEN 1 ELSE 0 END,
          s.nextAttemptAt ASC,
          s.id ASC
    """)
    List<ScheduledClientMessageState> findMonitorQueue(@Param("status") ScheduledMessageStateStatus status,
                                                       Pageable pageable);

    @Query("""
        SELECT s
        FROM ScheduledClientMessageState s
        WHERE s.scenario = :scenario
          AND s.status = :status
          AND s.targetKey IN :targetKeys
    """)
    List<ScheduledClientMessageState> findActiveByScenarioAndTargetKeys(
            @Param("scenario") ClientMessageScenario scenario,
            @Param("status") ScheduledMessageStateStatus status,
            @Param("targetKeys") Collection<String> targetKeys
    );

    @Modifying
    @Query(value = """
        UPDATE scheduled_client_message_state state
        JOIN (
            SELECT state_id, MAX(attempted_at) AS latest_attempted_at
            FROM scheduled_client_message_attempts
            GROUP BY state_id
        ) latest_attempt ON latest_attempt.state_id = state.state_id
        JOIN scheduled_client_message_attempts attempt
          ON attempt.state_id = state.state_id
         AND attempt.attempted_at = latest_attempt.latest_attempted_at
        SET state.next_attempt_at = :now,
            state.locked_until = NULL,
            state.updated_at = :now
        WHERE state.state_status = 'ACTIVE'
          AND state.next_attempt_at IS NOT NULL
          AND state.next_attempt_at > :now
          AND attempt.attempt_status = 'SKIPPED'
          AND attempt.error_code = 'client_messages_dry_run'
        """, nativeQuery = true)
    int releaseDryRunStates(@Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
        UPDATE scheduled_client_message_state state
        SET state.delivery_status = 'UNKNOWN',
            state.last_error_code = 'state_transaction_outcome_uncertain',
            state.last_error_message = 'Подготовленная отправка прервалась; проверьте чат клиента вручную',
            state.next_attempt_at = NULL,
            state.locked_until = NULL,
            state.updated_at = :now
        WHERE state.scenario = 'BAD_REVIEW_INVOICE'
          AND state.state_status = 'ACTIVE'
          AND state.delivery_status = 'PREPARED'
          AND state.locked_until IS NOT NULL
          AND state.locked_until < :now
        """, nativeQuery = true)
    int quarantineExpiredPreparedBadReviewDeliveries(@Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
        UPDATE scheduled_client_message_state state
        SET state.next_attempt_at = :now,
            state.locked_until = NULL,
            state.last_error_code = 'expired_claim_recovered',
            state.last_error_message = 'Захват истек до подготовки отправки; задача возвращена в очередь',
            state.updated_at = :now
        WHERE state.scenario = 'BAD_REVIEW_INVOICE'
          AND state.state_status = 'ACTIVE'
          AND state.delivery_status IS NULL
          AND state.last_error_code = 'state_transaction_in_progress'
          AND state.locked_until IS NOT NULL
          AND state.locked_until < :now
        """, nativeQuery = true)
    int releaseExpiredUnpreparedBadReviewClaims(@Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
        UPDATE scheduled_client_message_state state
        SET state.next_attempt_at = :now,
            state.locked_until = NULL,
            state.updated_at = :now
        WHERE state.scenario = :scenario
          AND state.state_status = 'ACTIVE'
          AND state.last_error_code = :errorCode
          AND (state.next_attempt_at IS NULL OR state.next_attempt_at > :now)
        """, nativeQuery = true)
    int releaseReenabledScenario(@Param("scenario") String scenario,
                                 @Param("errorCode") String errorCode,
                                 @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
        UPDATE scheduled_client_message_state state
        SET state.locked_until = :lockedUntil,
            state.next_attempt_at = NULL,
            state.last_error_code = :claimCode,
            state.last_error_message = :claimMessage,
            state.updated_at = :now
        WHERE state.state_id = :id
          AND state.state_status = 'ACTIVE'
          AND state.next_attempt_at IS NOT NULL
          AND state.next_attempt_at <= :now
          AND (state.locked_until IS NULL OR state.locked_until < :now)
          AND state.delivery_status IS NULL
        """, nativeQuery = true)
    int lockDueState(@Param("id") Long id,
                     @Param("now") LocalDateTime now,
                     @Param("lockedUntil") LocalDateTime lockedUntil,
                     @Param("claimCode") String claimCode,
                     @Param("claimMessage") String claimMessage);

    @Modifying
    @Query(value = """
        UPDATE scheduled_client_message_state state
        SET state.locked_until = :lockedUntil,
            state.next_attempt_at = NULL,
            state.last_error_code = :claimCode,
            state.last_error_message = :claimMessage,
            state.updated_at = :now
        WHERE state.state_id = :id
          AND state.state_status = 'ACTIVE'
          AND (state.locked_until IS NULL OR state.locked_until < :now)
          AND state.delivery_status IS NULL
        """, nativeQuery = true)
    int lockActiveState(@Param("id") Long id,
                        @Param("now") LocalDateTime now,
                        @Param("lockedUntil") LocalDateTime lockedUntil,
                        @Param("claimCode") String claimCode,
                        @Param("claimMessage") String claimMessage);

    @Modifying
    @Query("DELETE FROM ScheduledClientMessageState s WHERE s.status IN :statuses AND s.updatedAt < :cutoff")
    int deleteTerminalOlderThan(@Param("statuses") Collection<ScheduledMessageStateStatus> statuses,
                                @Param("cutoff") LocalDateTime cutoff);

    interface ScenarioCount {
        ClientMessageScenario getScenario();
        long getTotal();
    }

    interface NativeScenarioCount {
        String getScenario();
        long getTotal();
    }
}
