package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.repository.ManagerSummaryDeliveryLogRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManagerSummaryNotificationServiceTest {

    @Mock private AppSettingService appSettingService;
    @Mock private UserRepository userRepository;
    @Mock private TelegramService telegramService;
    @Mock private ManagerSummaryFormatter formatter;
    @Mock private ManagerSummaryDeliveryLogRepository deliveryRepository;

    @Test
    void realDeliveryDoesNotUseTestBanner() {
        LocalDate date = LocalDate.of(2026, 7, 14);
        List<ManagerDailySummaryResponse> rows = List.of();
        User recipient = User.builder().id(7L).telegramChatId(12345L).active(true).build();
        when(appSettingService.getBoolean("manager.summary.enabled", false)).thenReturn(true);
        when(appSettingService.getString("manager.summary.recipients", "ADMIN,OWNER")).thenReturn("ADMIN,OWNER");
        when(appSettingService.getString("manager.summary.recipient-user-ids", "")).thenReturn("");
        when(userRepository.findAllOwners("ROLE_ADMIN")).thenReturn(List.of(recipient));
        when(userRepository.findAllOwners("ROLE_OWNER")).thenReturn(List.of(recipient));
        when(deliveryRepository.findBySummaryDateAndRecipient_IdAndChannel(date, 7L, "TELEGRAM"))
                .thenReturn(Optional.empty());
        when(formatter.formatBoth(rows, false)).thenReturn(new ManagerFormattedReport(
                "Итоги рабочего дня",
                "<h2>Итоги рабочего дня</h2>"
        ));
        when(telegramService.sendRichMessage(12345L, "<h2>Итоги рабочего дня</h2>")).thenReturn(true);

        ManagerSummaryNotificationService service = new ManagerSummaryNotificationService(
                appSettingService,
                userRepository,
                telegramService,
                formatter,
                deliveryRepository
        );

        assertThat(service.send(date, rows)).isEqualTo(1);
        verify(formatter).formatBoth(rows, false);
        verify(formatter, never()).format(rows, true);
        verify(telegramService, never()).sendMessage(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void requestedTestGoesOnlyToRequesterWithoutDeliveryLog() {
        List<ManagerDailySummaryResponse> rows = List.of();
        User requester = User.builder().id(7L).telegramChatId(12345L).active(true).build();
        String message = "а".repeat(3890) + "\n\n" + "б".repeat(100);
        String richMessage = "<h2>Аудит</h2><p>" + "а".repeat(5000) + "</p>";
        when(formatter.formatBoth(rows, true)).thenReturn(new ManagerFormattedReport(message, richMessage));
        when(telegramService.sendRichMessage(12345L, richMessage)).thenReturn(true);

        ManagerSummaryNotificationService service = new ManagerSummaryNotificationService(
                appSettingService,
                userRepository,
                telegramService,
                formatter,
                deliveryRepository
        );

        assertThat(service.sendTest(requester, rows)).isEqualTo(1);
        verify(formatter).formatBoth(rows, true);
        verify(telegramService).sendRichMessage(12345L, richMessage);
        verify(telegramService, never()).sendMessage(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
        verify(deliveryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
