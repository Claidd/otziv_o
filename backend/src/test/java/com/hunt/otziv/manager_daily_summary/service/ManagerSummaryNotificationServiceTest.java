package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.AppSettingService;
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
        when(formatter.format(rows, false)).thenReturn("Итоги рабочего дня");
        when(telegramService.sendMessage(12345L, "Итоги рабочего дня", "HTML")).thenReturn(true);

        ManagerSummaryNotificationService service = new ManagerSummaryNotificationService(
                appSettingService,
                userRepository,
                telegramService,
                formatter,
                deliveryRepository
        );

        assertThat(service.send(date, rows)).isEqualTo(1);
        verify(formatter).format(rows, false);
        verify(formatter, never()).format(rows, true);
    }
}
