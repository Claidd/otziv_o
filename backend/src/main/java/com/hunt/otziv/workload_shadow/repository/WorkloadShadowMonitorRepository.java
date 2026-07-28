package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadShadowWorkerDailyEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadShadowMonitorRepository
        extends Repository<WorkloadShadowWorkerDailyEntity, Long> {

    @Query(value = """
            SELECT worker_totals.manager_count AS managerCount,
                   worker_totals.worker_count AS workerCount,
                   worker_totals.workers_at_100 AS workersAt100,
                   worker_totals.at_risk_workers AS atRiskWorkers,
                   worker_totals.late_excluded_units AS lateExcludedUnits,
                   worker_totals.missing_worker_groups AS missingWorkerGroups,
                   worker_totals.missing_manager_groups AS missingManagerGroups,
                   worker_totals.updated_at AS updatedAt,
                   worker_totals.progress_date AS progressDate,
                   transfer_totals.transfer_case_count AS transferCases,
                   transfer_totals.staffing_signals AS staffingSignals
            FROM (
                SELECT COUNT(DISTINCT wsc.manager_id) AS manager_count,
                       COUNT(*) AS worker_count,
                       COALESCE(SUM(CASE
                           WHEN wsc.progress_percent >= 100 THEN 1
                           ELSE 0
                       END), 0) AS workers_at_100,
                       COALESCE(SUM(CASE
                           WHEN wsc.transfer_stage > 0 THEN 1
                           ELSE 0
                       END), 0) AS at_risk_workers,
                       COALESCE(SUM(wsc.late_excluded_units), 0) AS late_excluded_units,
                       COALESCE(SUM(CASE
                           WHEN wsc.worker_group_connected = FALSE THEN 1
                           ELSE 0
                       END), 0) AS missing_worker_groups,
                       COUNT(DISTINCT CASE
                           WHEN manager.manager_id IS NOT NULL
                            AND (
                              manager.audit_telegram_group_chat_id IS NULL
                              OR manager.audit_telegram_group_chat_id >= 0
                            )
                           THEN wsc.manager_id
                       END) AS missing_manager_groups,
                       COALESCE(MAX(wsc.snapshot_at), CURRENT_TIMESTAMP(6)) AS updated_at,
                       COALESCE(MAX(wsc.progress_date), CURRENT_DATE()) AS progress_date
                FROM workload_shadow_worker_current wsc
                LEFT JOIN managers manager
                  ON manager.manager_id = wsc.manager_id
            ) worker_totals
            CROSS JOIN (
                SELECT COUNT(*) AS transfer_case_count,
                       COALESCE(SUM(CASE
                           WHEN staffing_required = TRUE THEN 1
                           ELSE 0
                       END), 0) AS staffing_signals
                FROM workload_shadow_transfer_cases
                WHERE active = TRUE
            ) transfer_totals
            """, nativeQuery = true)
    SummaryTotalsProjection summaryTotals();

    @Query(value = """
            SELECT wsc.manager_id AS managerId,
                   COALESCE(
                     NULLIF(TRIM(manager_user.fio), ''),
                     manager_user.username,
                     CONCAT('Менеджер #', wsc.manager_id)
                   ) AS managerName,
                   COUNT(*) AS workerCount,
                   SUM(CASE
                       WHEN wsc.progress_percent >= 100 THEN 1
                       ELSE 0
                   END) AS workersAt100,
                   ROUND(AVG(wsc.progress_percent), 2) AS progressPercent,
                   COALESCE(transfer_stats.transfer_case_count, 0) AS transferCaseCount,
                   COALESCE(transfer_stats.staffing_required, FALSE) AS staffingRequired,
                   CASE
                     WHEN manager.audit_telegram_group_chat_id < 0 THEN TRUE
                     ELSE FALSE
                   END AS groupConnected
            FROM workload_shadow_worker_current wsc
            JOIN managers manager
              ON manager.manager_id = wsc.manager_id
            LEFT JOIN users manager_user
              ON manager_user.id = manager.user_id
            LEFT JOIN (
                SELECT manager_id,
                       COUNT(*) AS transfer_case_count,
                       MAX(staffing_required) AS staffing_required
                FROM workload_shadow_transfer_cases
                WHERE active = TRUE
                GROUP BY manager_id
            ) transfer_stats
              ON transfer_stats.manager_id = wsc.manager_id
            GROUP BY wsc.manager_id,
                     managerName,
                     transfer_stats.transfer_case_count,
                     transfer_stats.staffing_required,
                     manager.audit_telegram_group_chat_id
            ORDER BY progressPercent ASC, managerName
            """, nativeQuery = true)
    List<ManagerSummaryProjection> managerSummaries();

    @Query(value = """
            SELECT wsc.worker_id AS workerId,
                   wsc.worker_user_id AS workerUserId,
                   wsc.manager_id AS managerId,
                   COALESCE(
                     NULLIF(TRIM(manager_user.fio), ''),
                     manager_user.username,
                     CONCAT('Менеджер #', wsc.manager_id)
                   ) AS managerName,
                   COALESCE(
                     NULLIF(TRIM(worker_user.fio), ''),
                     worker_user.username,
                     CONCAT('Специалист #', wsc.worker_id)
                   ) AS workerName,
                   wsc.progress_date AS progressDate,
                   wsc.snapshot_at AS snapshotAt,
                   wsc.progress_percent AS progressPercent,
                   wsc.completed_units AS completedUnits,
                   wsc.active_units AS activeUnits,
                   wsc.eligible_units AS eligibleUnits,
                   wsc.late_excluded_units AS lateExcludedUnits,
                   wsc.feasible_units AS feasibleUnits,
                   wsc.estimated_remaining_minutes AS estimatedRemainingMinutes,
                   wsc.planned_units AS plannedUnits,
                   wsc.incoming_units AS incomingUnits,
                   wsc.urgent_units AS urgentUnits,
                   wsc.external_blocked_units AS externalBlockedUnits,
                   wsc.client_deferred_units AS clientDeferredUnits,
                   wsc.manager_deferred_units AS managerDeferredUnits,
                   (
                     wsc.external_blocked_units
                     + wsc.client_deferred_units
                     + wsc.manager_deferred_units
                   ) AS blockedUnits,
                   wsc.new_units AS newUnits,
                   wsc.correction_units AS correctionUnits,
                   wsc.nagul_units AS nagulUnits,
                   wsc.publish_units AS publishUnits,
                   wsc.recovery_units AS recoveryUnits,
                   wsc.bad_units AS badUnits,
                   wsc.rating AS rating,
                   wsc.hundred_percent_days AS hundredPercentDays,
                   wsc.failure_days AS failureDays,
                   (wsc.hundred_percent_days + wsc.failure_days) AS evaluatedDays,
                   wsc.freeze_credits AS freezeCredits,
                   wsc.transfer_stage AS transferStage,
                   wsc.last_day_reached_100 AS lastDayReached100,
                   wsc.accepts_company_transfers AS acceptsCompanyTransfers,
                   wsc.recipient_eligible AS recipientEligible,
                   wsc.worker_group_connected AS workerGroupConnected,
                   wsc.diagnostic_status AS diagnosticStatus,
                   wsc.last_available_at AS lastAvailableAt
            FROM workload_shadow_worker_current wsc
            LEFT JOIN users worker_user
              ON worker_user.id = wsc.worker_user_id
            LEFT JOIN managers manager
              ON manager.manager_id = wsc.manager_id
            LEFT JOIN users manager_user
              ON manager_user.id = manager.user_id
            WHERE (:managerId IS NULL OR wsc.manager_id = :managerId)
            ORDER BY wsc.transfer_stage DESC,
                     wsc.progress_percent ASC,
                     wsc.rating DESC,
                     workerName
            """, nativeQuery = true)
    List<WorkerProjection> workers(@Param("managerId") Long managerId);

    @Query(value = """
            SELECT transfer_case.workload_shadow_transfer_case_id AS id,
                   transfer_case.manager_id AS managerId,
                   COALESCE(
                     NULLIF(TRIM(manager_user.fio), ''),
                     manager_user.username,
                     CONCAT('Менеджер #', transfer_case.manager_id)
                   ) AS managerName,
                   transfer_case.source_worker_id AS sourceWorkerId,
                   COALESCE(
                     NULLIF(TRIM(source_user.fio), ''),
                     source_user.username,
                     CONCAT('Специалист #', transfer_case.source_worker_id)
                   ) AS sourceWorkerName,
                   transfer_case.company_id AS companyId,
                   transfer_case.company_title AS companyTitle,
                   transfer_case.failure_number AS failureNumber,
                   transfer_case.transfer_percent AS transferPercent,
                   transfer_case.selection_rank AS selectionRank,
                   transfer_case.problem_units AS problemUnits,
                   transfer_case.estimated_minutes AS estimatedMinutes,
                   transfer_case.active_order_count AS activeOrderCount,
                   transfer_case.new_unit_count AS newUnitCount,
                   transfer_case.correction_count AS correctionCount,
                   transfer_case.nagul_count AS nagulCount,
                   transfer_case.publish_count AS publishCount,
                   transfer_case.recovery_count AS recoveryCount,
                   transfer_case.bad_count AS badCount,
                   transfer_case.graph_warning_count AS graphWarningCount,
                   transfer_case.graph_error_count AS graphErrorCount,
                   transfer_case.graph_warning_codes AS graphWarningCodes,
                   transfer_case.graph_error_codes AS graphErrorCodes,
                   transfer_case.staffing_required AS staffingRequired,
                   transfer_case.fallback_worker_id AS fallbackWorkerId,
                   COALESCE(
                     NULLIF(TRIM(fallback_user.fio), ''),
                     fallback_user.username
                   ) AS fallbackWorkerName,
                   transfer_case.fallback_review_id AS fallbackReviewId,
                   transfer_case.status AS status,
                   transfer_case.first_detected_at AS firstDetectedAt,
                   transfer_case.last_seen_at AS lastSeenAt
            FROM workload_shadow_transfer_cases transfer_case
            JOIN managers manager
              ON manager.manager_id = transfer_case.manager_id
            LEFT JOIN users manager_user
              ON manager_user.id = manager.user_id
            JOIN workers source_worker
              ON source_worker.worker_id = transfer_case.source_worker_id
            LEFT JOIN users source_user
              ON source_user.id = source_worker.user_id
            LEFT JOIN workers fallback_worker
              ON fallback_worker.worker_id = transfer_case.fallback_worker_id
            LEFT JOIN users fallback_user
              ON fallback_user.id = fallback_worker.user_id
            WHERE transfer_case.active = TRUE
              AND (:managerId IS NULL OR transfer_case.manager_id = :managerId)
            ORDER BY transfer_case.failure_number DESC,
                     transfer_case.manager_id,
                     transfer_case.selection_rank,
                     transfer_case.company_title
            """, nativeQuery = true)
    List<TransferCaseProjection> transferCases(@Param("managerId") Long managerId);

    @Query(value = """
            SELECT candidate.transfer_case_id AS transferCaseId,
                   candidate.worker_id AS workerId,
                   COALESCE(
                     NULLIF(TRIM(worker_user.fio), ''),
                     worker_user.username,
                     CONCAT('Специалист #', candidate.worker_id)
                   ) AS workerName,
                   candidate.sequence_number AS sequenceNumber,
                   candidate.rating AS rating,
                   candidate.hundred_percent_days AS hundredPercentDays,
                   candidate.failure_days AS failureDays,
                   candidate.current_estimated_minutes AS currentEstimatedMinutes,
                   candidate.worker_group_connected AS workerGroupConnected,
                   candidate.simulated_offer_status AS simulatedOfferStatus
            FROM workload_shadow_transfer_candidates candidate
            JOIN workers worker
              ON worker.worker_id = candidate.worker_id
            LEFT JOIN users worker_user
              ON worker_user.id = worker.user_id
            WHERE candidate.transfer_case_id IN (:caseIds)
            ORDER BY candidate.transfer_case_id, candidate.sequence_number
            """, nativeQuery = true)
    List<TransferCandidateProjection> transferCandidates(
            @Param("caseIds") Collection<Long> caseIds
    );

    @Query(value = """
            SELECT wse.workload_shadow_event_id AS id,
                   wse.severity AS severity,
                   wse.event_type AS eventType,
                   wse.manager_id AS managerId,
                   COALESCE(
                     NULLIF(TRIM(manager_user.fio), ''),
                     manager_user.username
                   ) AS managerName,
                   wse.worker_id AS workerId,
                   COALESCE(
                     NULLIF(TRIM(worker_user.fio), ''),
                     worker_user.username
                   ) AS workerName,
                   wse.company_id AS companyId,
                   company.company_title AS companyTitle,
                   wse.title AS title,
                   wse.message AS message,
                   wse.target_group_type AS targetGroupType,
                   CASE
                     WHEN wse.target_group_type = 'MANAGER_AUDIT'
                      AND wse.target_group_chat_id < 0
                      AND wse.target_group_chat_id = manager.audit_telegram_group_chat_id
                     THEN TRUE
                     ELSE FALSE
                   END AS targetGroupConnected,
                   wse.delivery_status AS deliveryStatus,
                   wse.delivery_attempts AS deliveryAttempts,
                   wse.occurrence_count AS occurrenceCount,
                   wse.first_seen_at AS firstSeenAt,
                   wse.last_seen_at AS lastSeenAt,
                   wse.delivered_at AS deliveredAt,
                   wse.last_error_code AS lastErrorCode,
                   wse.last_error AS lastError,
                   wse.active AS active
            FROM workload_shadow_events wse
            LEFT JOIN managers manager
              ON manager.manager_id = wse.manager_id
            LEFT JOIN users manager_user
              ON manager_user.id = manager.user_id
            LEFT JOIN workers worker
              ON worker.worker_id = wse.worker_id
            LEFT JOIN users worker_user
              ON worker_user.id = worker.user_id
            LEFT JOIN companies company
              ON company.company_id = wse.company_id
            ORDER BY wse.active DESC,
                     wse.last_seen_at DESC,
                     wse.workload_shadow_event_id DESC
            LIMIT :rowLimit
            """, nativeQuery = true)
    List<EventProjection> events(@Param("rowLimit") int rowLimit);

    @Query(value = """
            SELECT sample_count AS sampleCount,
                   average_seconds AS averageSeconds,
                   effective_minutes AS effectiveMinutes,
                   minimum_minutes AS minimumMinutes,
                   estimate_source AS estimateSource,
                   calculated_at AS calculatedAt
            FROM workload_shadow_estimate_stats
            WHERE section_code = 'NAGUL'
            """, nativeQuery = true)
    Optional<WalkEstimateProjection> nagulEstimate();

    interface SummaryTotalsProjection {
        Long getManagerCount();
        Long getWorkerCount();
        Long getWorkersAt100();
        Long getAtRiskWorkers();
        Long getTransferCases();
        Long getStaffingSignals();
        Long getLateExcludedUnits();
        Long getMissingManagerGroups();
        Long getMissingWorkerGroups();
        LocalDateTime getUpdatedAt();
        LocalDate getProgressDate();
    }

    interface ManagerSummaryProjection {
        Long getManagerId();
        String getManagerName();
        Long getWorkerCount();
        Long getWorkersAt100();
        BigDecimal getProgressPercent();
        Long getTransferCaseCount();
        Object getStaffingRequired();
        Object getGroupConnected();
    }

    interface WorkerProjection {
        Long getWorkerId();
        Long getWorkerUserId();
        Long getManagerId();
        String getManagerName();
        String getWorkerName();
        LocalDate getProgressDate();
        LocalDateTime getSnapshotAt();
        BigDecimal getProgressPercent();
        Long getCompletedUnits();
        Long getActiveUnits();
        Long getEligibleUnits();
        Long getLateExcludedUnits();
        Long getFeasibleUnits();
        Long getEstimatedRemainingMinutes();
        Long getPlannedUnits();
        Long getIncomingUnits();
        Long getUrgentUnits();
        Long getExternalBlockedUnits();
        Long getClientDeferredUnits();
        Long getManagerDeferredUnits();
        Long getBlockedUnits();
        Long getNewUnits();
        Long getCorrectionUnits();
        Long getNagulUnits();
        Long getPublishUnits();
        Long getRecoveryUnits();
        Long getBadUnits();
        BigDecimal getRating();
        Integer getHundredPercentDays();
        Integer getFailureDays();
        Integer getEvaluatedDays();
        Integer getFreezeCredits();
        Integer getTransferStage();
        Object getLastDayReached100();
        Object getAcceptsCompanyTransfers();
        Object getRecipientEligible();
        Object getWorkerGroupConnected();
        String getDiagnosticStatus();
        LocalDateTime getLastAvailableAt();
    }

    interface TransferCaseProjection {
        Long getId();
        Long getManagerId();
        String getManagerName();
        Long getSourceWorkerId();
        String getSourceWorkerName();
        Long getCompanyId();
        String getCompanyTitle();
        Integer getFailureNumber();
        Integer getTransferPercent();
        Integer getSelectionRank();
        Long getProblemUnits();
        Long getEstimatedMinutes();
        Long getActiveOrderCount();
        Long getNewUnitCount();
        Long getCorrectionCount();
        Long getNagulCount();
        Long getPublishCount();
        Long getRecoveryCount();
        Long getBadCount();
        Integer getGraphWarningCount();
        Integer getGraphErrorCount();
        String getGraphWarningCodes();
        String getGraphErrorCodes();
        Object getStaffingRequired();
        Long getFallbackWorkerId();
        String getFallbackWorkerName();
        Long getFallbackReviewId();
        String getStatus();
        LocalDateTime getFirstDetectedAt();
        LocalDateTime getLastSeenAt();
    }

    interface TransferCandidateProjection {
        Long getTransferCaseId();
        Long getWorkerId();
        String getWorkerName();
        Integer getSequenceNumber();
        BigDecimal getRating();
        Integer getHundredPercentDays();
        Integer getFailureDays();
        Long getCurrentEstimatedMinutes();
        Object getWorkerGroupConnected();
        String getSimulatedOfferStatus();
    }

    interface EventProjection {
        Long getId();
        String getSeverity();
        String getEventType();
        Long getManagerId();
        String getManagerName();
        Long getWorkerId();
        String getWorkerName();
        Long getCompanyId();
        String getCompanyTitle();
        String getTitle();
        String getMessage();
        String getTargetGroupType();
        Object getTargetGroupConnected();
        String getDeliveryStatus();
        Integer getDeliveryAttempts();
        Long getOccurrenceCount();
        LocalDateTime getFirstSeenAt();
        LocalDateTime getLastSeenAt();
        LocalDateTime getDeliveredAt();
        String getLastErrorCode();
        String getLastError();
        Object getActive();
    }

    interface WalkEstimateProjection {
        Long getSampleCount();
        Long getAverageSeconds();
        Integer getEffectiveMinutes();
        Integer getMinimumMinutes();
        String getEstimateSource();
        LocalDateTime getCalculatedAt();
    }
}
