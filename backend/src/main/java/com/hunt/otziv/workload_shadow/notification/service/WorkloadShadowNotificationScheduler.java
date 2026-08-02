package com.hunt.otziv.workload_shadow.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkloadShadowNotificationScheduler {

    private final WorkloadShadowNotificationDispatcher dispatcher;

    @Scheduled(
            fixedDelayString = "${workload.shadow.notification-delay-ms:60000}",
            initialDelayString = "${workload.shadow.notification-initial-delay-ms:90000}"
    )
    public void dispatch() {
        try {
            WorkloadShadowNotificationDispatcher.DispatchSummary summary = dispatcher.dispatchDue();
            if (!summary.disabled() && (summary.claimed() > 0 || summary.dead() > 0)) {
                log.info(
                        "Workload shadow notifications: scanned={}, claimed={}, sent={}, retried={}, dead={}",
                        summary.scanned(),
                        summary.claimed(),
                        summary.sent(),
                        summary.retried(),
                        summary.dead()
                );
            }
        } catch (RuntimeException exception) {
            log.error("Workload shadow notification dispatcher failed", exception);
        }
    }
}
