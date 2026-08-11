package com.hunt.otziv.s3.backup.service;

import com.hunt.otziv.s3.backup.config.BackupProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "backup.run-once.enabled", havingValue = "true")
public class BackupRunOnceRunner implements ApplicationRunner {

    private final BackupScheduler backupScheduler;
    private final BackupProperties backupProperties;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        BackupProperties.RunOnce runOnce = backupProperties.getRunOnce();
        if (runOnce == null) {
            throw new IllegalStateException("backup.run-once is required");
        }
        backupScheduler.runOnce(runOnce.getRequestId());
    }
}
