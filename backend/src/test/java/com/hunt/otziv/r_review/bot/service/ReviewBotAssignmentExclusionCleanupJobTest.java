package com.hunt.otziv.r_review.bot.service;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReviewBotAssignmentExclusionCleanupJobTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Irkutsk");

    @Mock
    private ReviewBotAssignmentExclusionService exclusionService;

    @Test
    void deletesOnlyPublishedHistoryOlderThanConfiguredRetention() {
        ReviewBotAssignmentExclusionCleanupJob job =
                new ReviewBotAssignmentExclusionCleanupJob(exclusionService);
        ReflectionTestUtils.setField(job, "retentionDays", 7);
        LocalDateTime before = LocalDateTime.now(BUSINESS_ZONE).minusDays(7).minusSeconds(1);

        job.cleanup();

        LocalDateTime after = LocalDateTime.now(BUSINESS_ZONE).minusDays(7).plusSeconds(1);
        verify(exclusionService).clearPublishedBefore(argThat(cutoff ->
                cutoff != null && !cutoff.isBefore(before) && !cutoff.isAfter(after)));
    }
}
