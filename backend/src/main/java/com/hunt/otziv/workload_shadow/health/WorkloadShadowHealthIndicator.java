package com.hunt.otziv.workload_shadow.health;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("workloadShadowHealthIndicator")
@RequiredArgsConstructor
public class WorkloadShadowHealthIndicator implements HealthIndicator {

    private final WorkloadShadowHealthService healthService;

    @Override
    public Health health() {
        try {
            WorkloadShadowHealthSnapshot snapshot = healthService.snapshot();
            /*
             * This is an observational subsystem. Its diagnostic backlog must not
             * downgrade the application's aggregate /actuator/health status and
             * trigger a restart of the production site.
             */
            return Health.up()
                    .withDetail("shadowMode", true)
                    .withDetail("diagnosticAvailable", true)
                    .withDetail("status", snapshot.status())
                    .withDetail("degraded", snapshot.degraded())
                    .withDetail("groupNotificationsEnabled", snapshot.groupNotificationsEnabled())
                    .withDetail("dueEvents", snapshot.dueEvents())
                    .withDetail("processingEvents", snapshot.processingEvents())
                    .withDetail("staleProcessingEvents", snapshot.staleProcessingEvents())
                    .withDetail("deadEvents", snapshot.deadEvents())
                    .withDetail("missingGroupBindings", snapshot.missingGroupBindings())
                    .withDetail("runningRuns", snapshot.runningRuns())
                    .withDetail("staleRunningRuns", snapshot.staleRunningRuns())
                    .withDetail("graphWarningCases", snapshot.graphWarningCases())
                    .withDetail("graphErrorCases", snapshot.graphErrorCases())
                    .withDetail("expiredRecalculationLocks", snapshot.expiredRecalculationLocks())
                    .withDetail("snapshotAgeSeconds", snapshot.snapshotAgeSeconds())
                    .withDetail("oldestDueAgeSeconds", snapshot.oldestDueAgeSeconds())
                    .withDetail("lastSnapshotAt", value(snapshot.lastSnapshotAt()))
                    .withDetail("lastSuccessfulRunAt", value(snapshot.lastSuccessfulRunAt()))
                    .build();
        } catch (RuntimeException exception) {
            return Health.up()
                    .withDetail("shadowMode", true)
                    .withDetail("diagnosticAvailable", false)
                    .withDetail("status", "HEALTH_CHECK_FAILED")
                    .withDetail("errorType", exception.getClass().getSimpleName())
                    .build();
        }
    }

    private String value(Object value) {
        return value == null ? "never" : value.toString();
    }
}
