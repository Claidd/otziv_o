package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerDailySummarySchedulerTest {

    @Mock
    private ManagerDailySummaryService summaryService;
    @Mock
    private ManagerSummaryNotificationService notificationService;
    @Mock
    private ManagerPersonalDayResultService personalDayResultService;

    @Test
    void midnightDeliveryFinalizesPreviousIrkutskDay() {
        when(summaryService.calculate(org.mockito.ArgumentMatchers.any(LocalDate.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(List.of());
        ManagerDailySummaryScheduler scheduler = new ManagerDailySummaryScheduler(
                summaryService,
                notificationService,
                personalDayResultService
        );

        scheduler.finalizeAndSend();

        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(summaryService).calculate(date.capture(), org.mockito.ArgumentMatchers.eq(true));
        assertEquals(LocalDate.now(ZoneId.of("Asia/Irkutsk")).minusDays(1), date.getValue());
        verify(notificationService).send(date.getValue(), List.<ManagerDailySummaryResponse>of());
    }
}
