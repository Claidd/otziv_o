package com.hunt.otziv.manager_daily_summary.service;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerDailySummaryScheduler {

    private final ManagerDailySummaryService summaryService;
    private final ManagerSummaryNotificationService notificationService;

    @Scheduled(cron = "${manager.summary.snapshot-cron:0 55 22 * * *}", zone = "${manager.summary.zone:Asia/Irkutsk}")
    public void calculateSnapshot() {
        try {
            summaryService.calculate(LocalDate.now(), false);
        } catch (RuntimeException exception) {
            log.error("Manager daily summary snapshot failed", exception);
        }
    }

    @Scheduled(cron = "${manager.summary.delivery-cron:0 0 23 * * *}", zone = "${manager.summary.zone:Asia/Irkutsk}")
    public void finalizeAndSend() {
        try {
            LocalDate date = LocalDate.now();
            var summaries = summaryService.calculate(date, true);
            int sent = notificationService.send(date, summaries);
            log.info("Manager daily summary finalized: date={}, managers={}, recipients={}", date, summaries.size(), sent);
        } catch (RuntimeException exception) {
            log.error("Manager daily summary delivery failed", exception);
        }
    }
}
