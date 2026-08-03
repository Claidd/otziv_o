package com.hunt.otziv.s3.buckupBD.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hunt.otziv.s3.buckupBD.config.BackupProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class BackupRunOnceRunnerTest {

    @Test
    void delegatesTheConfiguredStableRequestId() throws Exception {
        BackupScheduler scheduler = mock(BackupScheduler.class);
        BackupProperties properties = new BackupProperties();
        properties.getRunOnce().setEnabled(true);
        properties.getRunOnce().setRequestId("selectel-verification-20260804");
        BackupRunOnceRunner runner = new BackupRunOnceRunner(scheduler, properties);

        runner.run(mock(ApplicationArguments.class));

        verify(scheduler).runOnce("selectel-verification-20260804");
    }
}
