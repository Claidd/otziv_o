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
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
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
    void twentyTwoHourDeliverySendsCurrentIrkutskDayWithoutFinalizingIt() {
        when(summaryService.calculate(org.mockito.ArgumentMatchers.any(LocalDate.class), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(List.of());
        ManagerDailySummaryScheduler scheduler = new ManagerDailySummaryScheduler(
                summaryService,
                notificationService,
                personalDayResultService
        );

        scheduler.calculateAndSendCurrentDay();

        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(summaryService).calculate(date.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertEquals(LocalDate.now(ZoneId.of("Asia/Irkutsk")), date.getValue());
        verify(notificationService).send(date.getValue(), List.<ManagerDailySummaryResponse>of());
    }

    @Test
    void midnightFinalizationClosesPreviousDayWithoutDuplicateDelivery() {
        when(summaryService.calculate(org.mockito.ArgumentMatchers.any(LocalDate.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(List.of());
        ManagerDailySummaryScheduler scheduler = new ManagerDailySummaryScheduler(
                summaryService,
                notificationService,
                personalDayResultService
        );

        scheduler.finalizePreviousDay();

        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(summaryService).calculate(date.capture(), org.mockito.ArgumentMatchers.eq(true));
        assertEquals(LocalDate.now(ZoneId.of("Asia/Irkutsk")).minusDays(1), date.getValue());
        verify(notificationService, never()).send(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
        verify(personalDayResultService, never()).send(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void personalDeliveryAlsoUsesCurrentDayAtTwentyTwoOhFive() {
        when(summaryService.calculate(org.mockito.ArgumentMatchers.any(LocalDate.class), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(List.of());
        ManagerDailySummaryScheduler scheduler = new ManagerDailySummaryScheduler(
                summaryService,
                notificationService,
                personalDayResultService
        );

        scheduler.calculateCurrentDayAndSendPersonalResults();

        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(summaryService).calculate(date.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertEquals(LocalDate.now(ZoneId.of("Asia/Irkutsk")), date.getValue());
        verify(personalDayResultService).send(date.getValue(), List.<ManagerDailySummaryResponse>of());
    }

    @Test
    void personalDeliveryDefaultCronRunsFiveMinutesAfterGroupSummary() throws Exception {
        Scheduled scheduled = ManagerDailySummaryScheduler.class
                .getDeclaredMethod("calculateCurrentDayAndSendPersonalResults")
                .getAnnotation(Scheduled.class);

        assertEquals("${manager.summary.personal-delivery-cron:0 5 22 * * *}", scheduled.cron());
    }
}
