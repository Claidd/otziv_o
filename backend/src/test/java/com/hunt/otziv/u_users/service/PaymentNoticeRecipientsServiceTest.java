package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentNoticeRecipientsServiceTest {
    final UserService users=mock(UserService.class);final UserRepository repository=mock(UserRepository.class);final ManagerRepository managers=mock(ManagerRepository.class);
    final PaymentNoticeRecipientsService service=new PaymentNoticeRecipientsService(users,repository,managers);
    User user(long id,Long chat) {var user=new User();user.setId(id);user.setActive(true);user.setTelegramChatId(chat);return user;}
    @Test void selectsAdminAndResponsibleManagerAndKeepsMissingChatPending() {
        var admin=user(1,9191L);var employee=user(2,null);var unrelated=user(3,9393L);
        when(users.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(admin));
        var manager=new Manager();manager.setId(7L);manager.setUser(employee);when(managers.findById(7L)).thenReturn(Optional.of(manager));
        var result=service.forManagers(List.of(7L,7L));
        assertEquals(2,result.size());assertEquals(9191L,result.getFirst().chatId());assertNull(result.get(1).chatId());
        assertEquals(7L,result.get(1).managerId());assertTrue(result.stream().noneMatch(r -> r.userId()==unrelated.getId()));
        manager.setAuditTelegramGroupChatId(-500L);
        assertEquals(-500L,service.forManagers(List.of(7L)).get(1).chatId());
    }
    @Test void theSameAdminAndManagerReceivesOnlyOneNotice() {
        var admin=user(1,9191L);when(users.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(admin));
        var manager=new Manager();manager.setUser(admin);manager.setAuditTelegramGroupChatId(-500L);when(managers.findById(7L)).thenReturn(Optional.of(manager));
        var result=service.forManagers(List.of(7L));assertEquals(1,result.size());assertEquals(9191L,result.getFirst().chatId());
    }
    @Test void revokedOrInactiveRecipientCannotBeResolvedForSending() {
        var user=user(1,9191L);when(repository.findById(1L)).thenReturn(Optional.of(user));user.setRoles(List.of());
        assertNull(service.currentChat(1,null));
        var role=new Role();role.setName("ROLE_ADMIN");user.setRoles(List.of(role));assertEquals(9191L,service.currentChat(1,null));
        user.setActive(false);assertNull(service.currentChat(1,null));
    }
}
