package com.hunt.otziv.workload_shadow.maintenance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkloadShadowMaintenanceJob {

    private final WorkloadShadowMaintenanceService maintenanceService;

    @Scheduled(
            fixedDelayString = "${workload.shadow.repair-delay-ms:300000}",
            initialDelayString = "${workload.shadow.repair-initial-delay-ms:180000}"
    )
    public void repairStaleState() {
        try {
            WorkloadShadowMaintenanceService.RepairSummary summary =
                    maintenanceService.repairStaleState();
            if (summary.failedRuns() > 0
                    || summary.retriedEvents() > 0
                    || summary.cancelledEvents() > 0) {
                log.warn(
                        "Workload shadow self-heal: failedRuns={}, retriedEvents={}, cancelledEvents={}",
                        summary.failedRuns(),
                        summary.retriedEvents(),
                        summary.cancelledEvents()
                );
            }
        } catch (RuntimeException exception) {
            log.error("Workload shadow stale-state repair failed", exception);
        }
    }

    @Scheduled(
            cron = "${workload.shadow.maintenance-cron:0 40 4 * * *}",
            zone = "${workload.shadow.maintenance-zone:Asia/Irkutsk}"
    )
    public void cleanupRetention() {
        try {
            WorkloadShadowMaintenanceService.RepairSummary repair =
                    maintenanceService.repairStaleState();
            WorkloadShadowMaintenanceService.RetentionSummary retention =
                    maintenanceService.cleanupRetention();
            log.info(
                    "Workload shadow maintenance complete: failedRuns={}, retriedEvents={}, "
                            + "cancelledEvents={}, deletedEvents={}, deletedRuns={}, deletedDaily={}, "
                            + "deletedTransferCases={}, deletedLateBatches={}",
                    repair.failedRuns(),
                    repair.retriedEvents(),
                    repair.cancelledEvents(),
                    retention.deletedEvents(),
                    retention.deletedRuns(),
                    retention.deletedDaily(),
                    retention.deletedTransferCases(),
                    retention.deletedLateBatches()
            );
        } catch (RuntimeException exception) {
            log.error("Workload shadow nightly maintenance failed", exception);
        }
    }
}
