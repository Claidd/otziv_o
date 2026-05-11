package com.hunt.otziv.analytics.service;

import com.hunt.otziv.analytics.service.AnalyticsAggregateRebuildService.AnalyticsAggregateRebuildResult;
import com.hunt.otziv.analytics.service.AnalyticsAggregateScheduledRebuildJob.AnalyticsScheduledRebuildRun;
import com.hunt.otziv.analytics.service.AnalyticsAggregateVerificationService.AnalyticsAdminMonthComparison;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsAggregateScheduledRebuildJobTest {

    @Mock
    private AnalyticsAggregateRebuildService rebuildService;

    @Mock
    private AnalyticsAggregateVerificationService verificationService;

    private AnalyticsAggregateScheduledRebuildJob job;

    @BeforeEach
    void setUp() {
        job = new AnalyticsAggregateScheduledRebuildJob(
                rebuildService,
                verificationService,
                Clock.fixed(Instant.parse("2026-05-09T00:00:00Z"), ZoneId.of("Asia/Irkutsk"))
        );
        job.setVerifyAdminMonth(true);
        job.setPreviousMonthWindowDays(7);
    }

    @Test
    void rebuildsOnlyCurrentMonthOutsidePreviousMonthWindow() {
        LocalDate today = LocalDate.of(2026, 5, 9);
        LocalDate currentMonth = LocalDate.of(2026, 5, 1);
        when(rebuildService.rebuildMonth(currentMonth, false)).thenReturn(rebuild(currentMonth, false));
        when(verificationService.compareAdminMonth(currentMonth)).thenReturn(comparison(currentMonth, true));

        AnalyticsScheduledRebuildRun run = job.rebuildRecentMonths(today);

        assertTrue(run.matches());
        assertEquals(today, run.runDate());
        assertEquals(1, run.months().size());
        assertEquals(currentMonth, run.months().getFirst().monthStart());
        assertFalse(run.months().getFirst().periodClosed());
        verify(rebuildService).rebuildMonth(currentMonth, false);
        verify(rebuildService, never()).rebuildMonth(LocalDate.of(2026, 4, 1), true);
    }

    @Test
    void rebuildsPreviousMonthInsideWindow() {
        LocalDate today = LocalDate.of(2026, 5, 3);
        LocalDate currentMonth = LocalDate.of(2026, 5, 1);
        LocalDate previousMonth = LocalDate.of(2026, 4, 1);
        when(rebuildService.rebuildMonth(currentMonth, false)).thenReturn(rebuild(currentMonth, false));
        when(rebuildService.rebuildMonth(previousMonth, true)).thenReturn(rebuild(previousMonth, true));
        when(verificationService.compareAdminMonth(currentMonth)).thenReturn(comparison(currentMonth, true));
        when(verificationService.compareAdminMonth(previousMonth)).thenReturn(comparison(previousMonth, true));

        AnalyticsScheduledRebuildRun run = job.rebuildRecentMonths(today);

        assertTrue(run.matches());
        assertEquals(2, run.months().size());
        assertEquals(currentMonth, run.months().get(0).monthStart());
        assertFalse(run.months().get(0).periodClosed());
        assertEquals(previousMonth, run.months().get(1).monthStart());
        assertTrue(run.months().get(1).periodClosed());
    }

    @Test
    void canDisablePreviousMonthWindowAndVerification() {
        LocalDate today = LocalDate.of(2026, 5, 1);
        LocalDate currentMonth = LocalDate.of(2026, 5, 1);
        job.setPreviousMonthWindowDays(0);
        job.setVerifyAdminMonth(false);
        when(rebuildService.rebuildMonth(currentMonth, false)).thenReturn(rebuild(currentMonth, false));

        AnalyticsScheduledRebuildRun run = job.rebuildRecentMonths(today);

        assertTrue(run.matches());
        assertEquals(1, run.months().size());
        assertEquals(currentMonth, run.months().getFirst().monthStart());
        verify(verificationService, never()).compareAdminMonth(currentMonth);
        verify(rebuildService, never()).rebuildMonth(LocalDate.of(2026, 4, 1), true);
    }

    @Test
    void reportsVerificationMismatchWithoutDroppingRunDetails() {
        LocalDate today = LocalDate.of(2026, 5, 9);
        LocalDate currentMonth = LocalDate.of(2026, 5, 1);
        when(rebuildService.rebuildMonth(currentMonth, false)).thenReturn(rebuild(currentMonth, false));
        when(verificationService.compareAdminMonth(currentMonth)).thenReturn(comparison(currentMonth, false));

        AnalyticsScheduledRebuildRun run = job.rebuildRecentMonths(today);

        assertFalse(run.matches());
        assertEquals(1, run.months().size());
        assertFalse(run.months().getFirst().matches());
    }

    @Test
    void skipsScheduledRunWhenPreviousRunIsStillRunning() {
        LocalDate currentMonth = LocalDate.of(2026, 5, 1);
        when(rebuildService.rebuildMonth(currentMonth, false)).thenAnswer(invocation -> {
            job.runScheduledRebuild();
            return rebuild(currentMonth, false);
        });
        when(verificationService.compareAdminMonth(currentMonth)).thenReturn(comparison(currentMonth, true));

        job.runScheduledRebuild();

        verify(rebuildService, times(1)).rebuildMonth(currentMonth, false);
        verify(verificationService, times(1)).compareAdminMonth(currentMonth);
    }

    private AnalyticsAggregateRebuildResult rebuild(LocalDate monthStart, boolean closed) {
        return new AnalyticsAggregateRebuildResult(
                monthStart,
                closed,
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                9,
                10,
                11
        );
    }

    private AnalyticsAdminMonthComparison comparison(LocalDate monthStart, boolean matches) {
        BigDecimal delta = matches ? BigDecimal.ZERO : BigDecimal.ONE;
        return new AnalyticsAdminMonthComparison(
                monthStart,
                true,
                List.of(new AnalyticsAggregateVerificationService.MetricComparison(
                        "salary_sum",
                        BigDecimal.TEN,
                        BigDecimal.TEN.subtract(delta),
                        delta
                )),
                matches
        );
    }
}
