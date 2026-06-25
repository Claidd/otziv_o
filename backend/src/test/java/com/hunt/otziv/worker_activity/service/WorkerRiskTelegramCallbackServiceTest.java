package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentLevel;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerRiskTelegramCallbackServiceTest {

    @Mock
    private WorkerRiskIncidentRepository incidentRepository;

    @Mock
    private GamificationScoreLedgerRepository scoreLedgerRepository;

    @Mock
    private UserService userService;

    @Mock
    private PersonalReminderService personalReminderService;

    @Mock
    private TelegramService telegramService;

    private WorkerRiskTelegramCallbackService service;

    @BeforeEach
    void setUp() {
        service = new WorkerRiskTelegramCallbackService(
                incidentRepository,
                scoreLedgerRepository,
                userService,
                personalReminderService,
                telegramService
        );
    }

    @Test
    void explanationCallbackFromGroupUsesClickingUserTelegramId() {
        WorkerRiskIncident incident = incident();
        User admin = user(1L, "admin", 777L, "ROLE_ADMIN");
        User worker = user(2L, "worker", 888L, "ROLE_WORKER");

        when(userService.findByChatId(777L)).thenReturn(Optional.of(admin));
        when(incidentRepository.findById(77L)).thenReturn(Optional.of(incident));
        when(userService.findByUserName("worker")).thenReturn(Optional.of(worker));
        when(personalReminderService.hasOpenSystemReminder(worker, "WORKER_RISK_MANAGER_WARNING", 77L))
                .thenReturn(false);
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<String> answer = service.handle(callbackFromGroup(-100123L, 777L, "worker-risk:77:e"));

        assertEquals(Optional.of("Разъяснение запрошено"), answer);
        verify(userService).findByChatId(777L);
        verify(personalReminderService).createSystemReminderDueNow(
                eq(worker),
                eq("Нужно пояснение по действию"),
                any(),
                eq("WORKER_RISK_MANAGER_WARNING"),
                eq(77L),
                eq(100L)
        );
        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(888L),
                any(),
                eq(null),
                any()
        );

        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(captor.capture());
        assertEquals(WorkerRiskIncidentStatus.OPEN, captor.getValue().getStatus());
        assertEquals(WorkerRiskResolutionAction.EXPLANATION_REQUESTED, captor.getValue().getResolutionAction());
    }

    @Test
    void workerTextMessageStoresExplanationAndNotifiesManager() {
        WorkerRiskIncident incident = incident();
        incident.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
        incident.setExplanationPromptedAt(java.time.LocalDateTime.now());
        User managerUser = user(3L, "manager", 999L, "ROLE_MANAGER");
        User worker = user(2L, "worker", 888L, "ROLE_WORKER");
        Manager manager = new Manager();
        manager.setId(10L);
        manager.setUser(managerUser);
        worker.setManagers(Set.of(manager));

        when(incidentRepository.findFirstByWorkerUserIdAndStatusAndResolutionActionAndWorkerExplanationAtIsNullAndExplanationPromptedAtIsNotNullOrderByExplanationPromptedAtDescCreatedAtDesc(
                2L,
                WorkerRiskIncidentStatus.OPEN,
                WorkerRiskResolutionAction.EXPLANATION_REQUESTED
        )).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of());
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of());
        when(personalReminderService.hasOpenSystemReminder(managerUser, "WORKER_RISK_WORKER_EXPLANATION", 77L))
                .thenReturn(false);

        boolean handled = service.handleWorkerTextMessage(888L, worker, "Аккаунт был заблокирован, поэтому деактивировала.");

        assertEquals(true, handled);
        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(captor.capture());
        assertEquals("Аккаунт был заблокирован, поэтому деактивировала.", captor.getValue().getWorkerExplanation());
        assertEquals(2L, captor.getValue().getWorkerExplanationByUserId());
        verify(personalReminderService).createSystemReminderDueNow(
                eq(managerUser),
                eq("Получено пояснение специалиста"),
                any(),
                eq("WORKER_RISK_WORKER_EXPLANATION"),
                eq(77L),
                eq(100L)
        );
        verify(telegramService).sendMessage(eq(888L), any());
        verify(telegramService).sendMessage(eq(999L), any());
    }

    private CallbackQuery callbackFromGroup(long groupChatId, long actorTelegramId, String data) {
        Chat chat = new Chat();
        chat.setId(groupChatId);
        chat.setType("supergroup");

        Message message = new Message();
        message.setChat(chat);

        org.telegram.telegrambots.meta.api.objects.User from = new org.telegram.telegrambots.meta.api.objects.User();
        from.setId(actorTelegramId);

        CallbackQuery callbackQuery = new CallbackQuery();
        callbackQuery.setMessage(message);
        callbackQuery.setFrom(from);
        callbackQuery.setData(data);
        return callbackQuery;
    }

    private WorkerRiskIncident incident() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(77L);
        incident.setStatus(WorkerRiskIncidentStatus.OPEN);
        incident.setLevel(WorkerRiskIncidentLevel.MANAGER_REVIEW);
        incident.setRuleCode("ACCOUNT_DEACTIVATION_WITHOUT_CREDENTIAL_COPY");
        incident.setScore(35);
        incident.setWorkerUserId(2L);
        incident.setWorkerUsername("worker");
        incident.setWorkerName("Иван Работник");
        incident.setAction("REVIEW_BOT_DEACTIVATE");
        incident.setEntityType("review");
        incident.setEntityId(501L);
        incident.setOrderId(100L);
        incident.setReviewId(501L);
        incident.setTitle("Деактивация аккаунта без копирования данных");
        incident.setMessage("Проверить деактивацию");
        return incident;
    }

    private User user(Long id, String username, Long telegramChatId, String roleName) {
        Role role = new Role();
        role.setName(roleName);

        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setFio(username);
        user.setActive(true);
        user.setTelegramChatId(telegramChatId);
        user.setRoles(List.of(role));
        return user;
    }
}
