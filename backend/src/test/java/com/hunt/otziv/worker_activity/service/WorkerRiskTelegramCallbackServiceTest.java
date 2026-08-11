package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_activity.service.WorkerRiskEvaluationService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentLevel;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.math.BigDecimal;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
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

    @Mock
    private ManagerDailyControlConcreteItemRepository managerControlConcreteItemRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private WorkerRiskExplanationQualityService explanationQualityService;

    @Mock
    private WorkerRiskEventService riskEventService;

    @Mock
    private AppSettingService appSettingService;

    private WorkerRiskTelegramCallbackService service;

    @BeforeEach
    void setUp() {
        service = new WorkerRiskTelegramCallbackService(
                incidentRepository,
                scoreLedgerRepository,
                userService,
                personalReminderService,
                telegramService,
                managerControlConcreteItemRepository,
                orderRepository,
                explanationQualityService,
                riskEventService,
                appSettingService,
                new WorkerRiskDecisionPolicy()
        );
        lenient().when(explanationQualityService.assess(any(), any())).thenReturn(
                new WorkerRiskExplanationQualityService.Result(
                        WorkerRiskExplanationQuality.LOGICAL,
                        BigDecimal.ONE,
                        "Ответ относится к замечанию",
                        "",
                        "deepseek",
                        "test",
                        10,
                        5
                )
        );
    }

    @Test
    void explanationCallbackFromGroupUsesClickingUserTelegramId() {
        WorkerRiskIncident incident = incident();
        User admin = user(1L, "admin", 777L, "ROLE_ADMIN");
        User worker = user(2L, "worker", 888L, "ROLE_WORKER");
        worker.setWorkerTelegramGroupChatId(-100456L);

        when(userService.findByChatId(777L)).thenReturn(Optional.of(admin));
        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(personalReminderService.hasOpenSystemReminder(worker, "WORKER_RISK_MANAGER_WARNING", 77L))
                .thenReturn(false);
        when(telegramService.sendMessageWithInlineKeyboard(eq(-100456L), any(), eq(null), any()))
                .thenReturn(true);
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Order riskOrder = new Order();
        riskOrder.setId(100L);
        Company riskCompany = new Company();
        riskCompany.setTitle("Арком");
        riskOrder.setCompany(riskCompany);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(riskOrder));

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
        ArgumentCaptor<String> telegramText = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(-100456L),
                telegramText.capture(),
                eq(null),
                any()
        );
        assertEquals(true, telegramText.getValue().contains("Компания: Арком"));

        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(captor.capture());
        assertEquals(WorkerRiskIncidentStatus.OPEN, captor.getValue().getStatus());
        assertEquals(WorkerRiskResolutionAction.EXPLANATION_REQUESTED, captor.getValue().getResolutionAction());
    }

    @Test
    void managerExplanationCallbackWithUnlinkedWorkerDoesNotStartSlaOrSendKeyboard() {
        WorkerRiskIncident incident = incident();
        incident.setResponseDueAt(java.time.LocalDateTime.now().plusHours(2));
        incident.setExplanationReminderAt(java.time.LocalDateTime.now().minusMinutes(5));
        incident.setSectionRestrictedAt(java.time.LocalDateTime.now().minusMinutes(1));
        incident.setSlaDeliveryClaimToken("claim-token");
        incident.setSlaDeliveryClaimedAt(java.time.LocalDateTime.now());
        incident.setSlaDeliveryClaimKind("REMINDER");

        User admin = user(1L, "admin", 777L, "ROLE_ADMIN");
        User worker = user(2L, "secret-login", null, "ROLE_WORKER");
        worker.setWorkerTelegramGroupChatId(-100456L);

        when(userService.findByChatId(777L)).thenReturn(Optional.of(admin));
        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(incidentRepository.save(any(WorkerRiskIncident.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<String> answer = service.handle(callbackFromGroup(-100123L, 777L, "worker-risk:77:e"));

        assertEquals(Optional.of("Личный Telegram не привязан; срок ответа не запущен"), answer);
        assertNull(incident.getResponseDueAt());
        assertNull(incident.getExplanationReminderAt());
        assertNull(incident.getSlaDeliveryClaimToken());
        assertNotNull(incident.getSectionRestrictionReleasedAt());
        verify(telegramService, never()).sendMessageWithInlineKeyboard(anyLong(), any(), any(), any());
        ArgumentCaptor<String> warning = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(eq(-100456L), warning.capture());
        assertEquals(true, warning.getValue().contains("срок и ограничение раздела не запущены"));
        assertFalse(warning.getValue().contains("secret-login"));
        assertFalse(warning.getValue().contains("worker"));
        verify(riskEventService).record(
                eq(incident),
                eq(com.hunt.otziv.worker_activity.model.WorkerRiskEventType.EXPLANATION_REQUEST_FAILED),
                eq(2L),
                eq("WORKER"),
                eq("telegram"),
                any()
        );
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

        when(incidentRepository.findFirstByWorkerUserIdAndStatusAndResolutionActionAndExplanationAcceptedAtIsNullAndExplanationPromptedAtIsNotNullOrderByExplanationPromptedAtDescCreatedAtDesc(
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
        verify(incidentRepository, times(2)).save(captor.capture());
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
        verify(personalReminderService).deleteSystemReminderBySource(
                eq(worker),
                eq("WORKER_RISK_MANAGER_WARNING"),
                eq(77L)
        );
    }

    @Test
    void explanationPromptCallbackFromWorkerGroupAllowsOnlyAssignedWorkerWithoutForcingReplyForOthers() {
        WorkerRiskIncident incident = incident();
        incident.setExplanationReminderAt(java.time.LocalDateTime.now().minusMinutes(30));
        incident.setSectionRestrictedAt(java.time.LocalDateTime.now().minusMinutes(20));
        incident.setSectionRestrictionReleasedAt(java.time.LocalDateTime.now().minusMinutes(10));
        User worker = user(2L, "worker-renamed", 888L, "ROLE_WORKER");
        worker.setWorkerTelegramGroupChatId(-100123L);

        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(userService.findByChatId(888L)).thenReturn(Optional.of(worker));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<String> answer = service.handle(callbackFromGroup(-100123L, 888L, "worker-risk-explain:77"));

        assertEquals(Optional.of("Ответьте на сообщение бота с кодом запроса"), answer);
        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(captor.capture());
        assertEquals(WorkerRiskResolutionAction.EXPLANATION_REQUESTED, captor.getValue().getResolutionAction());
        assertEquals(WorkerRiskIncidentStatus.OPEN, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getResponseDueAt());
        assertNull(captor.getValue().getExplanationReminderAt());
        assertNull(captor.getValue().getSectionRestrictedAt());
        assertNull(captor.getValue().getSectionRestrictionReleasedAt());
        verify(telegramService).sendMessage(eq(-100123L), contains("Код запроса: risk-77"));
        verify(telegramService, never()).sendSelectiveForceReplyMessage(anyLong(), anyLong(), any());
        verify(telegramService, never()).sendForceReplyMessage(anyLong(), any());
    }

    @Test
    void explanationPromptCallbackFromWorkerGroupRejectsAdminManagerOrOwner() {
        WorkerRiskIncident incident = incident();
        User worker = user(2L, "worker", 888L, "ROLE_WORKER");
        worker.setWorkerTelegramGroupChatId(-100123L);
        User manager = user(9L, "manager", 999L, "ROLE_MANAGER");

        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(userService.findByChatId(999L)).thenReturn(Optional.of(manager));

        Optional<String> answer = service.handle(callbackFromGroup(-100123L, 999L, "worker-risk-explain:77"));

        assertEquals(Optional.of("Эта кнопка предназначена назначенному специалисту"), answer);
        verify(incidentRepository, never()).save(any());
        verify(telegramService, never()).sendMessage(anyLong(), any());
    }

    @Test
    void explanationPromptForResolvedRiskDoesNotReopenItAndRemovesStaleButton() {
        WorkerRiskIncident incident = incident();
        incident.setStatus(WorkerRiskIncidentStatus.RESOLVED);
        incident.setResolutionAction(WorkerRiskResolutionAction.VERIFIED);
        incident.setTelegramNotificationChatId(-100123L);
        incident.setTelegramNotificationMessageId(12);
        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));

        Optional<String> answer = service.handle(callbackFromGroup(-100123L, 888L, "worker-risk-explain:77"));

        assertEquals(Optional.of("Риск уже обработан"), answer);
        verify(incidentRepository, never()).save(any());
        verify(telegramService, never()).sendMessage(anyLong(), any());
        verify(telegramService).editMessageText(
                eq(-100123L),
                eq(12),
                contains("Ответ специалиста больше не требуется"),
                eq("HTML"),
                eq(List.of())
        );
    }

    @Test
    void resolvedRiskMessageShowsFinalStateAndRemovesKeyboard() {
        WorkerRiskIncident incident = incident();
        incident.setStatus(WorkerRiskIncidentStatus.IGNORED);
        incident.setResolutionAction(WorkerRiskResolutionAction.FALSE_POSITIVE);
        incident.setTelegramNotificationChatId(-100123L);
        incident.setTelegramNotificationMessageId(12);

        service.markOriginalRiskTelegramMessageResolved(incident);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramService).editMessageText(
                eq(-100123L),
                eq(12),
                text.capture(),
                eq("HTML"),
                eq(List.of())
        );
        assertEquals(true, text.getValue().contains("🟢 РИСК ОБРАБОТАН"));
        assertEquals(true, text.getValue().contains("Статус: ложное срабатывание"));
    }

    @Test
    void explanationPromptFromUnlinkedTelegramExplainsHowToBindAccount() {
        WorkerRiskIncident incident = incident();
        User worker = user(2L, "worker", null, "ROLE_WORKER");
        worker.setWorkerTelegramGroupChatId(-100123L);

        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);

        Optional<String> answer = service.handle(
                callbackFromGroup(-100123L, 444L, "worker-risk-explain:77")
        );

        assertEquals(true, answer.orElse("").contains("администратору для безопасной привязки"));
        verify(incidentRepository, never()).save(any());
        verify(telegramService, never()).sendMessage(anyLong(), any());
        verify(telegramService, never()).sendSelectiveForceReplyMessage(anyLong(), anyLong(), any());
        verify(telegramService, never()).sendForceReplyMessage(anyLong(), any());
    }

    @Test
    void workerGroupReplyStoresAssignedWorkerExplanationAndClearsWorkerReminder() {
        WorkerRiskIncident incident = incident();
        incident.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
        incident.setExplanationRequestedAt(java.time.LocalDateTime.now());
        incident.setExplanationPromptedAt(java.time.LocalDateTime.now());
        User worker = user(2L, "worker", 888L, "ROLE_WORKER");
        worker.setWorkerTelegramGroupChatId(-100123L);
        User managerUser = user(3L, "manager", 999L, "ROLE_MANAGER");
        Manager manager = new Manager();
        manager.setId(10L);
        manager.setUser(managerUser);
        worker.setManagers(Set.of(manager));

        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(userService.findByChatId(888L)).thenReturn(Optional.of(worker));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of());
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of());

        boolean handled = service.handleWorkerGroupTextMessage(
                -100123L,
                888L,
                "Нажмите «Ответить» на это сообщение.\nКод запроса: risk-77",
                true,
                "Тест."
        );

        assertEquals(true, handled);
        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository, times(2)).save(captor.capture());
        assertEquals("Тест.", captor.getValue().getWorkerExplanation());
        assertEquals(2L, captor.getValue().getWorkerExplanationByUserId());
        verify(personalReminderService).deleteSystemReminderBySource(
                eq(worker),
                eq("WORKER_RISK_MANAGER_WARNING"),
                eq(77L)
        );
    }

    @Test
    void workerGroupReplyFromUnrelatedWorkerIsRejected() {
        WorkerRiskIncident incident = incident();
        incident.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
        incident.setExplanationRequestedAt(java.time.LocalDateTime.now());
        incident.setExplanationPromptedAt(java.time.LocalDateTime.now());
        User assignedWorker = user(2L, "worker", 888L, "ROLE_WORKER");
        assignedWorker.setWorkerTelegramGroupChatId(-100123L);
        User coveringWorker = user(5L, "cover", 555L, "ROLE_WORKER");

        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(assignedWorker);
        when(userService.findByChatId(555L)).thenReturn(Optional.of(coveringWorker));
        boolean handled = service.handleWorkerGroupTextMessage(
                -100123L,
                555L,
                "Нажмите «Ответить» на это сообщение.\nКод запроса: risk-77",
                true,
                "Проверила аккаунт дважды перед блокировкой"
        );

        assertEquals(false, handled);
        assertNull(incident.getWorkerExplanationByUserId());
        assertNull(incident.getWorkerExplanation());
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void workerGroupReplyFromManagerIsNotStoredAsWorkerExplanation() {
        WorkerRiskIncident incident = incident();
        User worker = user(2L, "worker", 888L, "ROLE_WORKER");
        worker.setWorkerTelegramGroupChatId(-100123L);
        User manager = user(4L, "manager", 444L, "ROLE_MANAGER");

        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(userService.findByChatId(444L)).thenReturn(Optional.of(manager));

        boolean handled = service.handleWorkerGroupTextMessage(
                -100123L,
                444L,
                "Нажмите «Ответить» на это сообщение.\nКод запроса: risk-77",
                true,
                "Комментарий менеджера"
        );

        assertEquals(false, handled);
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void workerGroupReplyFromUnlinkedTelegramIsRejectedWithVisibleInstruction() {
        WorkerRiskIncident incident = incident();
        incident.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
        incident.setExplanationRequestedAt(java.time.LocalDateTime.now());
        User worker = user(2L, "worker", 888L, "ROLE_WORKER");
        worker.setWorkerTelegramGroupChatId(-100123L);

        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);

        boolean handled = service.handleWorkerGroupTextMessage(
                -100123L,
                444L,
                "Нажмите «Ответить» на это сообщение.\nКод запроса: risk-77",
                true,
                "Проверила аккаунт дважды"
        );

        assertEquals(false, handled);
        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(eq(-100123L), instruction.capture());
        assertEquals(true, instruction.getValue().contains("Ответ не засчитан: Telegram не привязан"));
        assertFalse(instruction.getValue().contains(worker.getUsername()));
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void genericWorkerAnswerRemainsPendingAndDoesNotReachManager() {
        WorkerRiskIncident incident = incident();
        incident.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
        incident.setExplanationPromptedAt(java.time.LocalDateTime.now());
        User worker = user(2L, "worker", 888L, "ROLE_WORKER");
        when(incidentRepository
                .findFirstByWorkerUserIdAndStatusAndResolutionActionAndExplanationAcceptedAtIsNullAndExplanationPromptedAtIsNotNullOrderByExplanationPromptedAtDescCreatedAtDesc(
                        2L,
                        WorkerRiskIncidentStatus.OPEN,
                        WorkerRiskResolutionAction.EXPLANATION_REQUESTED
                )).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(explanationQualityService.assess(any(), any())).thenReturn(
                new WorkerRiskExplanationQualityService.Result(
                        WorkerRiskExplanationQuality.PARTIAL,
                        BigDecimal.ONE,
                        "Ответ слишком общий",
                        "Что именно было сделано?",
                        "rules",
                        "",
                        0,
                        0
                )
        );

        assertEquals(true, service.handleWorkerTextMessage(888L, worker, "большой заказ"));

        assertNull(incident.getExplanationAcceptedAt());
        assertEquals(WorkerRiskExplanationQuality.PARTIAL, incident.getExplanationQuality());
        assertEquals(false, incident.isAuditRequired());
        verify(personalReminderService, never()).deleteSystemReminderBySource(
                any(),
                eq("WORKER_RISK_MANAGER_WARNING"),
                eq(77L)
        );
    }

    @Test
    void deepSeekNeedsReviewDoesNotAcceptAnswerOrTurnStatusGreen() {
        WorkerRiskIncident incident = incident();
        incident.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
        incident.setExplanationPromptedAt(java.time.LocalDateTime.now());
        User worker = user(2L, "worker", 888L, "ROLE_WORKER");
        when(incidentRepository
                .findFirstByWorkerUserIdAndStatusAndResolutionActionAndExplanationAcceptedAtIsNullAndExplanationPromptedAtIsNotNullOrderByExplanationPromptedAtDescCreatedAtDesc(
                        2L,
                        WorkerRiskIncidentStatus.OPEN,
                        WorkerRiskResolutionAction.EXPLANATION_REQUESTED
                )).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(appSettingService.getInt(
                AppSettingService.WORKER_RISK_EXPLANATION_MAX_CLARIFICATIONS,
                1
        )).thenReturn(1);
        when(explanationQualityService.assess(any(), any())).thenReturn(
                new WorkerRiskExplanationQualityService.Result(
                        WorkerRiskExplanationQuality.NEEDS_REVIEW,
                        BigDecimal.ZERO,
                        "DeepSeek не смог подтвердить правильность ответа",
                        "",
                        "deepseek",
                        "test",
                        0,
                        0
                )
        );

        assertEquals(true, service.handleWorkerTextMessage(
                888L,
                worker,
                "Причина связана с блокировкой аккаунта, детали уточняю"
        ));

        assertNull(incident.getExplanationAcceptedAt());
        assertEquals(WorkerRiskExplanationQuality.NEEDS_REVIEW, incident.getExplanationQuality());
        verify(telegramService).sendForceReplyMessage(
                eq(888L),
                contains("🟡 ОТВЕТ НУЖНО УТОЧНИТЬ")
        );
        verify(personalReminderService, never()).deleteSystemReminderBySource(
                any(),
                eq("WORKER_RISK_MANAGER_WARNING"),
                eq(77L)
        );
    }

    @Test
    void verifiedCallbackDeletesOpenRiskReminder() {
        WorkerRiskIncident incident = incident();
        incident.setExplanationQuality(WorkerRiskExplanationQuality.LOGICAL);
        User admin = user(1L, "admin", 777L, "ROLE_ADMIN");

        when(userService.findByChatId(777L)).thenReturn(Optional.of(admin));
        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<String> answer = service.handle(callbackFromGroup(-100123L, 777L, "worker-risk:77:v"));

        assertEquals(Optional.of("Инцидент проверен"), answer);
        verify(personalReminderService).deleteSystemRemindersBySource(
                WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT,
                77L
        );
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
        incident.setTitle("Блок аккаунта без попытки войти в него");
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
