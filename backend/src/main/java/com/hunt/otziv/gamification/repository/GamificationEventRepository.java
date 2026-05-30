package com.hunt.otziv.gamification.repository;

import com.hunt.otziv.gamification.model.GamificationEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface GamificationEventRepository extends JpaRepository<GamificationEvent, Long> {

    boolean existsByUniqueEventKey(String uniqueEventKey);

    Optional<GamificationEvent> findByUniqueEventKey(String uniqueEventKey);

    Page<GamificationEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime fromInclusive, LocalDateTime toExclusive);

    List<GamificationEvent> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    );

    @Query("""
            SELECT e.eventType, COUNT(e)
            FROM GamificationEvent e
            WHERE e.createdAt >= :fromInclusive
              AND e.createdAt < :toExclusive
            GROUP BY e.eventType
            ORDER BY COUNT(e) DESC
            """)
    List<Object[]> countByEventType(LocalDateTime fromInclusive, LocalDateTime toExclusive);

    @Query("""
            SELECT e.actorUserId, e.actorName, e.actorRole, COUNT(e)
            FROM GamificationEvent e
            WHERE e.createdAt >= :fromInclusive
              AND e.createdAt < :toExclusive
            GROUP BY e.actorUserId, e.actorName, e.actorRole
            ORDER BY COUNT(e) DESC
            """)
    List<Object[]> topActors(LocalDateTime fromInclusive, LocalDateTime toExclusive, Pageable pageable);

    @Query("""
            SELECT e.actorUserId, e.actorName, e.actorRole, e.eventType, COUNT(e)
            FROM GamificationEvent e
            WHERE e.createdAt >= :fromInclusive
              AND e.createdAt < :toExclusive
            GROUP BY e.actorUserId, e.actorName, e.actorRole, e.eventType
            """)
    List<Object[]> scorePreviewRows(LocalDateTime fromInclusive, LocalDateTime toExclusive);
}
