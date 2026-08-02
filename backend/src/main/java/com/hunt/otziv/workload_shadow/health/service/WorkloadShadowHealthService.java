package com.hunt.otziv.workload_shadow.health.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.health.dto.WorkloadMaintenanceHealthSnapshot;
import com.hunt.otziv.workload_shadow.health.dto.WorkloadShadowHealthData;
import com.hunt.otziv.workload_shadow.health.dto.WorkloadShadowHealthSnapshot;
import com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics;
import com.hunt.otziv.workload_shadow.maintenance.service.WorkloadMaintenanceStatusService;
import com.hunt.otziv.workload_shadow.notification.service.WorkloadShadowNotificationDispatcher;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowNotificationStore;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowBusinessTime;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WorkloadShadowHealthService {

    private static final String STALE_RUN_MINUTES = "workload.shadow.stale-run-minutes";

    private final WorkloadShadowNotificationStore store;
    private final WorkloadMaintenanceStatusService maintenanceStatusService;
    private final AppSettingService settings;
    private final WorkloadShadowMetrics metrics;
    private final Clock clock;

    @Autowired
    public WorkloadShadowHealthService(
            WorkloadShadowNotificationStore store,
            WorkloadMaintenanceStatusService maintenanceStatusService,
            AppSettingService settings,
            WorkloadShadowMetrics metrics
    ) {
        this(
                store,
                maintenanceStatusService,
                settings,
                metrics,
                Clock.systemDefaultZone()
        );
    }

    WorkloadShadowHealthService(
            WorkloadShadowNotificationStore store,
            WorkloadMaintenanceStatusService maintenanceStatusService,
            AppSettingService settings,
            WorkloadShadowMetrics metrics,
            Clock clock
    ) {
        this.store = store;
        this.maintenanceStatusService = maintenanceStatusService;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    public WorkloadShadowHealthSnapshot snapshot() {
        LocalDateTime now = WorkloadShadowBusinessTime.now(settings, clock);
        int staleRunMinutes = bounded(settings.getInt(STALE_RUN_MINUTES, 30), 5, 240);
        boolean notificationsEnabled = settings.getBoolean(
                WorkloadShadowNotificationDispatcher.GROUP_NOTIFICATIONS_ENABLED,
                true
        );
        WorkloadShadowHealthData data = store.healthData(
                now,
                now.minusMinutes(staleRunMinutes)
        );
        long oldestDueAgeSeconds = data.oldestDueEventAt() == null
                ? 0
                : Math.max(0, Duration.between(data.oldestDueEventAt(), now).toSeconds());
        long snapshotAgeSeconds = data.lastSnapshotAt() == null
                ? 0
                : Math.max(0, Duration.between(data.lastSnapshotAt(), now).toSeconds());
        WorkloadMaintenanceHealthSnapshot maintenance;
        try {
            maintenance = maintenanceStatusService.health(now);
        } catch (RuntimeException exception) {
            maintenance = unavailableMaintenance(exception);
        }

        String status;
        if (data.staleProcessingEvents() > 0
                || data.staleRunningRuns() > 0
                || data.expiredRecalculationLocks() > 0) {
            status = "STALE";
        } else if (data.deadEvents() > 0
                || data.missingGroupBindings() > 0
                || data.graphWarningCases() > 0
                || data.graphErrorCases() > 0
                || !maintenance.healthy()
                || (notificationsEnabled && oldestDueAgeSeconds > 300)) {
            status = "DEGRADED";
        } else if (!notificationsEnabled) {
            status = "PAUSED";
        } else {
            status = "UP";
        }

        WorkloadShadowHealthSnapshot snapshot = new WorkloadShadowHealthSnapshot(
                status,
                now,
                notificationsEnabled,
                data.dueEvents(),
                data.processingEvents(),
                data.staleProcessingEvents(),
                data.deadEvents(),
                data.missingGroupBindings(),
                data.runningRuns(),
                data.staleRunningRuns(),
                data.graphWarningCases(),
                data.graphErrorCases(),
                data.expiredRecalculationLocks(),
                snapshotAgeSeconds,
                oldestDueAgeSeconds,
                data.oldestDueEventAt(),
                data.lastSuccessfulRunAt(),
                data.lastSnapshotAt(),
                maintenance
        );
        metrics.updateHealth(snapshot);
        return snapshot;
    }

    private int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private WorkloadMaintenanceHealthSnapshot unavailableMaintenance(
            RuntimeException exception
    ) {
        String message = exception.getMessage();
        if (message != null && message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        return new WorkloadMaintenanceHealthSnapshot(
                false,
                "HEALTH_CHECK_FAILED",
                "HEALTH_CHECK_FAILED",
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                exception.getClass().getSimpleName(),
                message
        );
    }
}
