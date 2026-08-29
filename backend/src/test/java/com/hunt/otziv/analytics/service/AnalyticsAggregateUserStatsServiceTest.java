package com.hunt.otziv.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.analytics.service.AnalyticsSalarySourceService.DailySalary;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsAggregateUserStatsServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 9);

    @Mock
    private AnalyticsSalarySourceService salarySourceService;

    private AnalyticsAggregateUserStatsService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AnalyticsAggregateUserStatsService(salarySourceService, objectMapper);
    }

    @Test
    void buildsUserStatsFromCanonicalFinalAttribution() throws Exception {
        User user = user(10L, "Worker One", "1.25", 77L);
        stubRows(List.of(
                daily(LocalDate.of(2025, 1, 1), "300.00", 3),
                daily(LocalDate.of(2025, 5, 1), "100.00", 1),
                daily(LocalDate.of(2026, 1, 1), "500.00", 5),
                daily(LocalDate.of(2026, 4, 1), "200.00", 2),
                daily(LocalDate.of(2026, 5, 1), "50.00", 1),
                daily(LocalDate.of(2026, 5, 7), "25.00", 1),
                daily(LocalDate.of(2026, 5, 8), "100.00", 2),
                daily(DATE, "40.00", 1)
        ));

        UserStatDTO stats = service.buildUserStats(DATE, user).orElseThrow();

        assertEquals(10L, stats.getId());
        assertEquals("Worker One", stats.getFio());
        assertEquals(77L, stats.getImageId());
        assertEquals(new BigDecimal("1.25"), stats.getCoefficient());
        assertEquals(40, stats.getSum1Day());
        assertEquals(165, stats.getSum1Week());
        assertEquals(215, stats.getSum1Month());
        assertEquals(915, stats.getSum1Year());
        assertEquals(5, stats.getSumOrders1Month());
        assertEquals(2, stats.getSumOrders2Month());
        assertEquals(-60, stats.getPercent1Day());
        assertEquals(7, stats.getPercent1Month());

        JsonNode dailyMap = objectMapper.readTree(stats.getZpPayMap());
        assertEquals(31, dailyMap.size());
        assertEquals(100, dailyMap.get("8").asInt());
        assertEquals(40, dailyMap.get("9").asInt());

        JsonNode monthlyMap = objectMapper.readTree(stats.getZpPayMapMonth());
        assertEquals(100, monthlyMap.get("2025").get("5").asInt());
        assertTrue(monthlyMap.get("2025").get("2") == null);
        assertEquals(215, monthlyMap.get("2026").get("5").asInt());
    }

    @Test
    void usesCorrectedPriorDayInsteadOfStaleAggregateSnapshot() throws Exception {
        User user = user(10L, "Worker One", "1.25", 77L);
        stubRows(List.of(
                daily(DATE.minusDays(1), "100.00", 2),
                daily(DATE, "40.00", 3)
        ));

        UserStatDTO stats = service.buildUserStats(DATE, user).orElseThrow();

        assertEquals(140, stats.getSum1Month());
        assertEquals(5, stats.getSumOrders1Month());
        JsonNode dailyMap = objectMapper.readTree(stats.getZpPayMap());
        assertEquals(40, dailyMap.get("9").asInt());
        JsonNode monthlyMap = objectMapper.readTree(stats.getZpPayMapMonth());
        assertEquals(140, monthlyMap.get("2026").get("5").asInt());
    }

    private void stubRows(List<DailySalary> rows) {
        when(salarySourceService.dailyForUsers(anyCollection(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(rows);
    }

    private DailySalary daily(LocalDate metricDate, String salary, long salaryEntries) {
        return new DailySalary(metricDate, 10L, new BigDecimal(salary), salaryEntries, salaryEntries);
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
