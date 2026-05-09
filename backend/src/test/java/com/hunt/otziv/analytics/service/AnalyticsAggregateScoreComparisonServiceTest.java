package com.hunt.otziv.analytics.service;

import com.hunt.otziv.admin.dto.presonal.UserData;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateScoreComparisonService.AnalyticsScoreComparison;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsAggregateScoreComparisonServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 9);

    @Mock
    private PersonalService personalService;

    @Mock
    private AnalyticsAggregateScoreService aggregateScoreService;

    private AnalyticsAggregateScoreComparisonService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsAggregateScoreComparisonService(personalService, aggregateScoreService);
    }

    @Test
    void reportsOnlyCoveredFieldMismatches() {
        UserData legacyManager = scoreUser("Manager One", "ROLE_MANAGER", 100L, 500L, 1000L, 2L, 3L, 7L, 0L, 0L);
        UserData aggregateManager = scoreUser("Manager One", "ROLE_MANAGER", 125L, 500L, 1000L, 2L, 3L, 7L, 0L, 0L);
        UserData legacyWorker = scoreUser("Worker One", "ROLE_WORKER", 50L, 0L, 1000L, 0L, 1L, 2L, 0L, 0L);
        UserData aggregateOperator = scoreUser("Operator One", "ROLE_OPERATOR", 10L, 0L, 1000L, 0L, 1L, 1L, 5L, 2L);

        when(personalService.getPersonalsAndCountToScore(DATE)).thenReturn(List.of(legacyManager, legacyWorker));
        when(aggregateScoreService.buildScore(DATE)).thenReturn(Optional.of(List.of(aggregateManager, aggregateOperator)));

        AnalyticsScoreComparison comparison = service.compare(DATE);

        assertTrue(comparison.aggregateAvailable());
        assertFalse(comparison.matches());
        assertEquals(2, comparison.legacyUserCount());
        assertEquals(2, comparison.aggregateUserCount());
        assertEquals(1, comparison.comparedUserCount());
        assertEquals(3, comparison.mismatchCount());
        assertEquals(List.of("newOrders", "correctOrders", "inVigul", "inPublish", "imageId", "userId"), comparison.skippedFields());
        assertTrue(comparison.mismatches().stream()
                .anyMatch(field -> "ROLE_MANAGER|Manager One".equals(field.userKey())
                        && "salary".equals(field.field())
                        && "25".equals(field.delta())));
        assertTrue(comparison.mismatches().stream()
                .anyMatch(field -> "ROLE_WORKER|Worker One".equals(field.userKey())
                        && "row".equals(field.field())
                        && "missing".equals(field.aggregateValue())));
        assertTrue(comparison.mismatches().stream()
                .anyMatch(field -> "ROLE_OPERATOR|Operator One".equals(field.userKey())
                        && "row".equals(field.field())
                        && "missing".equals(field.legacyValue())));
    }

    @Test
    void reportsUnavailableAggregateScore() {
        when(personalService.getPersonalsAndCountToScore(DATE)).thenReturn(List.of(
                scoreUser("Manager One", "ROLE_MANAGER", 100L, 500L, 1000L, 2L, 3L, 7L, 0L, 0L)
        ));
        when(aggregateScoreService.buildScore(DATE)).thenReturn(Optional.empty());

        AnalyticsScoreComparison comparison = service.compare(DATE);

        assertFalse(comparison.aggregateAvailable());
        assertFalse(comparison.matches());
        assertEquals(1, comparison.legacyUserCount());
        assertEquals(0, comparison.aggregateUserCount());
        assertEquals(0, comparison.mismatchCount());
        assertTrue(comparison.mismatches().isEmpty());
    }

    private UserData scoreUser(
            String fio,
            String role,
            Long salary,
            Long totalSum,
            Long zpTotal,
            Long newCompanies,
            Long order1Month,
            Long review1Month,
            Long leadsNew,
            Long leadsInWork
    ) {
        Long percentInWork = leadsNew == null || leadsNew == 0 ? 0L : (leadsInWork * 100) / leadsNew;
        return UserData.builder()
                .fio(fio)
                .role(role)
                .salary(salary)
                .totalSum(totalSum)
                .zpTotal(zpTotal)
                .newCompanies(newCompanies)
                .order1Month(order1Month)
                .review1Month(review1Month)
                .leadsNew(leadsNew)
                .leadsInWork(leadsInWork)
                .percentInWork(percentInWork)
                .build();
    }
}
