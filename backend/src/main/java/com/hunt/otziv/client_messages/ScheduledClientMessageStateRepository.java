package com.hunt.otziv.client_messages;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
}
