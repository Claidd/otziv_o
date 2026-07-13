package com.hunt.otziv.manager_daily_summary.repository;

import com.hunt.otziv.manager_daily_summary.model.ManagerSummaryDeliveryLog;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ManagerSummaryDeliveryLogRepository extends JpaRepository<ManagerSummaryDeliveryLog, Long> {
    Optional<ManagerSummaryDeliveryLog> findBySummaryDateAndRecipient_IdAndChannel(LocalDate date, Long recipientId, String channel);
    @Modifying long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
