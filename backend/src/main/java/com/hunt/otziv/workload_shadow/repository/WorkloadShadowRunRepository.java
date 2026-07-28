package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadShadowRunEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadShadowRunRepository
        extends Repository<WorkloadShadowRunEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_runs (
                trigger_type,
                status,
                started_at,
                instance_id
            )
            VALUES (
                :triggerType,
                'RUNNING',
                :startedAt,
                :instanceId
            )
            """, nativeQuery = true)
    int startRun(
            @Param("triggerType") String triggerType,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("instanceId") String instanceId
    );

    @Query(value = "SELECT LAST_INSERT_ID()", nativeQuery = true)
    Long lastInsertedRunId();

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_runs
            SET status = 'SUCCEEDED',
                finished_at = :finishedAt,
                duration_ms = :durationMs,
                manager_count = :managerCount,
                worker_count = :workerCount,
                transfer_case_count = :transferCaseCount,
                event_count = :eventCount,
                self_heal_action_count = :selfHealActionCount,
                error_code = NULL,
                error_message = NULL
            WHERE workload_shadow_run_id = :runId
            """, nativeQuery = true)
    int complete(
            @Param("runId") long runId,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("durationMs") long durationMs,
            @Param("managerCount") int managerCount,
            @Param("workerCount") int workerCount,
            @Param("transferCaseCount") int transferCaseCount,
            @Param("eventCount") int eventCount,
            @Param("selfHealActionCount") int selfHealActionCount
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_runs
            SET status = 'FAILED',
                finished_at = :finishedAt,
                duration_ms = :durationMs,
                error_code = :errorCode,
                error_message = :errorMessage
            WHERE workload_shadow_run_id = :runId
            """, nativeQuery = true)
    int fail(
            @Param("runId") long runId,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("durationMs") long durationMs,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage
    );

    @Query(value = """
            SELECT workload_shadow_run_id AS id,
                   status AS status,
                   trigger_type AS triggerType,
                   started_at AS startedAt,
                   finished_at AS finishedAt,
                   duration_ms AS durationMs,
                   error_message AS errorMessage
            FROM workload_shadow_runs
            ORDER BY workload_shadow_run_id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<LatestRunProjection> latestRun();

    @Query(value = """
            SELECT finished_at
            FROM workload_shadow_runs
            WHERE status = 'SUCCEEDED'
              AND finished_at IS NOT NULL
            ORDER BY workload_shadow_run_id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<LocalDateTime> lastSuccessfulFinishedAt();

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_runs
            SET status = 'FAILED',
                finished_at = :now,
                duration_ms = GREATEST(
                    0,
                    TIMESTAMPDIFF(SECOND, started_at, :now) * 1000
                ),
                error_code = 'STALE_RUN_TIMEOUT',
                error_message = 'Запуск автоматически закрыт самодиагностикой после превышения lease'
            WHERE status = 'RUNNING'
              AND started_at < :staleBefore
            ORDER BY started_at
            LIMIT :batchSize
            """, nativeQuery = true)
    int failStaleRuns(
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("now") LocalDateTime now,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query(value = """
            DELETE FROM workload_shadow_runs
            WHERE status IN ('SUCCESS', 'SUCCEEDED', 'FAILED', 'SKIPPED')
              AND finished_at IS NOT NULL
              AND finished_at < :cutoff
            ORDER BY finished_at
            LIMIT :batchSize
            """, nativeQuery = true)
    int deleteTerminalRuns(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("batchSize") int batchSize
    );

    interface LatestRunProjection {
        Long getId();
        String getStatus();
        String getTriggerType();
        LocalDateTime getStartedAt();
        LocalDateTime getFinishedAt();
        Long getDurationMs();
        String getErrorMessage();
    }
}
