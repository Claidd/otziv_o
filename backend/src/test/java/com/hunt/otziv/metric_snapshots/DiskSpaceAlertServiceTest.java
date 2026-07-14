package com.hunt.otziv.metric_snapshots;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiskSpaceAlertServiceTest {

    @Mock private DiskSpaceUsageProvider usageProvider;
    @Mock private AppSettingService appSettingService;
    @Mock private PersonalReminderService personalReminderService;
    @Mock private UserService userService;
    @Mock private TelegramService telegramService;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(10L);
        owner.setActive(true);
        owner.setTelegramChatId(100L);
        lenient().when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of(owner));
        lenient().when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of());
    }

    @Test
    void warnsAtEightyPercent() {
        when(usageProvider.current()).thenReturn(new DiskSpaceUsageProvider.DiskUsage(100, 85, 15, 85));
        when(appSettingService.getString(AppSettingService.MONITORING_DISK_LAST_LEVEL, "NORMAL")).thenReturn("NORMAL");
        when(appSettingService.getString(AppSettingService.MONITORING_DISK_LAST_ALERT_AT, "")).thenReturn("");

        service().checkAndNotify();

        verify(personalReminderService).createSystemReminderDueNow(
                eq(owner), anyString(), anyString(), eq(DiskSpaceAlertService.SOURCE_TYPE), anyLong(), eq(null)
        );
        verify(telegramService).sendMessage(eq(100L), anyString());
        verify(appSettingService).setString(AppSettingService.MONITORING_DISK_LAST_LEVEL, "WARNING");
    }

    @Test
    void doesNotRepeatBeforeCooldown() {
        when(usageProvider.current()).thenReturn(new DiskSpaceUsageProvider.DiskUsage(100, 85, 15, 85));
        when(appSettingService.getString(AppSettingService.MONITORING_DISK_LAST_LEVEL, "NORMAL")).thenReturn("WARNING");
        when(appSettingService.getString(AppSettingService.MONITORING_DISK_LAST_ALERT_AT, ""))
                .thenReturn("2026-07-14T09:00:00Z");

        service().checkAndNotify();

        verify(personalReminderService, never()).createSystemReminderDueNow(
                eq(owner), anyString(), anyString(), anyString(), anyLong(), eq(null)
        );
    }

    private DiskSpaceAlertService service() {
        return new DiskSpaceAlertService(
                usageProvider,
                appSettingService,
                personalReminderService,
                userService,
                telegramService,
                Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC)
        );
    }
}
