package com.hunt.otziv.manager_daily_summary.repository;

import com.hunt.otziv.manager_daily_summary.model.ManagerSiteActivityEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ManagerSiteActivityEventRepository extends JpaRepository<ManagerSiteActivityEvent, Long> {
    List<ManagerSiteActivityEvent> findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(Long managerId, LocalDateTime from, LocalDateTime to);
    @Modifying long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
