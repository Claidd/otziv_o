package com.hunt.otziv.client_messages.repository;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ScheduledClientMessageStateRepository extends CrudRepository<ScheduledClientMessageState, Long> {

    Optional<ScheduledClientMessageState> findByScenarioAndTargetKey(ClientMessageScenario scenario, String targetKey);

    long countByStatus(ScheduledMessageStateStatus status);

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

    @Query(value = """
        SELECT state.scenario AS scenario, COUNT(*) AS total
        FROM scheduled_client_message_state state
        LEFT JOIN companies company ON company.company_id = state.company_id
        WHERE state.state_status = :status
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
        WHERE state.state_status = :status
          AND state.next_attempt_at IS NOT NULL
          AND state.next_attempt_at <= :now
          AND (state.locked_until IS NULL OR state.locked_until < :now)
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
        ORDER BY s.nextAttemptAt ASC, s.id ASC
    """)
    List<ScheduledClientMessageState> findDue(@Param("status") ScheduledMessageStateStatus status,
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
    @Query("UPDATE ScheduledClientMessageState s SET s.lockedUntil = :lockedUntil WHERE s.id = :id AND (s.lockedUntil IS NULL OR s.lockedUntil < :now)")
    int lockDueState(@Param("id") Long id,
                     @Param("now") LocalDateTime now,
                     @Param("lockedUntil") LocalDateTime lockedUntil);

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
