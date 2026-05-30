package com.hunt.otziv.gamification.repository;

import com.hunt.otziv.gamification.model.GamificationScoreLedger;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface GamificationScoreLedgerRepository extends JpaRepository<GamificationScoreLedger, Long> {

    boolean existsByUniqueScoreKey(String uniqueScoreKey);

    long countBySourceEventCreatedAtGreaterThanEqualAndSourceEventCreatedAtLessThan(
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    );

    long deleteBySourceEventCreatedAtGreaterThanEqualAndSourceEventCreatedAtLessThan(
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    );

    @Query("""
            SELECT COALESCE(SUM(l.points), 0)
            FROM GamificationScoreLedger l
            WHERE l.sourceEventCreatedAt >= :fromInclusive
              AND l.sourceEventCreatedAt < :toExclusive
            """)
    Long sumPoints(LocalDateTime fromInclusive, LocalDateTime toExclusive);

    @Query("""
            SELECT l.actorUserId, l.actorName, l.actorRole, COUNT(l), COALESCE(SUM(l.points), 0)
            FROM GamificationScoreLedger l
            WHERE l.sourceEventCreatedAt >= :fromInclusive
              AND l.sourceEventCreatedAt < :toExclusive
            GROUP BY l.actorUserId, l.actorName, l.actorRole
            ORDER BY COALESCE(SUM(l.points), 0) DESC
            """)
    List<Object[]> topActors(LocalDateTime fromInclusive, LocalDateTime toExclusive, Pageable pageable);

    @Query("""
            SELECT l.actorUserId, l.actorName, l.actorRole, l.eventType, COUNT(l), COALESCE(SUM(l.points), 0),
                   COALESCE(SUM(COALESCE(l.basePoints, l.rulePoints)), 0),
                   COALESCE(SUM(CASE WHEN COALESCE(l.delayDays, 0) <= 0 THEN 1 ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN COALESCE(l.delayDays, 0) > 0 THEN 1 ELSE 0 END), 0)
            FROM GamificationScoreLedger l
            WHERE l.sourceEventCreatedAt >= :fromInclusive
              AND l.sourceEventCreatedAt < :toExclusive
            GROUP BY l.actorUserId, l.actorName, l.actorRole, l.eventType
            """)
    List<Object[]> balanceRows(LocalDateTime fromInclusive, LocalDateTime toExclusive);

    @Query("""
            SELECT l.eventType, COUNT(l), COALESCE(SUM(l.points), 0),
                   COALESCE(SUM(COALESCE(l.basePoints, l.rulePoints)), 0),
                   COALESCE(SUM(CASE WHEN COALESCE(l.delayDays, 0) <= 0 THEN 1 ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN COALESCE(l.delayDays, 0) > 0 THEN 1 ELSE 0 END), 0)
            FROM GamificationScoreLedger l
            WHERE l.actorUserId = :actorUserId
              AND l.sourceEventCreatedAt >= :fromInclusive
              AND l.sourceEventCreatedAt < :toExclusive
            GROUP BY l.eventType
            """)
    List<Object[]> balanceRowsForActor(Long actorUserId, LocalDateTime fromInclusive, LocalDateTime toExclusive);

    @Query("""
            SELECT FUNCTION('date', l.sourceEventCreatedAt), COUNT(l),
                   COALESCE(SUM(CASE WHEN COALESCE(l.delayDays, 0) > 0 THEN 1 ELSE 0 END), 0)
            FROM GamificationScoreLedger l
            WHERE l.actorUserId = :actorUserId
              AND l.sourceEventCreatedAt >= :fromInclusive
              AND l.sourceEventCreatedAt < :toExclusive
            GROUP BY FUNCTION('date', l.sourceEventCreatedAt)
            ORDER BY FUNCTION('date', l.sourceEventCreatedAt) DESC
            """)
    List<Object[]> dailyRowsForActor(Long actorUserId, LocalDateTime fromInclusive, LocalDateTime toExclusive);
}
