package com.hunt.otziv.analytics.service;

import com.hunt.otziv.admin.dto.presonal.UserData;
import com.hunt.otziv.analytics.model.AnalyticsDailyUser;
import com.hunt.otziv.analytics.model.AnalyticsMonthlyUser;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsAggregateScoreServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 9);
    private static final LocalDate MONTH_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate MONTH_END = LocalDate.of(2026, 5, 31);

    @Mock
    private AnalyticsAggregateReadService readService;

    private AnalyticsAggregateScoreService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsAggregateScoreService(readService);
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

        Optional<List<UserData>> result = service.buildScore(DATE);

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());

        UserData managerScore = result.get().getFirst();
        assertEquals("Manager One", managerScore.getFio());
        assertEquals("ROLE_MANAGER", managerScore.getRole());
        assertEquals(100L, managerScore.getSalary());
        assertEquals(500L, managerScore.getTotalSum());
        assertEquals(1110L, managerScore.getZpTotal());
        assertEquals(2L, managerScore.getNewCompanies());
        assertEquals(3L, managerScore.getOrder1Month());
        assertEquals(7L, managerScore.getReview1Month());
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

        Optional<List<UserData>> result = service.buildScore(DATE);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
        assertEquals(100L, result.get().getFirst().getSalary());
        assertEquals(3L, result.get().getFirst().getOrder1Month());
        assertEquals(5L, result.get().getFirst().getReview1Month());
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
                .build();
        if (imageId != null) {
            Image image = new Image();
            image.setId(imageId);
            user.setImage(image);
        }
        return user;
    }
}
