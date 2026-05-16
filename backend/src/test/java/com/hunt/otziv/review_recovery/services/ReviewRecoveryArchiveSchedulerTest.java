package com.hunt.otziv.review_recovery.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewRecoveryArchiveSchedulerTest {

    @Mock
    private ReviewRecoveryTaskService reviewRecoveryTaskService;

    @Test
    void archivesClientNotifiedBatchesAfterRetentionPeriod() {
        Instant now = Instant.parse("2026-05-14T20:00:00Z");
        Instant cutoff = now.minus(ReviewRecoveryArchiveScheduler.CLIENT_NOTIFIED_RETENTION_DAYS, ChronoUnit.DAYS);
        ReviewRecoveryArchiveScheduler scheduler = new ReviewRecoveryArchiveScheduler(reviewRecoveryTaskService);
        scheduler.setClock(Clock.fixed(now, ZoneId.of("UTC")));

        when(reviewRecoveryTaskService.archiveClientNotifiedBefore(cutoff, now)).thenReturn(2);

        scheduler.archiveOldClientNotifiedBatches();

        verify(reviewRecoveryTaskService).archiveClientNotifiedBefore(cutoff, now);
    }
}
