package com.hunt.otziv.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateUserStatsComparisonService.AnalyticsUserStatsComparison;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsAggregateUserStatsComparisonServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 9);

    @Mock
    private PersonalService personalService;

    @Mock
    private UserService userService;

    @Mock
    private AnalyticsAggregateUserStatsService aggregateUserStatsService;

    private AnalyticsAggregateUserStatsComparisonService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AnalyticsAggregateUserStatsComparisonService(
                personalService,
                userService,
                aggregateUserStatsService,
                new ObjectMapper()
        );
        user = User.builder()
                .id(10L)
                .username("worker")
                .fio("Worker One")
                .build();
    }

    @Test
    void comparesCoveredWorkerCabinetFields() {
        UserStatDTO legacy = userStats(100, "{\"1\":100}", "1.25");
        UserStatDTO aggregate = userStats(125, "{\"1\":100.0}", "1.2500");
        when(userService.findByIdToUserInfo(10L)).thenReturn(user);
        when(personalService.getWorkerReviews(user, DATE)).thenReturn(legacy);
        when(aggregateUserStatsService.buildUserStats(DATE, user)).thenReturn(Optional.of(aggregate));

        AnalyticsUserStatsComparison comparison = service.compare(10L, DATE);

        assertTrue(comparison.aggregateAvailable());
        assertFalse(comparison.matches());
        assertEquals("worker", comparison.username());
        assertEquals(18, comparison.comparedFields().size());
        assertTrue(comparison.skippedFields().contains("reviewsPayYear"));
        assertTrue(comparison.fields().stream()
                .filter(field -> "zpPayMap".equals(field.field()))
                .findFirst()
                .orElseThrow()
                .matches());
        assertTrue(comparison.fields().stream()
                .anyMatch(field -> "sum1Month".equals(field.field()) && "25".equals(field.delta())));
        assertTrue(comparison.fields().stream()
                .filter(field -> "coefficient".equals(field.field()))
                .findFirst()
                .orElseThrow()
                .matches());
    }

    @Test
    void reportsMissingAggregateWithoutComparingFields() {
        UserStatDTO legacy = userStats(100, "{\"1\":100}", "1.25");
        when(userService.findByIdToUserInfo(10L)).thenReturn(user);
        when(personalService.getWorkerReviews(user, DATE)).thenReturn(legacy);
        when(aggregateUserStatsService.buildUserStats(DATE, user)).thenReturn(Optional.empty());

        AnalyticsUserStatsComparison comparison = service.compare(10L, DATE);

        assertFalse(comparison.aggregateAvailable());
        assertFalse(comparison.matches());
        assertEquals(10L, comparison.userId());
        assertTrue(comparison.fields().isEmpty());
    }

    private UserStatDTO userStats(int monthSalary, String dailySalaryMap, String coefficient) {
        UserStatDTO stats = new UserStatDTO();
        stats.setId(10L);
        stats.setFio("Worker One");
        stats.setImageId(1L);
        stats.setCoefficient(new BigDecimal(coefficient));
        stats.setZpPayMap(dailySalaryMap);
        stats.setZpPayMapMonth("{}");
        stats.setSum1Day(10);
        stats.setSum1Week(50);
        stats.setSum1Month(monthSalary);
        stats.setSum1Year(500);
        stats.setSumOrders1Month(4);
        stats.setSumOrders2Month(2);
        stats.setPercent1Day(100);
        stats.setPercent1Week(50);
        stats.setPercent1Month(25);
        stats.setPercent1Year(10);
        stats.setPercent1MonthOrders(50);
        stats.setPercent2MonthOrders(0);
        return stats;
    }
}
