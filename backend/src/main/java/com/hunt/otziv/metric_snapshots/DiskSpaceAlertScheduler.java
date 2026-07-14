package com.hunt.otziv.metric_snapshots;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DiskSpaceAlertScheduler {

    private final DiskSpaceAlertService alertService;

    @Value("${otziv.monitoring.disk.enabled:false}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        checkSafely();
    }

    @Scheduled(
            initialDelayString = "${otziv.monitoring.disk.initial-delay-ms:60000}",
            fixedDelayString = "${otziv.monitoring.disk.fixed-delay-ms:300000}"
    )
    public void scheduledCheck() {
        checkSafely();
    }

    private void checkSafely() {
        if (!enabled) {
            return;
        }
        try {
            alertService.checkAndNotify();
        } catch (RuntimeException e) {
            log.warn("Не удалось проверить свободное место на сервере", e);
        }
    }
}
