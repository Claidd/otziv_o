package com.hunt.otziv.workload_shadow.metrics;

import com.hunt.otziv.workload_shadow.health.WorkloadShadowHealthSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class WorkloadShadowMetrics {

    private static final String DELIVERY_COUNTER = "otziv.workload.shadow.notification.delivery";
    private static final String MAINTENANCE_COUNTER = "otziv.workload.shadow.maintenance.action";

    private final Counter sent;
    private final Counter retried;
    private final Counter dead;
    private final Counter missingGroup;
    private final Counter staleRunFailed;
    private final Counter staleEventRetried;
    private final Counter inactiveEventCancelled;
    private final Counter eventDeleted;
    private final Counter transferCaseDeleted;
    private final Counter runDeleted;
    private final Counter dailyDeleted;
    private final Counter lateBatchDeleted;

    private final AtomicLong notificationsEnabled = new AtomicLong();
    private final AtomicLong dueEvents = new AtomicLong();
    private final AtomicLong processingEvents = new AtomicLong();
    private final AtomicLong staleProcessingEvents = new AtomicLong();
    private final AtomicLong deadEvents = new AtomicLong();
    private final AtomicLong missingGroupBindings = new AtomicLong();
    private final AtomicLong staleRunningRuns = new AtomicLong();
    private final AtomicLong graphWarningCases = new AtomicLong();
    private final AtomicLong graphErrorCases = new AtomicLong();
    private final AtomicLong expiredRecalculationLocks = new AtomicLong();
    private final AtomicLong snapshotAgeSeconds = new AtomicLong();
    private final AtomicLong oldestDueAgeSeconds = new AtomicLong();
    private final AtomicLong maintenanceHealthy = new AtomicLong();

    public WorkloadShadowMetrics(MeterRegistry meterRegistry) {
        sent = deliveryCounter(meterRegistry, "sent");
        retried = deliveryCounter(meterRegistry, "retry");
        dead = deliveryCounter(meterRegistry, "dead");
        missingGroup = deliveryCounter(meterRegistry, "missing_group");
        staleRunFailed = maintenanceCounter(meterRegistry, "stale_run_failed");
        staleEventRetried = maintenanceCounter(meterRegistry, "stale_event_retried");
        inactiveEventCancelled = maintenanceCounter(meterRegistry, "inactive_event_cancelled");
        eventDeleted = maintenanceCounter(meterRegistry, "event_deleted");
        transferCaseDeleted = maintenanceCounter(meterRegistry, "transfer_case_deleted");
        runDeleted = maintenanceCounter(meterRegistry, "run_deleted");
        dailyDeleted = maintenanceCounter(meterRegistry, "daily_deleted");
        lateBatchDeleted = maintenanceCounter(meterRegistry, "late_batch_deleted");

        gauge(meterRegistry, "otziv.workload.shadow.notifications.enabled", notificationsEnabled);
        gauge(meterRegistry, "otziv.workload.shadow.events.due", dueEvents);
        gauge(meterRegistry, "otziv.workload.shadow.events.processing", processingEvents);
        gauge(meterRegistry, "otziv.workload.shadow.events.stale.processing", staleProcessingEvents);
        gauge(meterRegistry, "otziv.workload.shadow.events.dead", deadEvents);
        gauge(meterRegistry, "otziv.workload.shadow.events.missing.group", missingGroupBindings);
        gauge(meterRegistry, "otziv.workload.shadow.runs.stale", staleRunningRuns);
        gauge(meterRegistry, "otziv.workload.shadow.graph.warning.cases", graphWarningCases);
        gauge(meterRegistry, "otziv.workload.shadow.graph.error.cases", graphErrorCases);
        gauge(meterRegistry, "otziv.workload.shadow.locks.expired", expiredRecalculationLocks);
        gauge(meterRegistry, "otziv.workload.shadow.snapshot.age.seconds", snapshotAgeSeconds);
        gauge(meterRegistry, "otziv.workload.shadow.events.oldest.due.seconds", oldestDueAgeSeconds);
        gauge(meterRegistry, "otziv.workload.shadow.maintenance.healthy", maintenanceHealthy);
    }

    public void recordSent() {
        sent.increment();
    }

    public void recordRetry() {
        retried.increment();
    }

    public void recordDead() {
        dead.increment();
    }

    public void recordMissingGroup() {
        missingGroup.increment();
    }

    public void recordMaintenance(WorkloadShadowMaintenanceAction action, long count) {
        if (count <= 0 || action == null) {
            return;
        }
        Counter counter = switch (action) {
            case STALE_RUN_FAILED -> staleRunFailed;
            case STALE_EVENT_RETRIED -> staleEventRetried;
            case INACTIVE_EVENT_CANCELLED -> inactiveEventCancelled;
            case EVENT_DELETED -> eventDeleted;
            case TRANSFER_CASE_DELETED -> transferCaseDeleted;
            case RUN_DELETED -> runDeleted;
            case DAILY_DELETED -> dailyDeleted;
            case LATE_BATCH_DELETED -> lateBatchDeleted;
        };
        counter.increment(count);
    }

    public void updateHealth(WorkloadShadowHealthSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        notificationsEnabled.set(snapshot.groupNotificationsEnabled() ? 1 : 0);
        dueEvents.set(snapshot.dueEvents());
        processingEvents.set(snapshot.processingEvents());
        staleProcessingEvents.set(snapshot.staleProcessingEvents());
        deadEvents.set(snapshot.deadEvents());
        missingGroupBindings.set(snapshot.missingGroupBindings());
        staleRunningRuns.set(snapshot.staleRunningRuns());
        graphWarningCases.set(snapshot.graphWarningCases());
        graphErrorCases.set(snapshot.graphErrorCases());
        expiredRecalculationLocks.set(snapshot.expiredRecalculationLocks());
        snapshotAgeSeconds.set(snapshot.snapshotAgeSeconds());
        oldestDueAgeSeconds.set(snapshot.oldestDueAgeSeconds());
        maintenanceHealthy.set(snapshot.maintenanceHealthy() ? 1 : 0);
    }

    private Counter deliveryCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder(DELIVERY_COUNTER)
                .description("Workload shadow Telegram delivery outcomes")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private Counter maintenanceCounter(MeterRegistry meterRegistry, String action) {
        return Counter.builder(MAINTENANCE_COUNTER)
                .description("Workload shadow maintenance actions")
                .tag("action", action)
                .register(meterRegistry);
    }

    private void gauge(MeterRegistry registry, String name, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get)
                .description("Workload shadow health gauge")
                .register(registry);
    }

    public enum WorkloadShadowMaintenanceAction {
        STALE_RUN_FAILED,
        STALE_EVENT_RETRIED,
        INACTIVE_EVENT_CANCELLED,
        EVENT_DELETED,
        TRANSFER_CASE_DELETED,
        RUN_DELETED,
        DAILY_DELETED,
        LATE_BATCH_DELETED
    }
}
