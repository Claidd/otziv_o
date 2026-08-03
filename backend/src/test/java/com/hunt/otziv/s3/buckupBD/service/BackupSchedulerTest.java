package com.hunt.otziv.s3.buckupBD.service;

import com.hunt.otziv.scheduler.SchedulerLeaseService;
import com.hunt.otziv.scheduler.SchedulerLeaseService.Lease;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackupSchedulerTest {

    @Mock
    private DatabaseBackupService backupService;
    @Mock
    private SchedulerLeaseService schedulerLeaseService;

    private BackupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BackupScheduler(backupService, schedulerLeaseService);
    }

    @Test
    void skipsBackupWhenAnotherReplicaOwnsTheLease() throws Exception {
        when(schedulerLeaseService.tryAcquire("database-backup.daily", Duration.ofHours(1)))
                .thenReturn(Optional.empty());

        scheduler.daily();

        verify(backupService, never()).runDailyBackup();
        verify(schedulerLeaseService, never()).release(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void releasesLeaseAfterSuccessfulBackup() throws Exception {
        Lease lease = new Lease("database-backup.daily", "owner", 1L);
        when(schedulerLeaseService.tryAcquire("database-backup.daily", Duration.ofHours(1)))
                .thenReturn(Optional.of(lease));

        scheduler.daily();

        verify(backupService).runDailyBackup();
        verify(schedulerLeaseService).release(lease);
    }

    @Test
    void releasesLeaseAfterBackupFailure() throws Exception {
        Lease lease = new Lease("database-backup.daily", "owner", 2L);
        when(schedulerLeaseService.tryAcquire("database-backup.daily", Duration.ofHours(1)))
                .thenReturn(Optional.of(lease));
        org.mockito.Mockito.doThrow(new IllegalStateException("failed"))
                .when(backupService).runDailyBackup();

        scheduler.daily();

        verify(schedulerLeaseService).release(lease);
    }
}
