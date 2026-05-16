package com.hunt.otziv.review_recovery.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewRecoveryArchiveScheduler {

    static final int CLIENT_NOTIFIED_RETENTION_DAYS = 180;

    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private Clock clock = Clock.systemDefaultZone();

    @Scheduled(
            cron = "${otziv.review-recovery.archive.cron:0 20 4 * * *}",
            zone = "${otziv.review-recovery.archive.zone:Asia/Irkutsk}"
    )
    public void archiveOldClientNotifiedBatches() {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(CLIENT_NOTIFIED_RETENTION_DAYS, ChronoUnit.DAYS);
        int archived = reviewRecoveryTaskService.archiveClientNotifiedBefore(cutoff, now);
        if (archived > 0) {
            log.info("Review recovery archive completed: archived={} cutoff={}", archived, cutoff);
        }
    }

    void setClock(Clock clock) {
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }
}
