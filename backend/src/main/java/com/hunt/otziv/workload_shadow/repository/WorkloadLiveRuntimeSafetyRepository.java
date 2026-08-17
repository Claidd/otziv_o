package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadShadowRunEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface WorkloadLiveRuntimeSafetyRepository
        extends Repository<WorkloadShadowRunEntity, Long> {

    @Query(value = """
            SELECT successful.workload_shadow_run_id AS latestSuccessfulRunId,
                   successful.finished_at AS latestSuccessfulFinishedAt,
                   successful.settings_revision AS latestSettingsRevision,
                   (SELECT COUNT(*)
                    FROM workload_shadow_runs running
                    WHERE running.status = 'RUNNING') AS runningRunCount,
                   (SELECT COUNT(*)
                    FROM workload_shadow_worker_current current_snapshot)
                       AS currentSnapshotCount,
                   (SELECT COUNT(*)
                    FROM workload_shadow_worker_current current_snapshot
                    WHERE current_snapshot.run_id <>
                          successful.workload_shadow_run_id
                       OR current_snapshot.run_id IS NULL)
                       AS mismatchedCurrentSnapshotCount,
                   (SELECT COUNT(*)
                    FROM workload_shadow_transfer_cases transfer_case
                    WHERE transfer_case.active = TRUE
                      AND (
                          transfer_case.run_id <>
                              successful.workload_shadow_run_id
                          OR transfer_case.run_id IS NULL
                      )) AS mismatchedActiveCaseCount
            FROM workload_shadow_runs successful
            WHERE successful.status = 'SUCCEEDED'
              AND successful.finished_at IS NOT NULL
            ORDER BY successful.workload_shadow_run_id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<RuntimeStateProjection> runtimeState();

    interface RuntimeStateProjection {
        Long getLatestSuccessfulRunId();

        LocalDateTime getLatestSuccessfulFinishedAt();

        Long getLatestSettingsRevision();

        Long getRunningRunCount();

        Long getCurrentSnapshotCount();

        Long getMismatchedCurrentSnapshotCount();

        Long getMismatchedActiveCaseCount();
    }
}
