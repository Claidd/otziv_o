package com.hunt.otziv.workload_shadow.maintenance.service;

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
                    || summary.cancelledEvents() > 0
                    || summary.retriedLiveOffers() > 0
                    || summary.repairedOrphanReadyOffers() > 0
                    || summary.cancelledExpiredWorkflows() > 0) {
                log.warn(
                        "Workload self-heal: failedRuns={}, retriedEvents={}, cancelledEvents={}, "
                                + "retriedLiveOffers={}, repairedOrphanReadyOffers={}, "
                                + "cancelledExpiredWorkflows={}",
                        summary.failedRuns(),
                        summary.retriedEvents(),
                        summary.cancelledEvents(),
                        summary.retriedLiveOffers(),
                        summary.repairedOrphanReadyOffers(),
                        summary.cancelledExpiredWorkflows()
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
        WorkloadShadowMaintenanceService.RepairSummary repair = null;
        try {
            repair = maintenanceService.repairStaleState();
        } catch (RuntimeException exception) {
            log.error(
                    "Workload stale-state repair failed before nightly retention; "
                            + "bounded retention will still run",
                    exception
            );
        }
        try {
            WorkloadShadowMaintenanceService.RetentionSummary retention =
                    maintenanceService.cleanupRetention();
            log.info(
                    "Workload shadow maintenance complete: failedRuns={}, retriedEvents={}, "
                            + "cancelledEvents={}, deletedEvents={}, deletedRuns={}, deletedDaily={}, "
                            + "deletedTransferCases={}, deletedLateBatches={}, "
                            + "deletedAssignmentAudit={}, deletedExecutions={}, deletedWorkflows={}, "
                            + "deletedEmergencyAssignments={}",
                    repair == null ? 0 : repair.failedRuns(),
                    repair == null ? 0 : repair.retriedEvents(),
                    repair == null ? 0 : repair.cancelledEvents(),
                    retention.deletedEvents(),
                    retention.deletedRuns(),
                    retention.deletedDaily(),
                    retention.deletedTransferCases(),
                    retention.deletedLateBatches(),
                    retention.deletedAssignmentAudit(),
                    retention.deletedExecutions(),
                    retention.deletedWorkflows(),
                    retention.deletedEmergencyAssignments()
            );
        } catch (RuntimeException exception) {
            log.error("Workload bounded retention failed", exception);
        }
    }
}
