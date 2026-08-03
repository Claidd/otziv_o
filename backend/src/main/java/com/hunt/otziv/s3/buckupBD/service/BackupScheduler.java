package com.hunt.otziv.s3.buckupBD.service;

import com.hunt.otziv.scheduler.SchedulerLeaseService;
import com.hunt.otziv.scheduler.SchedulerLeaseService.Lease;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "backup.enabled", havingValue = "true")
public class BackupScheduler {

    private static final String DAILY_BACKUP_LEASE = "database-backup.daily";
    private static final Duration DAILY_BACKUP_LEASE_DURATION = Duration.ofHours(1);

    private final DatabaseBackupService backupService;
    private final SchedulerLeaseService schedulerLeaseService;

    // каждый день в 07:00 по времени сервера (или контейнера)
    @Scheduled(cron = "0 00 7 * * *")
    public void daily() {
        Optional<Lease> acquired = schedulerLeaseService.tryAcquire(
                DAILY_BACKUP_LEASE,
                DAILY_BACKUP_LEASE_DURATION
        );
        if (acquired.isEmpty()) {
            log.info("Daily database backup skipped because another replica owns the scheduler lease");
            return;
        }

        try {
            backupService.runDailyBackup();
        } catch (Exception e) {
            log.error("❌ Daily backup failed", e);
        } finally {
            schedulerLeaseService.release(acquired.get());
        }
    }
}
