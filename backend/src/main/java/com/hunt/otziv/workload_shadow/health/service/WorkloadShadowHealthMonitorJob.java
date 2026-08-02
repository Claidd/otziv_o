package com.hunt.otziv.workload_shadow.health.service;

import com.hunt.otziv.workload_shadow.health.dto.WorkloadShadowHealthSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkloadShadowHealthMonitorJob {

    private final WorkloadShadowHealthService healthService;
    private volatile String lastLoggedStatus;

    @Scheduled(
            fixedDelayString = "${workload.shadow.health-delay-ms:300000}",
            initialDelayString = "${workload.shadow.health-initial-delay-ms:120000}"
    )
    public void refreshMetrics() {
        try {
            WorkloadShadowHealthSnapshot snapshot = healthService.snapshot();
            String previous = lastLoggedStatus;
            lastLoggedStatus = snapshot.status();
            if (snapshot.degraded() && !snapshot.status().equals(previous)) {
                log.warn(
                        "Workload shadow health status={} due={} staleProcessing={} dead={} missingGroups={} staleRuns={}",
                        snapshot.status(),
                        snapshot.dueEvents(),
                        snapshot.staleProcessingEvents(),
                        snapshot.deadEvents(),
                        snapshot.missingGroupBindings(),
                        snapshot.staleRunningRuns()
                );
            } else if (!snapshot.degraded()
                    && previous != null
                    && !"UP".equals(previous)
                    && !"PAUSED".equals(previous)) {
                log.info("Workload shadow health recovered status={}", snapshot.status());
            }
        } catch (RuntimeException exception) {
            log.error("Workload shadow health refresh failed", exception);
        }
    }
}
