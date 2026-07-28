package com.hunt.otziv.workload_shadow.maintenance;

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
import com.hunt.otziv.workload_shadow.service.WorkloadShadowBusinessTime;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WorkloadShadowMaintenanceService {

    private static final String STALE_RUN_MINUTES = "workload.shadow.stale-run-minutes";
    private static final String RUN_RETENTION_DAYS = "workload.shadow.run-retention-days";
    private static final String EVENT_RETENTION_DAYS = "workload.shadow.event-retention-days";
    private static final String DAILY_RETENTION_DAYS = "workload.shadow.daily-retention-days";
    private static final String DECISION_RETENTION_DAYS =
            "workload.shadow.decision-retention-days";
    private static final String MAINTENANCE_BATCH_SIZE = "workload.shadow.maintenance-batch-size";

    private final WorkloadShadowNotificationStore store;
    private final AppSettingService settings;
    private final WorkloadShadowMetrics metrics;
    private final Clock clock;

    @Autowired
    public WorkloadShadowMaintenanceService(
            WorkloadShadowNotificationStore store,
            AppSettingService settings,
            WorkloadShadowMetrics metrics
    ) {
        this(store, settings, metrics, Clock.systemDefaultZone());
    }

    WorkloadShadowMaintenanceService(
            WorkloadShadowNotificationStore store,
            AppSettingService settings,
            WorkloadShadowMetrics metrics,
            Clock clock
    ) {
        this.store = store;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    public RepairSummary repairStaleState() {
        LocalDateTime now = WorkloadShadowBusinessTime.now(settings, clock);
        int batchSize = batchSize();
        int staleRunMinutes = bounded(settings.getInt(STALE_RUN_MINUTES, 30), 5, 240);

        int cancelledEvents = store.cancelInactiveDeliveries(now, batchSize);
        int retriedEvents = store.retryStaleProcessingEvents(now, batchSize);
        int failedRuns = store.failStaleRuns(
                now.minusMinutes(staleRunMinutes),
                now,
                batchSize
        );

        metrics.recordMaintenance(INACTIVE_EVENT_CANCELLED, cancelledEvents);
        metrics.recordMaintenance(STALE_EVENT_RETRIED, retriedEvents);
        metrics.recordMaintenance(STALE_RUN_FAILED, failedRuns);
        return new RepairSummary(failedRuns, retriedEvents, cancelledEvents);
    }

    public RetentionSummary cleanupRetention() {
        LocalDateTime now = WorkloadShadowBusinessTime.now(settings, clock);
        LocalDate today = WorkloadShadowBusinessTime.today(settings, clock);
        int batchSize = batchSize();
        int runRetentionDays = bounded(settings.getInt(RUN_RETENTION_DAYS, 30), 7, 3650);
        int eventRetentionDays = bounded(settings.getInt(EVENT_RETENTION_DAYS, 90), 7, 3650);
        int dailyRetentionDays = bounded(settings.getInt(DAILY_RETENTION_DAYS, 400), 31, 3650);
        int decisionRetentionDays = bounded(
                settings.getInt(DECISION_RETENTION_DAYS, 60),
                7,
                365
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

        metrics.recordMaintenance(EVENT_DELETED, deletedEvents);
        metrics.recordMaintenance(TRANSFER_CASE_DELETED, deletedTransferCases);
        metrics.recordMaintenance(RUN_DELETED, deletedRuns);
        metrics.recordMaintenance(DAILY_DELETED, deletedDaily);
        metrics.recordMaintenance(LATE_BATCH_DELETED, deletedLateBatches);
        return new RetentionSummary(
                deletedEvents,
                deletedTransferCases,
                deletedRuns,
                deletedDaily,
                deletedLateBatches
        );
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
            int cancelledEvents
    ) {
    }

    public record RetentionSummary(
            int deletedEvents,
            int deletedTransferCases,
            int deletedRuns,
            int deletedDaily,
            int deletedLateBatches
    ) {
    }
}
