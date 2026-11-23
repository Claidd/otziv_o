package com.hunt.otziv.metric_snapshots.service;

import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the daily server report at 23:00 (Irkutsk). The lease is kept until it expires, so two application
 * instances at the same minute (a rolling deploy) send it once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OperationsDailyReportScheduler {

    static final String LEASE = "ops-report.daily";
    static final Duration LEASE_DURATION = Duration.ofHours(1);

    private final OperationsDailyReportService reportService;
    private final SchedulerLeaseService leases;

    @Value("${otziv.monitoring.ops-report.enabled:false}")
    private boolean enabled;

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Scheduled(cron = "${otziv.monitoring.ops-report.cron:0 0 23 * * *}",
            zone = "${otziv.monitoring.ops-report.zone:Asia/Irkutsk}")
    public void sendDailyReport() {
        if (!enabled) {
            return;
        }
        try {
            if (leases.tryAcquire(LEASE, LEASE_DURATION).isEmpty()) {
                return;
            }
            int delivered = reportService.send();
            log.info("Daily server report sent to {} Telegram chat(s)", delivered);
        } catch (RuntimeException e) {
            log.warn("Не удалось отправить ежедневную сводку о сервере", e);
        }
    }
}
