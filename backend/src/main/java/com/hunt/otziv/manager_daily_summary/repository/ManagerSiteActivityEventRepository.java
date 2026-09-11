package com.hunt.otziv.manager_daily_summary.repository;

import com.hunt.otziv.manager_daily_summary.model.ManagerSiteActivityEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ManagerSiteActivityEventRepository extends JpaRepository<ManagerSiteActivityEvent, Long> {
    interface ActivityPoint {
        Long getManagerId();
        LocalDateTime getOccurredAt();
        String getActivityType();
    }
    @org.springframework.data.jpa.repository.Query("""
        SELECT e.manager.id AS managerId, e.occurredAt AS occurredAt, e.activityType AS activityType
        FROM ManagerSiteActivityEvent e WHERE e.manager.id IN :ids AND e.occurredAt BETWEEN :from AND :to
        ORDER BY e.occurredAt
        """)
    List<ActivityPoint> pointsForManagers(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids,
            @org.springframework.data.repository.query.Param("from") LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") LocalDateTime to);
    List<ManagerSiteActivityEvent> findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(Long managerId, LocalDateTime from, LocalDateTime to);
    @Modifying long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
