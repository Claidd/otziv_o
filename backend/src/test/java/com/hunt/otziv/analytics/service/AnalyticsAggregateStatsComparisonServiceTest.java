package com.hunt.otziv.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateStatsComparisonService.AnalyticsStatsComparison;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
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
class AnalyticsAggregateStatsComparisonServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 9);

    @Mock
    private PersonalService personalService;

    @Mock
    private UserService userService;

    @Mock
    private AnalyticsAggregateStatsService aggregateStatsService;

    private AnalyticsAggregateStatsComparisonService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AnalyticsAggregateStatsComparisonService(
                personalService,
                userService,
                aggregateStatsService,
                new ObjectMapper()
        );

        Role role = new Role();
        role.setName("ROLE_ADMIN");
        user = User.builder()
                .id(1L)
                .username("admin")
                .roles(List.of(role))
                .build();
    }

    @Test
    void comparesLegacyAndAggregateStats() {
        StatDTO legacy = stats(100, "{\"1\":100}");
        StatDTO aggregate = stats(125, "{\"1\":100}");
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user));
        when(personalService.getStats(DATE, user, "ROLE_ADMIN")).thenReturn(legacy);
        when(aggregateStatsService.buildStats(DATE, user, "ROLE_ADMIN", LocalDate.of(2025, 1, 1), DATE))
                .thenReturn(Optional.of(aggregate));

        AnalyticsStatsComparison comparison = service.compare("admin", DATE, null);

        assertTrue(comparison.aggregateAvailable());
        assertFalse(comparison.matches());
        assertEquals("ROLE_ADMIN", comparison.role());
        assertEquals("25", comparison.fields().stream()
                .filter(field -> "sum1MonthPay".equals(field.field()))
                .findFirst()
                .orElseThrow()
                .delta());
        assertTrue(comparison.fields().stream()
                .filter(field -> "orderPayMap".equals(field.field()))
                .findFirst()
                .orElseThrow()
                .matches());
    }

    @Test
    void reportsMissingAggregateWithoutComparingFields() {
        StatDTO legacy = stats(100, "{\"1\":100}");
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user));
        when(personalService.getStats(DATE, user, "ROLE_ADMIN")).thenReturn(legacy);
        when(aggregateStatsService.buildStats(DATE, user, "ROLE_ADMIN", LocalDate.of(2025, 1, 1), DATE))
                .thenReturn(Optional.empty());

        AnalyticsStatsComparison comparison = service.compare("admin", DATE, "ADMIN");

        assertFalse(comparison.aggregateAvailable());
        assertFalse(comparison.matches());
        assertEquals("ROLE_ADMIN", comparison.role());
        assertTrue(comparison.fields().isEmpty());
    }

    private StatDTO stats(int monthPayment, String dailyPaymentMap) {
        StatDTO stats = new StatDTO();
        stats.setOrderPayMap(dailyPaymentMap);
        stats.setOrderPayMapMonth("{}");
        stats.setZpPayMap("{}");
        stats.setZpPayMapMonth("{}");
        stats.setSum1MonthPay(monthPayment);
        return stats;
    }
}
