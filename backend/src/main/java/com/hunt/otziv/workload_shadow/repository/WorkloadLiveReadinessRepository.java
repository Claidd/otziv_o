package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadShadowTransferCaseEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadLiveReadinessRepository
        extends Repository<WorkloadShadowTransferCaseEntity, Long> {

    @Query(value = """
            SELECT COUNT(DISTINCT progress_date)
            FROM workload_shadow_worker_daily
            WHERE finalized = TRUE
              AND finalization_status <> 'STALE_SNAPSHOT'
              AND progress_date >= :historyStart
              AND progress_date < :today
            """, nativeQuery = true)
    long countFinalizedDates(
            @Param("historyStart") LocalDate historyStart,
            @Param("today") LocalDate today
    );

    @Query(value = """
            SELECT COUNT(*)
            FROM workload_shadow_runs
            WHERE status = 'FAILED'
              AND started_at >= :stableSince
            """, nativeQuery = true)
    long countFailedRunsSince(@Param("stableSince") LocalDateTime stableSince);

    @Query(value = """
            WITH successful_runs AS (
                SELECT finished_at,
                       LAG(finished_at) OVER (
                           ORDER BY finished_at
                       ) AS previous_finished_at
                FROM workload_shadow_runs
                WHERE status = 'SUCCEEDED'
                  AND finished_at IS NOT NULL
                  AND finished_at >= :stableSince
                  AND finished_at <= :checkedAt
            ),
            observed_gaps AS (
                SELECT TIMESTAMPDIFF(
                           MINUTE,
                           COALESCE(previous_finished_at, :stableSince),
                           finished_at
                       ) AS gap_minutes
                FROM successful_runs

                UNION ALL

                SELECT TIMESTAMPDIFF(
                           MINUTE,
                           COALESCE(MAX(finished_at), :stableSince),
                           :checkedAt
                       ) AS gap_minutes
                FROM successful_runs
            )
            SELECT COALESCE(
                       MAX(gap_minutes),
                       TIMESTAMPDIFF(MINUTE, :stableSince, :checkedAt)
                   )
            FROM observed_gaps
            """, nativeQuery = true)
    long maximumSuccessfulRunGapMinutes(
            @Param("stableSince") LocalDateTime stableSince,
            @Param("checkedAt") LocalDateTime checkedAt
    );

    @Query(value = """
            SELECT MAX(finished_at)
            FROM workload_shadow_runs
            WHERE status = 'SUCCEEDED'
            """, nativeQuery = true)
    Optional<LocalDateTime> lastSuccessfulRunAt();

    @Query(value = """
            SELECT current.manager_id AS managerId,
                   COUNT(*) AS workerCount,
                   SUM(CASE
                       WHEN current.recipient_eligible = TRUE
                        AND current.accepts_company_transfers = TRUE
                        AND current.worker_group_connected = TRUE
                       THEN 1
                       ELSE 0
                   END) AS eligibleRecipientCount
            FROM workload_shadow_worker_current current
            WHERE current.manager_id IS NOT NULL
            GROUP BY current.manager_id
            ORDER BY current.manager_id
            """, nativeQuery = true)
    List<ManagerCapacityProjection> managerCapacity();

    @Query(value = """
            SELECT COUNT(*)
            FROM workload_shadow_transfer_cases transfer_case
            WHERE transfer_case.active = TRUE
              AND transfer_case.graph_error_count > 0
              AND (
                    :allManagers = TRUE
                    OR transfer_case.manager_id IN (:managerIds)
              )
            """, nativeQuery = true)
    long countGraphErrorCases(
            @Param("allManagers") boolean allManagers,
            @Param("managerIds") List<Long> managerIds
    );

    @Query(value = """
            SELECT COUNT(*)
            FROM workload_transfer_executions execution
            WHERE execution.status IN ('PREPARED', 'APPLYING', 'ROLLING_BACK')
            """, nativeQuery = true)
    long countInFlightExecutions();

    interface ManagerCapacityProjection {
        Long getManagerId();
        Long getWorkerCount();
        Long getEligibleRecipientCount();
    }
}
