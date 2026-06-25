package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppAuthAlertServiceTest {

    @Mock
    private TelegramService telegramService;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PersonalReminderService personalReminderService;

    @InjectMocks
    private WhatsAppAuthAlertService service;

    @Test
    void authIssueFallsBackToAdminsWhenManagerTelegramMissing() {
        Manager manager = new Manager();
        manager.setId(7L);
        manager.setUser(new User());
        LocalDateTime now = LocalDateTime.of(2026, 5, 29, 10, 0);

        when(appSettingService.getString(eq("client.msgs.wa-auth-state.whatsapp_vika"), anyString()))
                .thenReturn("");
        when(appSettingService.getString(eq("client.msgs.wa-auth-alert.whatsapp_vika"), isNull()))
                .thenReturn(null);
        when(userRepository.findAllOwners("ROLE_ADMIN")).thenReturn(List.of(User.builder().id(1L).build()));
        service.notifyAuthIssue(
                "whatsapp_vika",
                "Компания",
                "health monitor",
                "whatsapp_not_ready",
                "authenticated=false state=qr",
                now,
                null,
                List.of(manager)
        );

        verify(telegramService, never()).sendMessage(anyLong(), anyString());
        verify(telegramService).sendAlertToAdmins(contains("WhatsApp-клиент не авторизован"));
        verify(personalReminderService).createSystemReminderDueNow(
                eq(User.builder().id(1L).build()),
                contains("whatsapp_vika"),
                contains("WhatsApp-клиент не авторизован"),
                eq("WHATSAPP_AUTH_ALERT"),
                eq(sourceId("whatsapp_vika")),
                isNull()
        );
        verify(appSettingService).setString("client.msgs.wa-auth-state.whatsapp_vika", "DOWN");
        verify(appSettingService).setString("client.msgs.wa-auth-alert.whatsapp_vika", now.toString());
    }

    @Test
    void recoveryIsSentOnceAfterDownStateAndClearsAlertCooldown() {
        Manager manager = new Manager();
        manager.setUser(User.builder().id(2L).telegramChatId(12345L).build());
        LocalDateTime now = LocalDateTime.of(2026, 5, 29, 12, 0);

        when(appSettingService.getString("client.msgs.wa-auth-state.whatsapp_lika", "UP"))
                .thenReturn("DOWN");
        when(telegramService.sendMessage(eq(12345L), contains("снова авторизован")))
                .thenReturn(true);
        when(userRepository.findAllOwners("ROLE_ADMIN")).thenReturn(List.of(User.builder().id(1L).build()));

        service.notifyRecovered(
                "whatsapp_lika",
                "health monitor",
                now,
                List.of(manager)
        );

        verify(telegramService).sendMessage(eq(12345L), contains("снова авторизован"));
        verify(telegramService).sendAlertToAdmins(contains("снова авторизован"));
        verify(personalReminderService).deleteSystemReminderBySource(
                eq(User.builder().id(1L).build()),
                eq("WHATSAPP_AUTH_ALERT"),
                eq(sourceId("whatsapp_lika"))
        );
        verify(personalReminderService).deleteSystemReminderBySource(
                eq(User.builder().id(2L).telegramChatId(12345L).build()),
                eq("WHATSAPP_AUTH_ALERT"),
                eq(sourceId("whatsapp_lika"))
        );
        verify(appSettingService).setString("client.msgs.wa-auth-state.whatsapp_lika", "UP");
        verify(appSettingService).setString("client.msgs.wa-auth-alert.whatsapp_lika", "");
        verify(appSettingService).setString("client.msgs.wa-auth-recovered.whatsapp_lika", now.toString());
    }

    @Test
    void authIssueIsSentToManagersAndAdmins() {
        Manager manager = new Manager();
        manager.setId(7L);
        manager.setUser(User.builder().id(2L).telegramChatId(12345L).build());
        LocalDateTime now = LocalDateTime.of(2026, 5, 29, 10, 0);

        when(appSettingService.getString(eq("client.msgs.wa-auth-state.whatsapp_vika"), anyString()))
                .thenReturn("");
        when(appSettingService.getString(eq("client.msgs.wa-auth-alert.whatsapp_vika"), isNull()))
                .thenReturn(null);
        when(telegramService.sendMessage(eq(12345L), contains("WhatsApp-клиент не авторизован")))
                .thenReturn(true);
        when(userRepository.findAllOwners("ROLE_ADMIN")).thenReturn(List.of(User.builder().id(1L).build()));

        service.notifyAuthIssue(
                "whatsapp_vika",
                "Компания",
                "health monitor",
                "whatsapp_not_ready",
                "authenticated=false state=qr",
                now,
                null,
                List.of(manager)
        );

        verify(telegramService).sendMessage(eq(12345L), contains("WhatsApp-клиент не авторизован"));
        verify(telegramService).sendAlertToAdmins(contains("WhatsApp-клиент не авторизован"));
        verify(personalReminderService).createSystemReminderDueNow(
                eq(User.builder().id(1L).build()),
                contains("whatsapp_vika"),
                contains("WhatsApp-клиент не авторизован"),
                eq("WHATSAPP_AUTH_ALERT"),
                eq(sourceId("whatsapp_vika")),
                isNull()
        );
        verify(personalReminderService).createSystemReminderDueNow(
                eq(User.builder().id(2L).telegramChatId(12345L).build()),
                contains("whatsapp_vika"),
                contains("WhatsApp-клиент не авторизован"),
                eq("WHATSAPP_AUTH_ALERT"),
                eq(sourceId("whatsapp_vika")),
                isNull()
        );
    }

    private Long sourceId(String clientId) {
        CRC32 crc32 = new CRC32();
        crc32.update(clientId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc32.getValue();
    }
}
