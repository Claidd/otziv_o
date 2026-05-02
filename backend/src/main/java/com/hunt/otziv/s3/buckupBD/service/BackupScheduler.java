package com.hunt.otziv.s3.buckupBD.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BackupScheduler {

    private final DatabaseBackupService backupService;

    // каждый день в 07:00 по времени сервера (или контейнера)
    @Scheduled(cron = "0 00 7 * * *")
    public void daily() {
        try {
            backupService.runDailyBackup();
        } catch (Exception e) {
            log.error("❌ Daily backup failed", e);
        }
    }
}

