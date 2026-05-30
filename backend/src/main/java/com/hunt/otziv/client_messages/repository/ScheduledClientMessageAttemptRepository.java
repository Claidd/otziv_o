package com.hunt.otziv.client_messages.repository;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageAttempt;
import com.hunt.otziv.client_messages.model.ScheduledMessageAttemptStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ScheduledClientMessageAttemptRepository extends CrudRepository<ScheduledClientMessageAttempt, Long> {

    @Modifying
    @Query("DELETE FROM ScheduledClientMessageAttempt a WHERE a.attemptedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    long countByStatusAndAttemptedAtGreaterThanEqual(ScheduledMessageAttemptStatus status, LocalDateTime attemptedAt);

    @Query("""
        SELECT COUNT(a)
        FROM ScheduledClientMessageAttempt a
        WHERE a.status = :status
          AND a.attemptedAt >= :since
          AND (a.channel IS NULL OR a.channel <> 'system')
    """)
    long countClientSentSince(@Param("status") ScheduledMessageAttemptStatus status,
                              @Param("since") LocalDateTime since);

    @Query("""
        SELECT a.scenario AS scenario, COUNT(a) AS total
        FROM ScheduledClientMessageAttempt a
        WHERE a.status = :status
          AND a.attemptedAt >= :since
        GROUP BY a.scenario
    """)
    List<ScenarioCount> countByStatusSinceGrouped(@Param("status") ScheduledMessageAttemptStatus status,
                                                  @Param("since") LocalDateTime since);

    @Query("""
        SELECT a
        FROM ScheduledClientMessageAttempt a
        ORDER BY a.attemptedAt DESC, a.id DESC
    """)
    List<ScheduledClientMessageAttempt> findRecent(Pageable pageable);

    Optional<ScheduledClientMessageAttempt> findFirstByScenarioAndStatusOrderByAttemptedAtDesc(
            ClientMessageScenario scenario,
            ScheduledMessageAttemptStatus status
    );

    interface ScenarioCount {
        ClientMessageScenario getScenario();
        long getTotal();
    }
}
