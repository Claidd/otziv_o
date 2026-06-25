package com.hunt.otziv.archive.service;

import com.hunt.otziv.archive.dto.ArchiveOrdersSettingsResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderArchiveDryRunScheduledJobTest {

    @Mock
    private OrderArchiveDryRunService archiveService;

    @Test
    void runsWithinCatchUpWindowAndClaimsRunKey() {
        OrderArchiveDryRunScheduledJob job = jobAt("2026-05-10T20:16:00Z");
        when(archiveService.settings()).thenReturn(settings("04:15"));
        when(archiveService.claimScheduledArchiveRun("2026-05-11 04:15 Asia/Irkutsk")).thenReturn(true);
        when(archiveService.runScheduledConfiguredArchive()).thenReturn("ok");

        job.runScheduledDryRun();

        verify(archiveService).claimScheduledArchiveRun("2026-05-11 04:15 Asia/Irkutsk");
        verify(archiveService).runScheduledConfiguredArchive();
    }

    @Test
    void skipsWhenRunKeyIsAlreadyClaimed() {
        OrderArchiveDryRunScheduledJob job = jobAt("2026-05-10T20:16:00Z");
        when(archiveService.settings()).thenReturn(settings("04:15"));
        when(archiveService.claimScheduledArchiveRun("2026-05-11 04:15 Asia/Irkutsk")).thenReturn(false);

        job.runScheduledDryRun();

        verify(archiveService).claimScheduledArchiveRun("2026-05-11 04:15 Asia/Irkutsk");
        verify(archiveService, never()).runScheduledConfiguredArchive();
    }

    @Test
    void skipsAfterCatchUpWindowWithoutClaimingRunKey() {
        OrderArchiveDryRunScheduledJob job = jobAt("2026-05-11T00:00:00Z");
        when(archiveService.settings()).thenReturn(settings("04:15"));

        job.runScheduledDryRun();

        verify(archiveService, never()).claimScheduledArchiveRun("2026-05-11 04:15 Asia/Irkutsk");
        verify(archiveService, never()).runScheduledConfiguredArchive();
    }

    @Test
    void skipsBeforeScheduledTimeWithoutClaimingRunKey() {
        OrderArchiveDryRunScheduledJob job = jobAt("2026-05-10T20:14:00Z");
        when(archiveService.settings()).thenReturn(settings("04:15"));

        job.runScheduledDryRun();

        verify(archiveService, never()).claimScheduledArchiveRun("2026-05-11 04:15 Asia/Irkutsk");
        verify(archiveService, never()).runScheduledConfiguredArchive();
    }

    private OrderArchiveDryRunScheduledJob jobAt(String instant) {
        OrderArchiveDryRunScheduledJob job = new OrderArchiveDryRunScheduledJob(archiveService);
        job.setClock(Clock.fixed(Instant.parse(instant), ZoneId.of("UTC")));
        job.setCatchUpWindow(Duration.ofHours(3));
        return job;
    }

    private ArchiveOrdersSettingsResponse settings(String scheduleTime) {
        return new ArchiveOrdersSettingsResponse(
                90,
                90,
                500,
                5000,
                true,
                true,
                true,
                "live",
                "scheduled-orders-retention-live",
                scheduleTime,
                "0 15 4 * * *",
                "Asia/Irkutsk"
        );
    }
}
