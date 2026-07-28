package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadShadowWorkerDailyEntity;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadShadowWorkerDailyRepository
        extends Repository<WorkloadShadowWorkerDailyEntity, Long> {

    @Modifying
    @Query(value = """
            DELETE FROM workload_shadow_worker_daily
            WHERE finalized = 1
              AND progress_date < :cutoff
            ORDER BY progress_date, workload_shadow_worker_daily_id
            LIMIT :batchSize
            """, nativeQuery = true)
    int deleteFinalizedDaily(
            @Param("cutoff") LocalDate cutoff,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query(value = """
            DELETE FROM workload_shadow_late_batches
            WHERE progress_date < :cutoff
            ORDER BY progress_date, worker_id, batch_key
            LIMIT :batchSize
            """, nativeQuery = true)
    int deleteLateBatches(
            @Param("cutoff") LocalDate cutoff,
            @Param("batchSize") int batchSize
    );
}
