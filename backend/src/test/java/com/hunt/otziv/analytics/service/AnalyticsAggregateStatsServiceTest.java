package com.hunt.otziv.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.analytics.model.AnalyticsDailyTotal;
import com.hunt.otziv.analytics.model.AnalyticsMonthlyTotal;
import com.hunt.otziv.analytics.repository.AnalyticsDailyTotalRepository;
import com.hunt.otziv.analytics.repository.AnalyticsMonthlyTotalRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsAggregateStatsServiceTest {

    private static final String SCOPE_KEY = AnalyticsAggregateReadService.SCOPE_ADMIN_ALL;
    private static final LocalDate SELECTED_DATE = LocalDate.of(2026, 5, 9);

    @Mock
    private AnalyticsDailyTotalRepository dailyTotalRepository;

    @Mock
    private AnalyticsMonthlyTotalRepository monthlyTotalRepository;

    private AnalyticsAggregateStatsService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AnalyticsAggregateReadService readService = new AnalyticsAggregateReadService(
                dailyTotalRepository,
                null,
                null,
                monthlyTotalRepository
        );
        service = new AnalyticsAggregateStatsService(readService, objectMapper);
    }

    @Test
    void buildsAdminStatsFromMonthlyAndDailyTotals() throws Exception {
        List<AnalyticsDailyTotal> dailyRows = List.of(
                daily(LocalDate.of(2026, 5, 1), "200.00", "80.00", 2, 4, 1, 1),
                daily(LocalDate.of(2026, 5, 7), "50.00", "20.00", 1, 1, 0, 0),
                daily(LocalDate.of(2026, 5, 8), "100.00", "40.00", 1, 1, 0, 0)
        );
        List<AnalyticsMonthlyTotal> monthlyRows = List.of(
                monthly(LocalDate.of(2026, 1, 1), "300.00", "150.00", 3, 6, 0, 0),
                monthly(LocalDate.of(2026, 2, 1), "0.00", "0.00", 5, 10, 0, 0),
                monthly(LocalDate.of(2026, 4, 1), "1000.00", "500.00", 10, 20, 2, 1)
        );
        stubDailyRows(dailyRows);
        stubMonthlyRows(monthlyRows);

        User admin = User.builder().id(1L).build();
        Optional<StatDTO> result = service.buildStats(SELECTED_DATE, admin, "ROLE_ADMIN");

        assertTrue(result.isPresent());
        StatDTO stats = result.get();
        assertEquals(100, stats.getSum1DayPay());
        assertEquals(150, stats.getSum1WeekPay());
        assertEquals(350, stats.getSum1MonthPay());
        assertEquals(1650, stats.getSum1YearPay());
        assertEquals(4, stats.getSumOrders1MonthPay());
        assertEquals(10, stats.getSumOrders2MonthPay());
        assertEquals(-65, stats.getPercent1MonthPay());
        assertEquals(-60, stats.getPercent1MonthOrdersPay());
        assertEquals(1, stats.getNewLeads());
        assertEquals(1, stats.getLeadsInWork());

        JsonNode dailyPaymentMap = objectMapper.readTree(stats.getOrderPayMap());
        assertEquals(31, dailyPaymentMap.size());
        assertEquals(100, dailyPaymentMap.get("8").asInt());
        assertEquals(0, dailyPaymentMap.get("9").asInt());

        JsonNode monthlyPaymentMap = objectMapper.readTree(stats.getOrderPayMapMonth());
        assertEquals(1000, monthlyPaymentMap.get("2026").get("4").asInt());
        assertEquals(350, monthlyPaymentMap.get("2026").get("5").asInt());
    }

    @Test
    void returnsEmptyForUnsupportedRole() {
        User worker = User.builder().id(7L).build();

        assertTrue(service.buildStats(SELECTED_DATE, worker, "ROLE_WORKER").isEmpty());
    }

    private void stubDailyRows(List<AnalyticsDailyTotal> rows) {
        when(dailyTotalRepository.findByScopeKeyInPeriod(eq(SCOPE_KEY), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    LocalDate from = invocation.getArgument(1);
                    LocalDate to = invocation.getArgument(2);
                    return rows.stream()
                            .filter(row -> !row.getMetricDate().isBefore(from) && !row.getMetricDate().isAfter(to))
                            .toList();
                });
    }

    private void stubMonthlyRows(List<AnalyticsMonthlyTotal> rows) {
        when(monthlyTotalRepository.findByScopeKeyInMonthPeriod(eq(SCOPE_KEY), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    LocalDate from = invocation.getArgument(1);
                    LocalDate to = invocation.getArgument(2);
                    return rows.stream()
                            .filter(row -> !row.getMonthStart().isBefore(from) && !row.getMonthStart().isAfter(to))
                            .toList();
                });
    }

    private AnalyticsDailyTotal daily(
            LocalDate metricDate,
            String paymentSum,
            String salarySum,
            long paymentCount,
            long salaryCount,
            long leadsNew,
            long leadsInWork
    ) {
        AnalyticsDailyTotal total = new AnalyticsDailyTotal();
        total.setMetricDate(metricDate);
        total.setScopeKey(SCOPE_KEY);
        total.setPaymentSum(new BigDecimal(paymentSum));
        total.setSalarySum(new BigDecimal(salarySum));
        total.setPaymentCount(paymentCount);
        total.setSalaryEntryCount(salaryCount);
        total.setLeadsNewCount(leadsNew);
        total.setLeadsInWorkCount(leadsInWork);
        return total;
    }

    private AnalyticsMonthlyTotal monthly(
            LocalDate monthStart,
            String paymentSum,
            String salarySum,
            long paymentCount,
            long salaryCount,
            long leadsNew,
            long leadsInWork
    ) {
        AnalyticsMonthlyTotal total = new AnalyticsMonthlyTotal();
        total.setMonthStart(monthStart);
        total.setScopeKey(SCOPE_KEY);
        total.setPaymentSum(new BigDecimal(paymentSum));
        total.setSalarySum(new BigDecimal(salarySum));
        total.setPaymentCount(paymentCount);
        total.setSalaryEntryCount(salaryCount);
        total.setLeadsNewCount(leadsNew);
        total.setLeadsInWorkCount(leadsInWork);
        return total;
    }
}
