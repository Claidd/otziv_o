package com.hunt.otziv.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.analytics.model.AnalyticsDailyUser;
import com.hunt.otziv.analytics.model.AnalyticsMonthlyUser;
import com.hunt.otziv.analytics.repository.AnalyticsDailyUserRepository;
import com.hunt.otziv.analytics.repository.AnalyticsMonthlyUserRepository;
import com.hunt.otziv.u_users.model.Image;
import com.hunt.otziv.u_users.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsAggregateUserStatsServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 9);

    @Mock
    private AnalyticsDailyUserRepository dailyUserRepository;

    @Mock
    private AnalyticsMonthlyUserRepository monthlyUserRepository;

    private AnalyticsAggregateUserStatsService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AnalyticsAggregateReadService readService = new AnalyticsAggregateReadService(
                null,
                dailyUserRepository,
                monthlyUserRepository,
                null
        );
        service = new AnalyticsAggregateUserStatsService(readService, objectMapper);
    }

    @Test
    void buildsUserStatsFromMonthlyAndDailyAggregates() throws Exception {
        User user = user(10L, "Worker One", "1.25", 77L);
        List<AnalyticsMonthlyUser> monthlyRows = List.of(
                monthly(user, LocalDate.of(2025, 1, 1), "300.00", 3),
                monthly(user, LocalDate.of(2025, 2, 1), "0.00", 0),
                monthly(user, LocalDate.of(2025, 5, 1), "100.00", 1),
                monthly(user, LocalDate.of(2026, 1, 1), "500.00", 5),
                monthly(user, LocalDate.of(2026, 4, 1), "200.00", 2)
        );
        List<AnalyticsDailyUser> dailyRows = List.of(
                daily(user, LocalDate.of(2026, 5, 1), "50.00", 1),
                daily(user, LocalDate.of(2026, 5, 7), "25.00", 1),
                daily(user, LocalDate.of(2026, 5, 8), "100.00", 2)
        );
        stubMonthlyRows(monthlyRows);
        stubDailyRows(dailyRows);

        Optional<UserStatDTO> result = service.buildUserStats(DATE, user);

        assertTrue(result.isPresent());
        UserStatDTO stats = result.get();
        assertEquals(10L, stats.getId());
        assertEquals("Worker One", stats.getFio());
        assertEquals(77L, stats.getImageId());
        assertEquals(new BigDecimal("1.25"), stats.getCoefficient());
        assertEquals(100, stats.getSum1Day());
        assertEquals(125, stats.getSum1Week());
        assertEquals(175, stats.getSum1Month());
        assertEquals(875, stats.getSum1Year());
        assertEquals(4, stats.getSumOrders1Month());
        assertEquals(2, stats.getSumOrders2Month());
        assertEquals(75, stats.getPercent1Day());
        assertEquals(-13, stats.getPercent1Month());

        JsonNode dailyMap = objectMapper.readTree(stats.getZpPayMap());
        assertEquals(31, dailyMap.size());
        assertEquals(100, dailyMap.get("8").asInt());
        assertEquals(0, dailyMap.get("9").asInt());

        JsonNode monthlyMap = objectMapper.readTree(stats.getZpPayMapMonth());
        assertEquals(100, monthlyMap.get("2025").get("5").asInt());
        assertTrue(monthlyMap.get("2025").get("2") == null);
        assertEquals(175, monthlyMap.get("2026").get("5").asInt());
    }

    private void stubDailyRows(List<AnalyticsDailyUser> rows) {
        when(dailyUserRepository.findByUserIdsInPeriod(anyCollection(), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    LocalDate from = invocation.getArgument(1);
                    LocalDate to = invocation.getArgument(2);
                    return rows.stream()
                            .filter(row -> !row.getMetricDate().isBefore(from) && !row.getMetricDate().isAfter(to))
                            .toList();
                });
    }

    private void stubMonthlyRows(List<AnalyticsMonthlyUser> rows) {
        when(monthlyUserRepository.findByUserIdsInMonthPeriod(anyCollection(), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    LocalDate from = invocation.getArgument(1);
                    LocalDate to = invocation.getArgument(2);
                    return rows.stream()
                            .filter(row -> !row.getMonthStart().isBefore(from) && !row.getMonthStart().isAfter(to))
                            .toList();
                });
    }

    private AnalyticsMonthlyUser monthly(User user, LocalDate monthStart, String salary, long salaryEntries) {
        AnalyticsMonthlyUser row = new AnalyticsMonthlyUser();
        row.setMonthStart(monthStart);
        row.setUser(user);
        row.setRoleName("ROLE_WORKER");
        row.setSalarySum(new BigDecimal(salary));
        row.setSalaryEntryCount(salaryEntries);
        return row;
    }

    private AnalyticsDailyUser daily(User user, LocalDate metricDate, String salary, long salaryEntries) {
        AnalyticsDailyUser row = new AnalyticsDailyUser();
        row.setMetricDate(metricDate);
        row.setUser(user);
        row.setRoleName("ROLE_WORKER");
        row.setSalarySum(new BigDecimal(salary));
        row.setSalaryEntryCount(salaryEntries);
        return row;
    }

    private User user(Long id, String fio, String coefficient, Long imageId) {
        User user = User.builder()
                .id(id)
                .fio(fio)
                .coefficient(new BigDecimal(coefficient))
                .build();
        Image image = new Image();
        image.setId(imageId);
        user.setImage(image);
        return user;
    }
}
