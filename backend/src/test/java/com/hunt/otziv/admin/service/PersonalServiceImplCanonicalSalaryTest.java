package com.hunt.otziv.admin.service;

import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.analytics.service.AnalyticsSalarySourceService;
import com.hunt.otziv.analytics.service.AnalyticsSalarySourceService.DailySalary;
import com.hunt.otziv.analytics.service.AnalyticsSalarySourceService.UserSalaryTotal;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.l_lead.service.LeadService;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.z_zp.service.PaymentCheckService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalServiceImplCanonicalSalaryTest {

    @Test
    void telegramSourcePreservesFractionalBonusesAndDeductions() {
        AnalyticsSalarySourceService salaries = mock(AnalyticsSalarySourceService.class);
        WorkerService workers = mock(WorkerService.class);
        PaymentCheckService payments = mock(PaymentCheckService.class);
        LeadService leads = mock(LeadService.class);
        ReviewService reviews = mock(ReviewService.class);
        OrderService orders = mock(OrderService.class);
        CompanyService companies = mock(CompanyService.class);
        PersonalServiceImpl service = new PersonalServiceImpl(
                null, null, workers, null, payments, null, leads, reviews,
                orders, companies, null, null, null, null, null, salaries, null
        );
        LocalDate today = LocalDate.now();
        LocalDate start = today.withDayOfMonth(1);
        LocalDate end = today.withDayOfMonth(today.lengthOfMonth());
        when(salaries.totalsForActiveUsers(start, end)).thenReturn(List.of(
                new UserSalaryTotal(1L, "Менеджер", "ROLE_MANAGER", new BigDecimal("10.50"), 0L, 0L),
                new UserSalaryTotal(2L, "Специалист", "ROLE_WORKER", new BigDecimal("-0.75"), 0L, 0L)
        ));
        when(payments.getAllPaymentToMonth(start, end)).thenReturn(Map.of());
        when(companies.getAllNewCompanies(start, end)).thenReturn(Map.of());
        when(orders.getNewOrderAll("Новый", "Коррекция")).thenReturn(Map.of());
        when(reviews.getAllPublishAndVigul(start, today)).thenReturn(Map.of());
        when(orders.getAllOrdersToMonthByStatus(start, end,
                "Новый", "В проверку", "На проверке", "Коррекция", "Опубликовано",
                "Выставлен счет", "Напоминание", "Не оплачено")).thenReturn(Map.of());
        when(leads.getAllLeadsToMonthToManager("Новый", start, end)).thenReturn(Map.of());
        when(workers.getAllWorkers()).thenReturn(List.of());

        Map<String, com.hunt.otziv.admin.dto.personal.UserData> result = service.getPersonalsAndCountToMap();

        assertThat(result.get("Менеджер").getSalaryKopecks()).isEqualTo(1_050L);
        assertThat(result.get("Специалист").getSalaryKopecks()).isEqualTo(-75L);
        assertThat(result.get("Менеджер").getZpTotalKopecks()).isEqualTo(975L);
        assertThat(result.get("Специалист").getZpTotalKopecks()).isEqualTo(975L);
        assertThat(service.displayResult(result))
                .contains("Вознаграждения всего: <b>9,75 руб.</b>")
                .contains("Начислено: <b>10,50 руб.</b>")
                .contains("Начислено: <b>-0,75 руб.</b>");
    }

    @Test
    void workerStatsUseCanonicalSalarySourceAndPreserveWeightedEntryCount() {
        AnalyticsSalarySourceService salarySourceService = mock(AnalyticsSalarySourceService.class);
        PersonalServiceImpl service = new PersonalServiceImpl(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, salarySourceService, null
        );
        LocalDate selectedDate = LocalDate.of(2026, 9, 2);
        User user = User.builder()
                .id(39L)
                .fio("Специалист")
                .coefficient(new BigDecimal("0.50"))
                .build();

        when(salarySourceService.dailyForUsers(
                List.of(39L),
                LocalDate.of(2025, 1, 1),
                selectedDate
        )).thenReturn(List.of(
                new DailySalary(selectedDate.minusDays(1), 39L, new BigDecimal("100.00"), 2L, 5L),
                new DailySalary(selectedDate, 39L, new BigDecimal("50.45"), 1L, 1L)
        ));

        UserStatDTO result = service.getWorkerReviews(user, selectedDate);

        assertThat(result.getSum1Day()).isEqualTo(50);
        assertThat(result.getSum1Week()).isEqualTo(150);
        assertThat(result.getSum1Month()).isEqualTo(150);
        assertThat(result.getSum1DayKopecks()).isEqualTo(5_045L);
        assertThat(result.getSum1WeekKopecks()).isEqualTo(15_045L);
        assertThat(result.getSum1MonthKopecks()).isEqualTo(15_045L);
        assertThat(result.getSumOrders1Month()).isEqualTo(3);
        verify(salarySourceService).dailyForUsers(
                List.of(39L),
                LocalDate.of(2025, 1, 1),
                selectedDate
        );
    }
}
