package com.hunt.otziv.p_products.worker_access.repository;

import com.hunt.otziv.u_users.model.User;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit SQL boundary for worker network-violation episodes.
 *
 * <p>The marker {@link Repository} is intentional: this runtime repository must
 * not expose generated CRUD queries. Every database operation is declared below
 * with an explicit {@link Query}.</p>
 */
public interface WorkerNetworkViolationRepository
        extends Repository<User, Long> {

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO worker_network_violation_episodes (
                worker_user_id, worker_username, reason_code, scope_code,
                access_mode, access_result, episode_slot, first_seen_at, last_seen_at,
                attempt_count, provider, ip_prefix, client_evidence
            ) VALUES (
                :userId, :username, :reason, :scope,
                :mode, :result, :episodeSlot, :now, :now,
                1, :provider, :ipPrefix, :clientEvidence
            )
            ON DUPLICATE KEY UPDATE
                last_seen_at = VALUES(last_seen_at),
                attempt_count = attempt_count + 1,
                access_mode = VALUES(access_mode),
                access_result = VALUES(access_result),
                provider = VALUES(provider),
                ip_prefix = VALUES(ip_prefix),
                client_evidence = VALUES(client_evidence)
            """, nativeQuery = true)
    int upsertEpisode(
            @Param("userId") long userId,
            @Param("username") String username,
            @Param("reason") String reason,
            @Param("scope") String scope,
            @Param("mode") String mode,
            @Param("result") String result,
            @Param("episodeSlot") LocalDateTime episodeSlot,
            @Param("now") LocalDateTime now,
            @Param("provider") String provider,
            @Param("ipPrefix") String ipPrefix,
            @Param("clientEvidence") String clientEvidence
    );

    @Query(value = """
            SELECT worker_user_id AS userId,
                   first_seen_at AS firstSeenAt,
                   last_seen_at AS lastSeenAt,
                   reason_code AS reason,
                   scope_code AS scope,
                   attempt_count AS attemptCount,
                   provider AS provider,
                   client_evidence AS clientEvidence,
                   access_result AS accessResult
            FROM worker_network_violation_episodes
            WHERE worker_user_id IN (:userIds)
              AND last_seen_at >= :fromInclusive
              AND first_seen_at < :toExclusive
              AND access_result <> 'INVALIDATED'
            ORDER BY last_seen_at DESC
            """, nativeQuery = true)
    List<ViolationRowProjection> findActiveForUsers(
            @Param("userIds") Collection<Long> userIds,
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive
    );

    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM worker_network_violation_episodes
            WHERE last_seen_at < :cutoff
            """, nativeQuery = true)
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);

    interface ViolationRowProjection {
        Long getUserId();

        LocalDateTime getFirstSeenAt();

        LocalDateTime getLastSeenAt();

        String getReason();

        String getScope();

        long getAttemptCount();

        String getProvider();

        String getClientEvidence();

        String getAccessResult();
    }
}
