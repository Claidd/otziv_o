package com.hunt.otziv.s3.buckupBD.service;

import com.hunt.otziv.scheduler.SchedulerLeaseService;
import com.hunt.otziv.scheduler.SchedulerLeaseService.Lease;
import com.hunt.otziv.s3.buckupBD.config.BackupProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private BackupProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BackupProperties();
        scheduler = new BackupScheduler(backupService, schedulerLeaseService, properties);
        scheduler.validateConfiguration();
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

    @Test
    void registersConfiguredDailyScheduleAndRequiredCatchUpByDefault() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduler.configureTasks(registrar);

        assertThat(registrar.getTriggerTaskList()).hasSize(1);
        assertThat(registrar.getFixedDelayTaskList()).hasSize(1);
    }

    @Test
    void disabledScheduleRegistersNoRecurringTasks() {
        properties.getSchedule().setEnabled(false);
        properties.getSchedule().setCatchUpEnabled(false);
        scheduler.validateConfiguration();
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduler.configureTasks(registrar);

        assertThat(registrar.getTriggerTaskList()).isEmpty();
        assertThat(registrar.getFixedDelayTaskList()).isEmpty();
    }

    @Test
    void locatesOnlyARecentlyMissedOccurrenceInConfiguredZone() {
        CronExpression cron = CronExpression.parse("0 0 7 * * *");
        ZoneId zone = ZoneId.of("Asia/Irkutsk");

        assertThat(BackupScheduler.mostRecentScheduledOccurrence(
                cron,
                zone,
                Instant.parse("2026-08-03T00:30:00Z"),
                Duration.ofHours(2)
        )).contains(Instant.parse("2026-08-02T23:00:00Z"));

        assertThat(BackupScheduler.mostRecentScheduledOccurrence(
                cron,
                zone,
                Instant.parse("2026-08-03T06:00:00Z"),
                Duration.ofHours(2)
        )).isEmpty();
    }

    @Test
    void rejectsUnboundedCatchUpAndRunOnceWithRecurringSchedule() {
        properties.getSchedule().setCatchUpEnabled(true);
        properties.getSchedule().setCatchUpWindow(Duration.ofHours(37));

        assertThatThrownBy(scheduler::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catch-up-window");

        properties.getSchedule().setCatchUpWindow(Duration.ofHours(26));
        properties.getRunOnce().setEnabled(true);
        properties.getRunOnce().setRequestId("selectel-verification-1");

        assertThatThrownBy(scheduler::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schedule.enabled=false");
    }

    @Test
    void acceptsCatchUpWindowCoveringThePreviousDailyOccurrence() {
        properties.getSchedule().setCatchUpEnabled(true);
        properties.getSchedule().setCatchUpWindow(Duration.ofHours(26));

        assertThatCode(scheduler::validateConfiguration).doesNotThrowAnyException();
    }

    @Test
    void rejectsDisabledOrTooShortCatchUpForRecurringBackup() {
        properties.getSchedule().setCatchUpEnabled(false);
        assertThatThrownBy(scheduler::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catch-up-enabled must be true");

        properties.getSchedule().setCatchUpEnabled(true);
        properties.getSchedule().setCatchUpWindow(Duration.ofHours(24));
        assertThatThrownBy(scheduler::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("greater than PT24H");
    }

    @Test
    void acceptsExactlyOneConfiguredTimeEveryCalendarDay() {
        properties.getSchedule().setCron("0 30 4 * * *");

        assertThatCode(scheduler::validateConfiguration).doesNotThrowAnyException();
    }

    @Test
    void rejectsSchedulesThatSkipCalendarDays() {
        for (String cron : List.of(
                "0 30 4 1 * *",
                "0 30 4 * 13 *",
                "0 30 4 * * MON-FRI"
        )) {
            properties.getSchedule().setCron(cron);

            assertThatThrownBy(scheduler::validateConfiguration)
                    .as("cron %s", cron)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be '*'");
        }
    }

    @Test
    void rejectsCronExpressionsThatCanRunMoreThanOncePerCalendarDay() {
        for (String cron : List.of(
                "*/5 * * * * *",
                "0 */15 * * * *",
                "0 0 */2 * * *",
                "0 0 7,19 * * *"
        )) {
            properties.getSchedule().setCron(cron);

            assertThatThrownBy(scheduler::validateConfiguration)
                    .as("cron %s", cron)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be one numeric value");
        }
    }

    @Test
    void rejectsOutOfRangeDailyCronTimeFields() {
        for (String cron : List.of(
                "60 0 7 * * *",
                "0 60 7 * * *",
                "0 0 24 * * *"
        )) {
            properties.getSchedule().setCron(cron);

            assertThatThrownBy(scheduler::validateConfiguration)
                    .as("cron %s", cron)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be between");
        }
    }

    @Test
    void manualRunIsIdempotentFromVerifiedEvidence() throws Exception {
        properties.getSchedule().setEnabled(false);
        properties.getSchedule().setCatchUpEnabled(false);
        scheduler.validateConfiguration();
        when(backupService.readEvidenceSummary()).thenReturn(
                new DatabaseBackupService.BackupEvidenceSummary(
                        Optional.of(Instant.parse("2026-08-03T00:00:00Z")),
                        java.util.Set.of("selectel-verification-1")
                )
        );

        scheduler.runOnce("selectel-verification-1");

        verify(schedulerLeaseService, never()).tryAcquire(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(backupService, never()).runManualBackup(org.mockito.ArgumentMatchers.anyString());
    }
}
