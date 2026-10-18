package com.hunt.otziv.analytics.service;

import com.hunt.otziv.admin.dto.personal.UserData;
import com.hunt.otziv.analytics.model.AnalyticsDailyUser;
import com.hunt.otziv.analytics.model.AnalyticsMonthlyUser;
import com.hunt.otziv.analytics.service.AnalyticsSalarySourceService.SalaryTotal;
import com.hunt.otziv.analytics.service.AnalyticsFinancialSourceService.PaymentTotal;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AnalyticsAggregateScoreServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 9);
    private static final LocalDate MONTH_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate MONTH_END = LocalDate.of(2026, 5, 31);

    @Mock
    private AnalyticsAggregateReadService readService;

    @Mock
    private AnalyticsSalarySourceService salarySourceService;

    @Mock
    private AnalyticsFinancialSourceService financialSourceService;

    private AnalyticsAggregateScoreService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsAggregateScoreService(readService, salarySourceService, financialSourceService);
    }

    @Test
    void buildsScoreRowsFromMonthlyUserAggregates() {
        AnalyticsMonthlyUser manager = monthlyUser(
                user(10L, "Manager One", 55L),
                "ROLE_MANAGER",
                "100.00",
                "500.00",
                3,
                7,
                2,
                9,
                4
        );
        AnalyticsMonthlyUser operator = monthlyUser(
                user(20L, "Operator One", null),
                "ROLE_OPERATOR",
                "10.00",
                "0.00",
                1,
                1,
                0,
                5,
                2
        );
        AnalyticsMonthlyUser owner = monthlyUser(
                user(30L, "Owner One", null),
                "ROLE_OWNER",
                "1000.00",
                "0.00",
                1,
                1,
                0,
                0,
                0
        );
        when(readService.monthlyUsers(MONTH_START, MONTH_START)).thenReturn(List.of(manager, operator, owner));
        when(salarySourceService.totalsForUsers(anyCollection(), eq(MONTH_START), eq(DATE)))
                .thenReturn(Map.of(
                        10L, new SalaryTotal(new BigDecimal("90.00"), 4, 8),
                        20L, new SalaryTotal(new BigDecimal("20.00"), 2, 2)
                ));
        when(financialSourceService.paymentTotalsForUsers(anyCollection(), eq(MONTH_START), eq(DATE)))
                .thenReturn(Map.of(10L, new PaymentTotal(new BigDecimal("500.00"), 1)));

        Optional<List<UserData>> result = service.buildScore(DATE);

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());

        UserData managerScore = result.get().getFirst();
        assertEquals("Manager One", managerScore.getFio());
        assertEquals("ROLE_MANAGER", managerScore.getRole());
        assertEquals(90L, managerScore.getSalary());
        assertEquals(500L, managerScore.getTotalSum());
        assertEquals(110L, managerScore.getZpTotal());
        assertEquals(2L, managerScore.getNewCompanies());
        assertEquals(4L, managerScore.getOrder1Month());
        assertEquals(8L, managerScore.getReview1Month());
        assertEquals(0L, managerScore.getLeadsNew());
        assertEquals(55L, managerScore.getImageId());
        assertEquals(10L, managerScore.getUserId());

        UserData operatorScore = result.get().get(1);
        assertEquals("Operator One", operatorScore.getFio());
        assertEquals(5L, operatorScore.getLeadsNew());
        assertEquals(2L, operatorScore.getLeadsInWork());
        assertEquals(40L, operatorScore.getPercentInWork());
        assertEquals(1L, operatorScore.getImageId());
    }

    @Test
    void fallsBackToDailyUserAggregatesWhenMonthlyRowsAreMissing() {
        User worker = user(11L, "Worker One", null);
        AnalyticsDailyUser firstDay = dailyUser(worker, "ROLE_WORKER", "25.00", 1, 2);
        AnalyticsDailyUser secondDay = dailyUser(worker, "ROLE_WORKER", "75.00", 2, 3);

        when(readService.monthlyUsers(MONTH_START, MONTH_START)).thenReturn(List.of());
        when(readService.dailyUsers(MONTH_START, MONTH_END)).thenReturn(List.of(firstDay, secondDay));
        when(salarySourceService.totalsForUsers(anyCollection(), eq(MONTH_START), eq(DATE)))
                .thenReturn(Map.of(11L, new SalaryTotal(new BigDecimal("100.00"), 3, 5)));

        Optional<List<UserData>> result = service.buildScore(DATE);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
        assertEquals(100L, result.get().getFirst().getSalary());
        assertEquals(3L, result.get().getFirst().getOrder1Month());
        assertEquals(5L, result.get().getFirst().getReview1Month());
    }

    @Test
    void preservesBonusAndDeductionKopecksWithoutChangingWorkCounts() {
        AnalyticsMonthlyUser manager = monthlyUser(user(10L, "Manager", null), "ROLE_MANAGER",
                "10.50", "0.00", 3, 7, 0, 0, 0);
        AnalyticsMonthlyUser worker = monthlyUser(user(11L, "Worker", null), "ROLE_WORKER",
                "-0.75", "0.00", 2, 4, 0, 0, 0);
        when(readService.monthlyUsers(MONTH_START, MONTH_START)).thenReturn(List.of(manager, worker));
        when(salarySourceService.totalsForUsers(anyCollection(), eq(MONTH_START), eq(DATE)))
                .thenReturn(Map.of(
                        10L, new SalaryTotal(new BigDecimal("10.50"), 3L, 7L),
                        11L, new SalaryTotal(new BigDecimal("-0.75"), 2L, 4L)
                ));

        List<UserData> score = service.buildScore(DATE).orElseThrow();

        assertEquals(1_050L, score.get(0).getSalaryKopecks());
        assertEquals(-75L, score.get(1).getSalaryKopecks());
        assertEquals(975L, score.get(0).getZpTotalKopecks());
        assertEquals(975L, score.get(1).getZpTotalKopecks());
        assertEquals(3L, score.get(0).getOrder1Month());
        assertEquals(7L, score.get(0).getReview1Month());
        assertEquals(2L, score.get(1).getOrder1Month());
        assertEquals(4L, score.get(1).getReview1Month());
    }

    @Test
    void excludesInactiveStaffFromScoreAndSalaryTotal() {
        User active = user(10L, "Current Worker", null);
        User inactive = user(11L, "Former Worker", null);
        inactive.setActive(false);
        when(readService.monthlyUsers(MONTH_START, MONTH_START)).thenReturn(List.of(
                monthlyUser(active, "ROLE_WORKER", "100.00", "0.00", 1, 1, 0, 0, 0),
                monthlyUser(inactive, "ROLE_WORKER", "500.00", "0.00", 5, 5, 0, 0, 0)
        ));
        when(salarySourceService.totalsForUsers(eq(java.util.Set.of(10L)), eq(MONTH_START), eq(DATE)))
                .thenReturn(Map.of(10L, new SalaryTotal(new BigDecimal("100.00"), 1, 1)));

        List<UserData> score = service.buildScore(DATE).orElseThrow();

        assertEquals(1, score.size());
        assertEquals(10L, score.getFirst().getUserId());
        assertEquals(100L, score.getFirst().getZpTotal());
        verify(financialSourceService).paymentTotalsForUsers(java.util.Set.of(10L), MONTH_START, DATE);
    }

    private AnalyticsMonthlyUser monthlyUser(
            User user,
            String role,
            String salary,
            String payment,
            long salaryEntries,
            long salaryReviews,
            long newCompanies,
            long leadsNew,
            long leadsInWork
    ) {
        AnalyticsMonthlyUser row = new AnalyticsMonthlyUser();
        row.setMonthStart(MONTH_START);
        row.setUser(user);
        row.setRoleName(role);
        row.setSalarySum(new BigDecimal(salary));
        row.setPaymentSum(new BigDecimal(payment));
        row.setSalaryEntryCount(salaryEntries);
        row.setSalaryReviewCount(salaryReviews);
        row.setNewCompaniesCount(newCompanies);
        row.setLeadsNewCount(leadsNew);
        row.setLeadsInWorkCount(leadsInWork);
        return row;
    }

    private AnalyticsDailyUser dailyUser(User user, String role, String salary, long salaryEntries, long salaryReviews) {
        AnalyticsDailyUser row = new AnalyticsDailyUser();
        row.setMetricDate(MONTH_START);
        row.setUser(user);
        row.setRoleName(role);
        row.setSalarySum(new BigDecimal(salary));
        row.setSalaryEntryCount(salaryEntries);
        row.setSalaryReviewCount(salaryReviews);
        return row;
    }

    private User user(long id, String fio, Long imageId) {
        User user = User.builder()
                .id(id)
                .fio(fio)
                .active(true)
                .build();
        if (imageId != null) {
            Image image = new Image();
            image.setId(imageId);
            user.setImage(image);
        }
        return user;
    }
}
