package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadMaintenanceStatusEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadMaintenanceStatusRepository
        extends Repository<WorkloadMaintenanceStatusEntity, String> {

    @Modifying
    @Query(value = """
            INSERT INTO workload_maintenance_status (
                maintenance_task,
                last_started_at,
                created_at,
                updated_at
            )
            VALUES (:task, :startedAt, :startedAt, :startedAt)
            ON DUPLICATE KEY UPDATE
                last_started_at = VALUES(last_started_at),
                updated_at = VALUES(updated_at)
            """, nativeQuery = true)
    int markStarted(
            @Param("task") String task,
            @Param("startedAt") LocalDateTime startedAt
    );

    @Modifying
    @Query(value = """
            UPDATE workload_maintenance_status
            SET last_succeeded_at = :succeededAt,
                consecutive_failures = 0,
                last_error_code = NULL,
                last_error_message = NULL,
                updated_at = :succeededAt
            WHERE maintenance_task = :task
            """, nativeQuery = true)
    int markSucceeded(
            @Param("task") String task,
            @Param("succeededAt") LocalDateTime succeededAt
    );

    @Modifying
    @Query(value = """
            UPDATE workload_maintenance_status
            SET last_failed_at = :failedAt,
                consecutive_failures = consecutive_failures + 1,
                last_error_code = :errorCode,
                last_error_message = :errorMessage,
                updated_at = :failedAt
            WHERE maintenance_task = :task
            """, nativeQuery = true)
    int markFailed(
            @Param("task") String task,
            @Param("failedAt") LocalDateTime failedAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage
    );

    @Query(value = """
            SELECT maintenance_task AS task,
                   last_started_at AS lastStartedAt,
                   last_succeeded_at AS lastSucceededAt,
                   last_failed_at AS lastFailedAt,
                   consecutive_failures AS consecutiveFailures,
                   last_error_code AS lastErrorCode,
                   last_error_message AS lastErrorMessage,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM workload_maintenance_status
            WHERE maintenance_task IN ('REPAIR', 'RETENTION')
            ORDER BY maintenance_task
            """, nativeQuery = true)
    List<StatusProjection> findRuntimeStatuses();

    interface StatusProjection {
        String getTask();
        LocalDateTime getLastStartedAt();
        LocalDateTime getLastSucceededAt();
        LocalDateTime getLastFailedAt();
        Integer getConsecutiveFailures();
        String getLastErrorCode();
        String getLastErrorMessage();
        LocalDateTime getCreatedAt();
        LocalDateTime getUpdatedAt();
    }
}
