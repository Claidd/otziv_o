package com.hunt.otziv.p_products.worker_flow;

import com.hunt.otziv.business_audit.repository.BusinessAuditEventRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerTaskCompletionMonitorServiceTest {

    @Mock
    private BusinessAuditEventRepository auditEventRepository;

    @Mock
    private PersonalReminderService personalReminderService;

    @Mock
    private UserService userService;

    @Mock
    private TelegramService telegramService;

    @Test
    void plainWorkerThresholdCreatesWarningsForWorkerManagersAndOwners() {
        WorkerTaskCompletionMonitorService service = service();
        User worker = user(1L, "worker", "Иван Работник", 101L);
        User managerUser = user(2L, "manager", "Мария Менеджер", 102L);
        User ownerUser = user(3L, "owner", "Ольга Владелец", 103L);
        Manager manager = new Manager();
        manager.setUser(managerUser);
        worker.setManagers(Set.of(manager));

        when(userService.findByUserNameWithAssignments("worker")).thenReturn(Optional.of(worker));
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of(ownerUser));
        when(auditEventRepository.countByActorAndActionsSince(eq("worker"), anyCollection(), any(LocalDateTime.class)))
                .thenReturn(10L, 10L, 10L);

        service.warnIfSuspiciousCompletion(workerAuth(), "Плохие", 55L);

        verify(personalReminderService, times(3)).createSystemReminderDueNow(
                any(User.class),
                anyString(),
                anyString(),
                eq("SUSPICIOUS_TASK_COMPLETION"),
                eq(1L),
                isNull()
        );
        verify(telegramService).sendMessage(eq(101L), org.mockito.ArgumentMatchers.contains("массовое закрытие задач"));
        verify(telegramService).sendMessage(eq(102L), org.mockito.ArgumentMatchers.contains("Иван Работник"));
        verify(telegramService).sendMessage(eq(103L), org.mockito.ArgumentMatchers.contains("Иван Работник"));
    }

    @Test
    void managerRoleDoesNotTriggerWorkerWarning() {
        WorkerTaskCompletionMonitorService service = service();

        service.warnIfSuspiciousCompletion(managerAuth(), "Плохие", 55L);

        verify(auditEventRepository, never()).countByActorAndActionsSince(anyString(), anyCollection(), any());
        verify(personalReminderService, never()).createSystemReminderDueNow(
                any(),
                anyString(),
                anyString(),
                anyString(),
                any(),
                any()
        );
    }

    private WorkerTaskCompletionMonitorService service() {
        return new WorkerTaskCompletionMonitorService(
                auditEventRepository,
                personalReminderService,
                userService,
                telegramService
        );
    }

    private UsernamePasswordAuthenticationToken workerAuth() {
        return new UsernamePasswordAuthenticationToken(
                "worker",
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_WORKER"))
        );
    }

    private UsernamePasswordAuthenticationToken managerAuth() {
        return new UsernamePasswordAuthenticationToken(
                "manager",
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        );
    }

    private User user(Long id, String username, String fio, Long telegramChatId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setFio(fio);
        user.setTelegramChatId(telegramChatId);
        user.setActive(true);
        return user;
    }
}
