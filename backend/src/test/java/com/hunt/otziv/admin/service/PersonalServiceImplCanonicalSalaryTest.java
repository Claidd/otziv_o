package com.hunt.otziv.admin.service;

import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.analytics.service.AnalyticsSalarySourceService;
import com.hunt.otziv.analytics.service.AnalyticsSalarySourceService.DailySalary;
import com.hunt.otziv.u_users.model.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalServiceImplCanonicalSalaryTest {

    @Test
    void workerStatsUseCanonicalSalarySourceAndPreserveWeightedEntryCount() {
        AnalyticsSalarySourceService salarySourceService = mock(AnalyticsSalarySourceService.class);
        PersonalServiceImpl service = new PersonalServiceImpl(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, salarySourceService
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
                new DailySalary(selectedDate, 39L, new BigDecimal("50.00"), 1L, 1L)
        ));

        UserStatDTO result = service.getWorkerReviews(user, selectedDate);

        assertThat(result.getSum1Day()).isEqualTo(50);
        assertThat(result.getSum1Week()).isEqualTo(150);
        assertThat(result.getSum1Month()).isEqualTo(150);
        assertThat(result.getSumOrders1Month()).isEqualTo(3);
        verify(salarySourceService).dailyForUsers(
                List.of(39L),
                LocalDate.of(2025, 1, 1),
                selectedDate
        );
    }
}
