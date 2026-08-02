package com.hunt.otziv.workload_shadow.maintenance.service;

import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.DAILY_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.EVENT_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.INACTIVE_EVENT_CANCELLED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.LATE_BATCH_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.RUN_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.STALE_EVENT_RETRIED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.STALE_RUN_FAILED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.TRANSFER_CASE_DELETED;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowNotificationStore;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferMaintenanceRepository;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowBusinessTime;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class WorkloadShadowMaintenanceService {

    private static final String STALE_RUN_MINUTES = "workload.shadow.stale-run-minutes";
    private static final String RUN_RETENTION_DAYS = "workload.shadow.run-retention-days";
    private static final String EVENT_RETENTION_DAYS = "workload.shadow.event-retention-days";
    private static final String DAILY_RETENTION_DAYS = "workload.shadow.daily-retention-days";
    private static final String DECISION_RETENTION_DAYS =
            "workload.shadow.decision-retention-days";
    private static final String MAINTENANCE_BATCH_SIZE = "workload.shadow.maintenance-batch-size";
    private static final String LIVE_RETENTION_DAYS = "workload.live.retention-days";
    private static final int ORPHAN_READY_GRACE_MINUTES = 5;

    private final WorkloadShadowNotificationStore store;
    private final WorkloadTransferMaintenanceRepository transferMaintenanceRepository;
    private final WorkloadMaintenanceStatusService maintenanceStatusService;
    private final AppSettingService settings;
    private final WorkloadShadowMetrics metrics;
    private final Clock clock;

    @Autowired
    public WorkloadShadowMaintenanceService(
            WorkloadShadowNotificationStore store,
            WorkloadTransferMaintenanceRepository transferMaintenanceRepository,
            WorkloadMaintenanceStatusService maintenanceStatusService,
            AppSettingService settings,
            WorkloadShadowMetrics metrics
    ) {
        this(
                store,
                transferMaintenanceRepository,
                maintenanceStatusService,
                settings,
                metrics,
                Clock.systemDefaultZone()
        );
    }

    WorkloadShadowMaintenanceService(
            WorkloadShadowNotificationStore store,
            WorkloadTransferMaintenanceRepository transferMaintenanceRepository,
            WorkloadMaintenanceStatusService maintenanceStatusService,
            AppSettingService settings,
            WorkloadShadowMetrics metrics,
            Clock clock
    ) {
        this.store = store;
        this.transferMaintenanceRepository = transferMaintenanceRepository;
        this.maintenanceStatusService = maintenanceStatusService;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public RepairSummary repairStaleState() {
        LocalDateTime now = WorkloadShadowBusinessTime.now(settings, clock);
        maintenanceStatusService.recordStarted(
                WorkloadMaintenanceStatusService.TASK_REPAIR,
                now
        );
        try {
            int batchSize = batchSize();
            int staleRunMinutes = bounded(
                    settings.getInt(STALE_RUN_MINUTES, 30),
                    5,
                    240
            );

            int cancelledEvents = store.cancelInactiveDeliveries(now, batchSize);
            int retriedEvents = store.retryStaleProcessingEvents(now, batchSize);
            int failedRuns = store.failStaleRuns(
                    now.minusMinutes(staleRunMinutes),
                    now,
                    batchSize
            );
            int retriedLiveOffers =
                    transferMaintenanceRepository.retryStaleOfferDeliveries(
                            now,
                            batchSize
                    )
                    + transferMaintenanceRepository.retryStaleEmergencyNotifications(
                            now,
                            batchSize
                    );
            int repairedOrphanReadyOffers =
                    repairOrphanReadyOffers(now, batchSize);
            int cancelledExpiredWorkflows = closeExpiredWorkflows(
                    WorkloadShadowBusinessTime.today(settings, clock),
                    now,
                    batchSize
            );

            metrics.recordMaintenance(INACTIVE_EVENT_CANCELLED, cancelledEvents);
            metrics.recordMaintenance(STALE_EVENT_RETRIED, retriedEvents);
            metrics.recordMaintenance(STALE_RUN_FAILED, failedRuns);
            recordSuccessAfterCommit(
                    WorkloadMaintenanceStatusService.TASK_REPAIR,
                    WorkloadShadowBusinessTime.now(settings, clock)
            );
            return new RepairSummary(
                    failedRuns,
                    retriedEvents,
                    cancelledEvents,
                    retriedLiveOffers,
                    repairedOrphanReadyOffers,
                    cancelledExpiredWorkflows
            );
        } catch (RuntimeException exception) {
            recordFailure(WorkloadMaintenanceStatusService.TASK_REPAIR, exception);
            throw exception;
        }
    }

    @Transactional
    public RetentionSummary cleanupRetention() {
        LocalDateTime now = WorkloadShadowBusinessTime.now(settings, clock);
        maintenanceStatusService.recordStarted(
                WorkloadMaintenanceStatusService.TASK_RETENTION,
                now
        );
        try {
            LocalDate today = WorkloadShadowBusinessTime.today(settings, clock);
            int batchSize = batchSize();
            int runRetentionDays = bounded(
                    settings.getInt(RUN_RETENTION_DAYS, 30),
                    7,
                    3650
            );
            int eventRetentionDays = bounded(
                    settings.getInt(EVENT_RETENTION_DAYS, 90),
                    7,
                    3650
            );
            int dailyRetentionDays = bounded(
                    settings.getInt(DAILY_RETENTION_DAYS, 400),
                    31,
                    3650
            );
            int decisionRetentionDays = bounded(
                    settings.getInt(DECISION_RETENTION_DAYS, 60),
                    7,
                    365
            );
            int liveRetentionDays = bounded(
                    settings.getInt(LIVE_RETENTION_DAYS, 400),
                    31,
                    3650
            );

            int deletedEvents = store.deleteTerminalInactiveEvents(
                    now.minusDays(eventRetentionDays),
                    batchSize
            );
            int deletedTransferCases = store.deleteInactiveResolvedTransferCases(
                    now.minusDays(eventRetentionDays),
                    batchSize
            );
            int deletedRuns = store.deleteTerminalRuns(
                    now.minusDays(runRetentionDays),
                    batchSize
            );
            LocalDate dailyCutoff = today.minusDays(dailyRetentionDays);
            int deletedDaily = store.deleteFinalizedDaily(
                    dailyCutoff,
                    batchSize
            );
            int deletedLateBatches = store.deleteLateBatches(
                    today.minusDays(decisionRetentionDays),
                    batchSize
            );
            LocalDateTime liveCutoff = now.minusDays(liveRetentionDays);
            int deletedAssignmentAudit =
                    transferMaintenanceRepository.deleteOldAssignmentAudit(
                            liveCutoff,
                            batchSize
                    );
            int deletedExecutions = transferMaintenanceRepository.deleteOldExecutions(
                    liveCutoff,
                    batchSize
            );
            int deletedWorkflows = transferMaintenanceRepository.deleteOldWorkflows(
                    liveCutoff,
                    batchSize
            );
            int deletedEmergencyAssignments =
                    transferMaintenanceRepository.deleteOldEmergencyAssignments(
                            liveCutoff,
                            batchSize
                    );

            metrics.recordMaintenance(EVENT_DELETED, deletedEvents);
            metrics.recordMaintenance(TRANSFER_CASE_DELETED, deletedTransferCases);
            metrics.recordMaintenance(RUN_DELETED, deletedRuns);
            metrics.recordMaintenance(DAILY_DELETED, deletedDaily);
            metrics.recordMaintenance(LATE_BATCH_DELETED, deletedLateBatches);
            recordSuccessAfterCommit(
                    WorkloadMaintenanceStatusService.TASK_RETENTION,
                    WorkloadShadowBusinessTime.now(settings, clock)
            );
            return new RetentionSummary(
                    deletedEvents,
                    deletedTransferCases,
                    deletedRuns,
                    deletedDaily,
                    deletedLateBatches,
                    deletedAssignmentAudit,
                    deletedExecutions,
                    deletedWorkflows,
                    deletedEmergencyAssignments
            );
        } catch (RuntimeException exception) {
            recordFailure(WorkloadMaintenanceStatusService.TASK_RETENTION, exception);
            throw exception;
        }
    }

    private void recordSuccessAfterCommit(String task, LocalDateTime completedAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            maintenanceStatusService.recordSucceeded(task, completedAt);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        maintenanceStatusService.recordSucceeded(task, completedAt);
                    }
                }
        );
    }

    private int repairOrphanReadyOffers(
            LocalDateTime now,
            int batchSize
    ) {
        List<Long> offerIds =
                transferMaintenanceRepository.lockOrphanReadyOfferIds(
                        now.minusMinutes(ORPHAN_READY_GRACE_MINUTES),
                        batchSize
                );
        if (offerIds.isEmpty()) {
            return 0;
        }
        transferMaintenanceRepository.closeCandidatesForOrphanReadyOffers(
                offerIds,
                now
        );
        int cancelled =
                transferMaintenanceRepository.cancelOrphanReadyOffers(
                        offerIds,
                        now
                );
        requireExactBatch("orphan READY offers", offerIds.size(), cancelled);
        return cancelled;
    }

    private int closeExpiredWorkflows(
            LocalDate today,
            LocalDateTime now,
            int batchSize
    ) {
        List<Long> workflowIds =
                transferMaintenanceRepository.lockExpiredWorkflowIds(
                        today,
                        batchSize
                );
        if (workflowIds.isEmpty()) {
            return 0;
        }
        transferMaintenanceRepository.closeOffersForExpiredWorkflows(
                workflowIds,
                now
        );
        transferMaintenanceRepository.closeCandidatesForExpiredWorkflows(
                workflowIds,
                now
        );
        long openChildren =
                transferMaintenanceRepository.countOpenChildrenForWorkflows(
                        workflowIds
                );
        if (openChildren != 0) {
            throw new IllegalStateException(
                    "Maintenance invariant failed for expired workflow children: open="
                            + openChildren
            );
        }
        int cancelled = transferMaintenanceRepository.cancelExpiredWorkflows(
                workflowIds,
                today,
                now
        );
        requireExactBatch("expired workflows", workflowIds.size(), cancelled);
        return cancelled;
    }

    private void requireExactBatch(
            String batchName,
            int expected,
            int changed
    ) {
        if (changed != expected) {
            throw new IllegalStateException(
                    "Maintenance invariant failed for "
                            + batchName
                            + ": expected="
                            + expected
                            + ", changed="
                            + changed
            );
        }
    }

    private void recordFailure(String task, RuntimeException exception) {
        try {
            maintenanceStatusService.recordFailed(
                    task,
                    WorkloadShadowBusinessTime.now(settings, clock),
                    exception
            );
        } catch (RuntimeException statusException) {
            exception.addSuppressed(statusException);
        }
    }

    private int batchSize() {
        return bounded(settings.getInt(MAINTENANCE_BATCH_SIZE, 1000), 100, 5000);
    }

    private int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record RepairSummary(
            int failedRuns,
            int retriedEvents,
            int cancelledEvents,
            int retriedLiveOffers,
            int repairedOrphanReadyOffers,
            int cancelledExpiredWorkflows
    ) {
    }

    public record RetentionSummary(
            int deletedEvents,
            int deletedTransferCases,
            int deletedRuns,
            int deletedDaily,
            int deletedLateBatches,
            int deletedAssignmentAudit,
            int deletedExecutions,
            int deletedWorkflows,
            int deletedEmergencyAssignments
    ) {
    }
}
