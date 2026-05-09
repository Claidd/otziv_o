package com.hunt.otziv.analytics.service;

import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.OperatorsListDTO;
import com.hunt.otziv.analytics.model.AnalyticsDailyUser;
import com.hunt.otziv.analytics.model.AnalyticsMonthlyUser;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamService.AggregateTeam;
import com.hunt.otziv.u_users.model.Image;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsAggregateTeamServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 9);
    private static final LocalDate MONTH_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate MONTH_END = LocalDate.of(2026, 5, 31);

    @Mock
    private AnalyticsAggregateReadService readService;

    private AnalyticsAggregateTeamService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsAggregateTeamService(readService);
    }

    @Test
    void buildsTeamFromMonthlyAggregatesAndPartialDailyPayments() {
        User managerUser = user(10L, "manager", "Manager One", 55L);
        User operatorUser = user(20L, "operator", "Operator One", null);
        Manager manager = Manager.builder().id(100L).user(managerUser).build();
        Operator operator = Operator.builder().id(200L).user(operatorUser).build();

        AnalyticsMonthlyUser managerMonthly = monthlyUser(managerUser, "100.00", "999.00", 3, 7, 0, 0);
        AnalyticsMonthlyUser operatorMonthly = monthlyUser(operatorUser, "20.00", "0.00", 1, 2, 5, 2);
        AnalyticsDailyUser managerPartialPayment = dailyUser(managerUser, "250.00", "0.00", 0, 0, 0, 0);

        when(readService.monthlyUsers(anyCollection(), eq(MONTH_START), eq(MONTH_START)))
                .thenReturn(List.of(managerMonthly, operatorMonthly));
        when(readService.dailyUsers(anyCollection(), eq(MONTH_START), eq(DATE)))
                .thenReturn(List.of(managerPartialPayment));

        Optional<AggregateTeam> result = service.buildTeam(
                DATE,
                List.of(manager),
                List.of(),
                List.of(),
                List.of(operator)
        );

        assertTrue(result.isPresent());
        ManagersListDTO managerDto = result.get().managers().getFirst();
        assertEquals(100L, managerDto.getId());
        assertEquals(10L, managerDto.getUserId());
        assertEquals("Manager One", managerDto.getFio());
        assertEquals("manager", managerDto.getLogin());
        assertEquals(55L, managerDto.getImageId());
        assertEquals(100, managerDto.getSum1Month());
        assertEquals(3, managerDto.getOrder1Month());
        assertEquals(7, managerDto.getReview1Month());
        assertEquals(250, managerDto.getPayment1Month());

        OperatorsListDTO operatorDto = result.get().operators().getFirst();
        assertEquals(20, operatorDto.getSum1Month());
        assertEquals(1, operatorDto.getOrder1Month());
        assertEquals(2, operatorDto.getReview1Month());
        assertEquals(5L, operatorDto.getLeadsNew());
        assertEquals(2L, operatorDto.getLeadsInWork());
        assertEquals(40L, operatorDto.getPercentInWork());
        assertEquals(1L, operatorDto.getImageId());
    }

    @Test
    void fallsBackToDailyRowsWhenMonthlyRowsAreMissing() {
        User managerUser = user(10L, "manager", "Manager One", null);
        Manager manager = Manager.builder().id(100L).user(managerUser).build();
        AnalyticsDailyUser firstDay = dailyUser(managerUser, "10.00", "30.00", 1, 2, 0, 0);
        AnalyticsDailyUser secondDay = dailyUser(managerUser, "15.00", "70.00", 2, 3, 0, 0);

        when(readService.monthlyUsers(anyCollection(), eq(MONTH_START), eq(MONTH_START)))
                .thenReturn(List.of());
        when(readService.dailyUsers(anyCollection(), eq(MONTH_START), eq(MONTH_END)))
                .thenReturn(List.of(firstDay, secondDay));
        when(readService.dailyUsers(anyCollection(), eq(MONTH_START), eq(DATE)))
                .thenReturn(List.of(firstDay));

        Optional<AggregateTeam> result = service.buildTeam(
                DATE,
                List.of(manager),
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(result.isPresent());
        ManagersListDTO managerDto = result.get().managers().getFirst();
        assertEquals(100, managerDto.getSum1Month());
        assertEquals(3, managerDto.getOrder1Month());
        assertEquals(5, managerDto.getReview1Month());
        assertEquals(10, managerDto.getPayment1Month());
    }

    private AnalyticsMonthlyUser monthlyUser(
            User user,
            String salary,
            String payment,
            long salaryEntries,
            long salaryReviews,
            long leadsNew,
            long leadsInWork
    ) {
        AnalyticsMonthlyUser row = new AnalyticsMonthlyUser();
        row.setMonthStart(MONTH_START);
        row.setUser(user);
        row.setRoleName("ROLE_TEST");
        row.setSalarySum(new BigDecimal(salary));
        row.setPaymentSum(new BigDecimal(payment));
        row.setSalaryEntryCount(salaryEntries);
        row.setSalaryReviewCount(salaryReviews);
        row.setLeadsNewCount(leadsNew);
        row.setLeadsInWorkCount(leadsInWork);
        return row;
    }

    private AnalyticsDailyUser dailyUser(
            User user,
            String payment,
            String salary,
            long salaryEntries,
            long salaryReviews,
            long leadsNew,
            long leadsInWork
    ) {
        AnalyticsDailyUser row = new AnalyticsDailyUser();
        row.setMetricDate(MONTH_START);
        row.setUser(user);
        row.setRoleName("ROLE_TEST");
        row.setPaymentSum(new BigDecimal(payment));
        row.setSalarySum(new BigDecimal(salary));
        row.setSalaryEntryCount(salaryEntries);
        row.setSalaryReviewCount(salaryReviews);
        row.setLeadsNewCount(leadsNew);
        row.setLeadsInWorkCount(leadsInWork);
        return row;
    }

    private User user(Long id, String login, String fio, Long imageId) {
        User user = User.builder()
                .id(id)
                .username(login)
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
