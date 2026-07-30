package com.hunt.otziv.workload_shadow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkloadEmergencyNotificationScheduler {

    private final WorkloadEmergencyNotificationDeliveryService deliveryService;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 210_000L)
    public void deliverPending() {
        try {
            int delivered = deliveryService.deliverDue();
            if (delivered > 0) {
                log.info(
                        "Delivered {} emergency workload assignment notifications",
                        delivered
                );
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Emergency workload assignment notification delivery failed",
                    exception
            );
        }
    }
}
