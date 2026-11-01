package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecialistPaymentReviewDirectoryServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final ManagerRepository managers = mock(ManagerRepository.class);
    private final SpecialistPaymentReviewDirectoryService service = new SpecialistPaymentReviewDirectoryService(users, managers);

    @Test
    void groupDeliveryDoesNotRequirePrivateBotChat() {
        User user = new User(); user.setId(7L); user.setActive(true);
        user.setWorkerTelegramGroupChatId(-10077L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        assertEquals(Optional.of(-10077L), service.activePaymentReviewGroupChatId(7L, "SPECIALIST"));
        for (Long group : new Long[]{null, 0L, 777L}) {
            user.setWorkerTelegramGroupChatId(group); user.setTelegramChatId(777L);
            assertTrue(service.activePaymentReviewGroupChatId(7L, "SPECIALIST").isEmpty());
        }
        user.setWorkerTelegramGroupChatId(-10077L); user.setActive(false);
        assertTrue(service.activePaymentReviewGroupChatId(7L, "SPECIALIST").isEmpty());
    }

    @Test
    void managerUsesTheirConfiguredSharedGroupAndNeverPrivateFallback() {
        User user = new User(); user.setId(7L); user.setActive(true); user.setTelegramChatId(777L);
        user.setWorkerTelegramGroupChatId(-10077L);
        Manager manager = new Manager(); manager.setAuditTelegramGroupChatId(-10088L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(managers.findByUserId(7L)).thenReturn(Optional.of(manager));
        assertEquals(Optional.of(-10088L), service.activePaymentReviewGroupChatId(7L, "MANAGER"));
        manager.setAuditTelegramGroupChatId(null);
        assertTrue(service.activePaymentReviewGroupChatId(7L, "MANAGER").isEmpty());
        assertTrue(service.activePaymentReviewGroupChatId(null, "SPECIALIST").isEmpty());
    }
}
