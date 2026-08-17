package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadTransferWorkflowEntity;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadLiveDailyQuotaLockRepository
        extends Repository<WorkloadTransferWorkflowEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO workload_live_daily_quota_locks (decision_date)
            VALUES (:decisionDate)
            """, nativeQuery = true)
    int ensureDay(@Param("decisionDate") LocalDate decisionDate);

    @Query(value = """
            SELECT decision_date
            FROM workload_live_daily_quota_locks
            WHERE decision_date = :decisionDate
            FOR UPDATE
            """, nativeQuery = true)
    Optional<LocalDate> lockDay(@Param("decisionDate") LocalDate decisionDate);
}
