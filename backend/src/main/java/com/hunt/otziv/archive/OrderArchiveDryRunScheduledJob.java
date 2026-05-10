package com.hunt.otziv.archive;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "otziv.archive.orders.schedule", name = "enabled", havingValue = "true")
public class OrderArchiveDryRunScheduledJob {

    private final OrderArchiveDryRunService archiveDryRunService;

    @Scheduled(
            cron = "${otziv.archive.orders.schedule.cron:0 15 4 * * *}",
            zone = "${otziv.archive.orders.schedule.zone:Asia/Irkutsk}"
    )
    public void runScheduledDryRun() {
        try {
            Object result = archiveDryRunService.runScheduledConfiguredArchive();
            log.info("Scheduled order archive completed: {}", result);
        } catch (RuntimeException exception) {
            log.error("Scheduled order archive failed", exception);
        }
    }
}
