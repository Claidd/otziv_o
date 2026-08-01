package com.hunt.otziv.b_bots.repository;

import com.hunt.otziv.b_bots.model.BotBrowserSession;
import com.hunt.otziv.b_bots.model.BotBrowserSessionStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BotBrowserSessionRepository extends JpaRepository<BotBrowserSession, String> {

    Optional<BotBrowserSession> findFirstByBotIdAndStatusInOrderByCreatedAtDesc(
            Long botId,
            Collection<BotBrowserSessionStatus> statuses
    );

    @Query("""
            SELECT session
            FROM BotBrowserSession session
            WHERE (session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.OPEN
                   AND (session.heartbeatExpiresAt <= :now OR session.absoluteExpiresAt <= :now))
               OR (session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.OPENING
                   AND (session.updatedAt <= :openingCutoff OR session.absoluteExpiresAt <= :now))
               OR (session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.STOP_RETRY
                   AND (session.nextStopRetryAt IS NULL OR session.nextStopRetryAt <= :now))
               OR (session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.CLOSING
                   AND session.updatedAt <= :closingCutoff)
            ORDER BY session.updatedAt, session.sessionId
            """)
    List<BotBrowserSession> findSweepCandidates(
            @Param("now") LocalDateTime now,
            @Param("openingCutoff") LocalDateTime openingCutoff,
            @Param("closingCutoff") LocalDateTime closingCutoff,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE BotBrowserSession session
            SET session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.OPEN,
                session.openedAt = :now,
                session.lastHeartbeatAt = :now,
                session.heartbeatExpiresAt = :heartbeatExpiresAt,
                session.updatedAt = :now,
                session.lastError = NULL,
                session.version = session.version + 1
            WHERE session.sessionId = :sessionId
              AND session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.OPENING
              AND session.version = :expectedVersion
            """)
    int markOpen(
            @Param("sessionId") String sessionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now,
            @Param("heartbeatExpiresAt") LocalDateTime heartbeatExpiresAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE BotBrowserSession session
            SET session.lastHeartbeatAt = :now,
                session.heartbeatExpiresAt = :heartbeatExpiresAt,
                session.updatedAt = :now,
                session.version = session.version + 1
            WHERE session.sessionId = :sessionId
              AND session.botId = :botId
              AND session.openerSubject = :openerSubject
              AND session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.OPEN
              AND session.heartbeatExpiresAt > :now
              AND session.absoluteExpiresAt > :now
              AND session.version = :expectedVersion
            """)
    int heartbeat(
            @Param("sessionId") String sessionId,
            @Param("botId") Long botId,
            @Param("openerSubject") String openerSubject,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now,
            @Param("heartbeatExpiresAt") LocalDateTime heartbeatExpiresAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE BotBrowserSession session
            SET session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.CLOSING,
                session.closeRequestedAt = COALESCE(session.closeRequestedAt, :now),
                session.updatedAt = :now,
                session.version = session.version + 1
            WHERE session.sessionId = :sessionId
              AND session.status = :expectedStatus
              AND session.version = :expectedVersion
            """)
    int beginClosing(
            @Param("sessionId") String sessionId,
            @Param("expectedStatus") BotBrowserSessionStatus expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE BotBrowserSession session
            SET session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.CLOSED,
                session.closedAt = :now,
                session.nextStopRetryAt = NULL,
                session.lastError = NULL,
                session.updatedAt = :now,
                session.version = session.version + 1
            WHERE session.sessionId = :sessionId
              AND session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.CLOSING
              AND session.version = :expectedVersion
            """)
    int markClosed(
            @Param("sessionId") String sessionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE BotBrowserSession session
            SET session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.STOP_RETRY,
                session.stopAttempts = session.stopAttempts + 1,
                session.nextStopRetryAt = :retryAt,
                session.lastError = :lastError,
                session.updatedAt = :now,
                session.version = session.version + 1
            WHERE session.sessionId = :sessionId
              AND session.status = com.hunt.otziv.b_bots.model.BotBrowserSessionStatus.CLOSING
              AND session.version = :expectedVersion
            """)
    int markStopRetry(
            @Param("sessionId") String sessionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now,
            @Param("retryAt") LocalDateTime retryAt,
            @Param("lastError") String lastError
    );
}
