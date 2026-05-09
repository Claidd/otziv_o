package com.hunt.otziv.analytics.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsAggregateReadServiceTest {

    private final AnalyticsAggregateReadService service = new AnalyticsAggregateReadService(null, null, null, null);

    @Test
    void splitPeriodUsesOnlyDailyRowsForCurrentMonth() {
        AnalyticsAggregateReadService.AggregatePeriod period = service.splitPeriod(
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 9),
                LocalDate.of(2026, 5, 9)
        );

        assertTrue(period.monthlyRanges().isEmpty());
        assertEquals(1, period.dailyRanges().size());
        assertEquals(
                new AnalyticsAggregateReadService.DateRange(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 9)),
                period.dailyRanges().getFirst()
        );
    }

    @Test
    void splitPeriodSeparatesClosedFullMonthsAndPartialEdges() {
        AnalyticsAggregateReadService.AggregatePeriod period = service.splitPeriod(
                LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 3, 20),
                LocalDate.of(2026, 5, 9)
        );

        assertEquals(1, period.monthlyRanges().size());
        assertEquals(
                new AnalyticsAggregateReadService.DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)),
                period.monthlyRanges().getFirst()
        );
        assertEquals(2, period.dailyRanges().size());
        assertEquals(
                new AnalyticsAggregateReadService.DateRange(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 31)),
                period.dailyRanges().get(0)
        );
        assertEquals(
                new AnalyticsAggregateReadService.DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 20)),
                period.dailyRanges().get(1)
        );
    }

    @Test
    void splitPeriodDoesNotUseCurrentMonthAsClosedMonthlyAggregate() {
        AnalyticsAggregateReadService.AggregatePeriod period = service.splitPeriod(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 3, 15)
        );

        assertEquals(1, period.monthlyRanges().size());
        assertEquals(
                new AnalyticsAggregateReadService.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)),
                period.monthlyRanges().getFirst()
        );
        assertEquals(1, period.dailyRanges().size());
        assertEquals(
                new AnalyticsAggregateReadService.DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
                period.dailyRanges().getFirst()
        );
    }

    @Test
    void scopeKeysAreStable() {
        assertEquals("OWNER:42", AnalyticsAggregateReadService.ownerScopeKey(42L));
        assertEquals("MANAGER:7", AnalyticsAggregateReadService.managerScopeKey(7L));
    }

    @Test
    void splitPeriodRejectsInvertedDates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.splitPeriod(LocalDate.of(2026, 5, 2), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 9))
        );
    }
}
