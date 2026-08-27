package com.hunt.otziv.manager_daily_summary.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerDailySummaryScheduler {

    private static final ZoneId SUMMARY_ZONE = ZoneId.of("Asia/Irkutsk");

    private final ManagerDailySummaryService summaryService;
    private final ManagerSummaryNotificationService notificationService;
    private final ManagerPersonalDayResultService personalDayResultService;
    private final ReentrantLock calculationLock = new ReentrantLock();

    @Scheduled(cron = "${manager.summary.snapshot-cron:0 55 21 * * *}", zone = "${manager.summary.zone:Asia/Irkutsk}")
    public void calculateSnapshot() {
        try {
            withCalculationLock(() -> {
                summaryService.calculate(LocalDate.now(SUMMARY_ZONE), false);
                return null;
            });
        } catch (RuntimeException exception) {
            log.error("Manager daily summary snapshot failed", exception);
        }
    }

    @Scheduled(cron = "${manager.summary.delivery-cron:0 0 22 * * *}", zone = "${manager.summary.zone:Asia/Irkutsk}")
    public void calculateAndSendCurrentDay() {
        try {
            LocalDate date = LocalDate.now(SUMMARY_ZONE);
            var summaries = withCalculationLock(() -> summaryService.calculate(date, false));
            int sent = notificationService.send(date, summaries);
            log.info("Manager daily summary sent at 22:00: date={}, managers={}, recipients={}",
                    date, summaries.size(), sent);
        } catch (RuntimeException exception) {
            log.error("Manager daily summary delivery failed", exception);
        }
    }

    @Scheduled(
            cron = "${manager.summary.personal-delivery-cron:0 5 22 * * *}",
            zone = "${manager.summary.zone:Asia/Irkutsk}"
    )
    public void calculateCurrentDayAndSendPersonalResults() {
        LocalDate date = LocalDate.now(SUMMARY_ZONE);
        try {
            var summaries = withCalculationLock(() -> summaryService.calculate(date, false));
            int sent = personalDayResultService.send(date, summaries);
            log.info("Manager personal day results sent: date={}, managers={}, recipients={}",
                    date, summaries.size(), sent);
        } catch (RuntimeException exception) {
            log.error("Manager personal day result delivery failed for {}", date, exception);
        }
    }

    @Scheduled(
            cron = "${manager.summary.finalize-cron:0 0 0 * * *}",
            zone = "${manager.summary.zone:Asia/Irkutsk}"
    )
    public void finalizePreviousDay() {
        LocalDate date = LocalDate.now(SUMMARY_ZONE).minusDays(1);
        try {
            var summaries = withCalculationLock(() -> summaryService.calculate(date, true));
            log.info("Manager daily summary finalized without repeat delivery: date={}, managers={}",
                    date, summaries.size());
        } catch (RuntimeException exception) {
            log.error("Manager daily summary finalization failed for {}", date, exception);
        }
    }

    private <T> T withCalculationLock(Supplier<T> action) {
        calculationLock.lock();
        try {
            return action.get();
        } finally {
            calculationLock.unlock();
        }
    }
}
