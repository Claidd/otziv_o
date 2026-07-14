package com.hunt.otziv.manager.services;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyPublicationDatePermissionNotificationServiceTest {

    @Mock private UserService userService;
    @Mock private TelegramService telegramService;
    @Mock private ManagerPermissionService managerPermissionService;
    @Mock private Authentication authentication;

    @InjectMocks
    private CompanyPublicationDatePermissionNotificationService service;

    @Test
    void managerActivationNotifiesOwnersAndAdminsWithCompany() {
        User manager = user(10L, "Менеджер М.", null);
        User owner = user(20L, "Владелец", 200L);
        User admin = user(30L, "Администратор", 300L);
        when(managerPermissionService.hasRole(authentication, "MANAGER")).thenReturn(true);
        when(authentication.getName()).thenReturn("manager");
        when(userService.findByUserName("manager")).thenReturn(Optional.of(manager));
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of(owner));
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(admin));

        service.notifyEnabledByManager(42L, "Компания Тест", authentication);

        verify(telegramService).sendMessage(eq(200L), contains("Компания: Компания Тест (#42)"));
        verify(telegramService).sendMessage(eq(300L), contains("Менеджер: Менеджер М. (manager)"));
    }

    @Test
    void nonManagerActivationDoesNotNotify() {
        when(managerPermissionService.hasRole(authentication, "MANAGER")).thenReturn(false);

        service.notifyEnabledByManager(42L, "Компания Тест", authentication);

        verify(telegramService, never()).sendMessage(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    private User user(Long id, String fio, Long telegramChatId) {
        User user = new User();
        user.setId(id);
        user.setFio(fio);
        user.setTelegramChatId(telegramChatId);
        user.setActive(true);
        return user;
    }
}
