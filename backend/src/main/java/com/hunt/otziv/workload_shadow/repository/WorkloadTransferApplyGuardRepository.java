package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadTransferWorkflowEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadTransferApplyGuardRepository
        extends Repository<WorkloadTransferWorkflowEntity, Long> {

    @Query(value = """
            SELECT workflow.live_settings_revision AS liveSettingsRevision,
                   workflow.shadow_settings_revision AS shadowSettingsRevision,
                   successful.settings_revision AS successfulSettingsRevision,
                   CASE
                       WHEN transfer_case.workload_shadow_transfer_case_id IS NOT NULL
                        AND transfer_case.active = TRUE
                        AND transfer_case.status = 'SHADOW_PENDING'
                        AND transfer_case.graph_error_count = 0
                        AND transfer_case.staffing_required = FALSE
                        AND transfer_case.manager_id = workflow.manager_id
                        AND transfer_case.source_worker_id = workflow.source_worker_id
                        AND transfer_case.company_id = workflow.company_id
                        AND transfer_case.run_id = successful.workload_shadow_run_id
                        AND source_current.worker_id = workflow.source_worker_id
                        AND source_current.manager_id = workflow.manager_id
                        AND source_current.run_id = successful.workload_shadow_run_id
                        AND source_current.diagnostic_status = 'OK'
                        AND source_current.last_day_reached_100 = FALSE
                        AND source_current.failure_days > CAST(
                            TRIM(allowed_failures.setting_value) AS UNSIGNED
                        )
                        AND workflow.shadow_settings_revision =
                            successful.settings_revision
                        AND workflow.shadow_settings_revision = CAST(
                            TRIM(shadow_revision.setting_value) AS UNSIGNED
                        )
                       THEN 1
                       ELSE 0
                   END AS recommendationCurrent
            FROM workload_transfer_workflows workflow
            LEFT JOIN workload_shadow_transfer_cases transfer_case
              ON transfer_case.workload_shadow_transfer_case_id = workflow.shadow_case_id
            LEFT JOIN workload_shadow_worker_current source_current
              ON source_current.worker_id = workflow.source_worker_id
            LEFT JOIN (
                SELECT run.workload_shadow_run_id,
                       run.settings_revision
                FROM workload_shadow_runs run
                WHERE run.status = 'SUCCEEDED'
                  AND run.finished_at IS NOT NULL
                ORDER BY run.workload_shadow_run_id DESC
                LIMIT 1
            ) successful ON TRUE
            JOIN app_settings allowed_failures
              ON allowed_failures.setting_key = 'workload.shadow.allowed-failure-days'
            JOIN app_settings shadow_revision
              ON shadow_revision.setting_key = 'workload.shadow.settings-revision'
            WHERE workflow.workload_transfer_workflow_id = :workflowId
              AND workflow.active = TRUE
              AND workflow.status = 'APPLYING'
            FOR UPDATE
            """, nativeQuery = true)
    Optional<ApplyGuardProjection> lockGuard(@Param("workflowId") long workflowId);

    interface ApplyGuardProjection {
        Long getLiveSettingsRevision();

        Long getShadowSettingsRevision();

        Long getSuccessfulSettingsRevision();

        Long getRecommendationCurrent();
    }
}
