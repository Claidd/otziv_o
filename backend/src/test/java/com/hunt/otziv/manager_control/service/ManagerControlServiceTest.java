package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.client_messages.service.ClientMessageOrderStatusService;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.ClientMessageOrderStatusResponse;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageReconciliationService;
import com.hunt.otziv.client_chat_control.service.ClientChatReplySuggestionService;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.service.SharedChatLinkSyncService;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.common_billing.service.CommonPaymentInitFailureClassifier;
import com.hunt.otziv.common_billing.service.CommonInvoicePublicationBlockerService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlClientReplyRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlItemActionRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlOverdueStatusResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlStageRequest;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlSeverity;
import com.hunt.otziv.manager_control.model.ManagerDailyControlStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.OrderPaymentIntegrityService;
import com.hunt.otziv.payments.service.StandaloneBankPaymentPolicy;
import com.hunt.otziv.payments.service.BadReviewPaymentInstructionOrchestrator;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.t_telegrambot.service.TelegramGroupLinkService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import com.hunt.otziv.whatsapp.service.WhatsAppGroupLinkSyncService;
import com.hunt.otziv.maxbot.service.MaxGroupLinkService;
import java.lang.reflect.Method;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerControlServiceTest {

    @Mock
    private ManagerRepository managerRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserService userService;
    @Mock
    private ManagerAccessService managerAccessService;
    @Mock
    private ManagerPermissionService managerPermissionService;
    @Mock
    private PersonalReminderService personalReminderService;
    @Mock
    private TelegramService telegramService;
    @Mock
    private NotificationMediaDeliveryService notificationMediaDeliveryService;
    @Mock
    private OrderService orderService;
    @Mock
    private ClientMessageOrderStatusService clientMessageOrderStatusService;
    @Mock
    private ScheduledClientMessageService scheduledClientMessageService;
    @Mock
    private ScheduledClientMessageStateRepository scheduledClientMessageStateRepository;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ManagerAutomationFailureService managerAutomationFailureService;
    @Mock
    private ClientChatMessageSender clientChatMessageSender;
    @Mock
    private ClientChatMessageTrackerService clientChatMessageTrackerService;
    @Mock
    private ClientChatMessageReconciliationService clientChatMessageReconciliationService;
    @Mock
    private ClientChatReplySuggestionService clientChatReplySuggestionService;
    @Mock
    private ClientChatUnansweredItemRepository clientChatUnansweredItemRepository;
    @Mock
    private BadReviewTaskService badReviewTaskService;
    @Mock
    private ReviewRecoveryTaskService reviewRecoveryTaskService;
    @Mock
    private ReviewService reviewService;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private OrderPaymentIntegrityService orderPaymentIntegrityService;
    @Mock
    private BadReviewPaymentInstructionOrchestrator paymentInstructionOrchestrator;
    @Spy
    private ManagerControlTransactionRunner managerControlTransactionRunner = new ManagerControlTransactionRunner();
    @Mock
    private CommonInvoiceRepository commonInvoiceRepository;
    @Mock
    private CommonInvoiceOrderRepository commonInvoiceOrderRepository;
    @Mock
    private CommonInvoicePublicationBlockerService commonInvoicePublicationBlockerService;
    @Mock
    private CommonBillingService commonBillingService;
    @Spy
    private ManagerControlInvoiceOperationExecutor invoiceOperationExecutor =
            new ManagerControlInvoiceOperationExecutor();
    @Mock
    private OrderPublicationApprovalService publicationApprovalService;
    @Mock
    private WorkerRiskIncidentRepository riskIncidentRepository;
    @Mock
    private WhatsAppGroupLinkSyncService whatsAppGroupLinkSyncService;
    @Mock
    private SharedChatLinkSyncService sharedChatLinkSyncService;
    @Mock
    private TelegramGroupLinkService telegramGroupLinkService;
    @Mock
    private MaxGroupLinkService maxGroupLinkService;
    @Mock
    private ManagerDailyControlRepository dailyControlRepository;
    @Mock
    private ManagerDailyControlItemRepository dailyControlItemRepository;
    @Mock
    private ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;
    @Mock
    private ManagerDailyControlEventRepository dailyControlEventRepository;
    @Spy
    private ManagerActionBalanceService managerActionBalanceService = new ManagerActionBalanceService();
    @Mock
    private ManagerOperationalMetricsService managerOperationalMetricsService;
    @Mock
    private LeadsRepository leadsRepository;
    @Mock
    private GamificationEventService gamificationEventService;

    @InjectMocks
    private ManagerControlService service;

    @Test
    void fastClickRiskUsesOnlyManagerClientMessageResolutions() throws Exception {
        ManagerDailyControl control = new ManagerDailyControl();
        control.setManagerUserId(17L);

        ManagerDailyControlItem clientMessages = new ManagerDailyControlItem();
        clientMessages.setReasonCode("UNANSWERED_CLIENT_MESSAGES");
        ManagerDailyControlItem workerOverdue = new ManagerDailyControlItem();
        workerOverdue.setReasonCode("WORKER_OVERDUE_PUBLICATIONS");

        ManagerDailyControlEvent managerClientClosure = event(
                clientMessages,
                17L,
                ManagerDailyControlActionType.RESOLVED
        );
        ManagerDailyControlEvent workerReminder = event(
                workerOverdue,
                17L,
                ManagerDailyControlActionType.ACTION_TAKEN
        );
        ManagerDailyControlEvent anotherEmployeeClosure = event(
                clientMessages,
                44L,
                ManagerDailyControlActionType.RESOLVED
        );
        ManagerDailyControlEvent deferredClientMessage = event(
                clientMessages,
                17L,
                ManagerDailyControlActionType.DEFERRED
        );

        Method method = ManagerControlService.class.getDeclaredMethod(
                "isManagerClientMessageResolutionEvent",
                ManagerDailyControl.class,
                ManagerDailyControlEvent.class
        );
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(service, control, managerClientClosure));
        assertFalse((boolean) method.invoke(service, control, workerReminder));
        assertFalse((boolean) method.invoke(service, control, anotherEmployeeClosure));
        assertFalse((boolean) method.invoke(service, control, deferredClientMessage));
    }

    @Test
    void concreteMessageSlaUsesActualFirstObservedTime() throws Exception {
        LocalDateTime firstObservedAt = LocalDateTime.of(2026, 7, 13, 18, 0);
        ManagerDailyControlItem parent = new ManagerDailyControlItem();
        parent.setReasonCode("UNANSWERED_CLIENT_MESSAGES");
        parent.setCreatedAt(firstObservedAt.plusHours(1));
        ManagerControlConcreteItemResponse concrete = new ManagerControlConcreteItemResponse(
                1L, "CLIENT_CHAT_UNANSWERED", 2L, "Клиент", null, null, 0L, null,
                "/chat", null, null, null, null, "OPEN", null, null,
                firstObservedAt.plusHours(1), null, null
        ).withSla(firstObservedAt, null, null, null);
        when(appSettingService.getBoolean("manager.sla.enabled", false)).thenReturn(true);
        when(appSettingService.getInt("manager.sla.target.control-card-minutes", 30)).thenReturn(30);
        when(appSettingService.getInt("manager.sla.hard.control-card-minutes", 60)).thenReturn(480);

        Method method = ManagerControlService.class.getDeclaredMethod(
                "decorateConcreteSla",
                ManagerDailyControlItem.class,
                ManagerControlConcreteItemResponse.class
        );
        method.setAccessible(true);
        ManagerControlConcreteItemResponse response = (ManagerControlConcreteItemResponse) method.invoke(service, parent, concrete);

        assertEquals(firstObservedAt, response.firstObservedAt());
        assertEquals(firstObservedAt.plusMinutes(30), response.targetDeadlineAt());
        assertEquals(firstObservedAt.plusMinutes(480), response.hardDeadlineAt());
    }

    @Test
    void orderControlSlaStartsWhenStatusBecomesActionableInsteadOfPageOpen() throws Exception {
        LocalDateTime statusChangedAt = LocalDateTime.of(2026, 7, 13, 9, 38);
        OrderDTOList order = OrderDTOList.builder()
                .id(42L)
                .status("На проверке")
                .changed(statusChangedAt.toLocalDate())
                .statusChangedAt(statusChangedAt)
                .build();
        when(appSettingService.getInt(
                eq(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS),
                eq(ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS)
        )).thenReturn(2);

        Method method = ManagerControlService.class.getDeclaredMethod("orderControlStartedAt", OrderDTOList.class);
        method.setAccessible(true);

        assertEquals(statusChangedAt.plusDays(2), method.invoke(service, order));
    }

    @Test
    void synchronizationBackfillsEarlierSourceTimeForExistingCard() throws Exception {
        LocalDateTime pageOpenedAt = LocalDateTime.of(2026, 7, 15, 9, 38);
        LocalDateTime sourceActionableAt = LocalDateTime.of(2026, 7, 15, 7, 12);
        ManagerDailyControlConcreteItem stored = new ManagerDailyControlConcreteItem();
        stored.setCreatedAt(pageOpenedAt);
        ManagerControlConcreteItemResponse source = new ManagerControlConcreteItemResponse(
                null, "ORDER", 42L, "Компания", null, "На проверке", 2L, "Причина",
                "/orders", null, null, null, null, "OPEN", null, null,
                null, null, null
        ).withSla(sourceActionableAt, null, null, null);

        Method method = ManagerControlService.class.getDeclaredMethod(
                "applyConcreteItemSnapshot",
                ManagerDailyControlConcreteItem.class,
                ManagerControlConcreteItemResponse.class
        );
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(service, stored, source));
        assertEquals(sourceActionableAt, stored.getCreatedAt());

        LocalDateTime correctedLaterSource = LocalDateTime.of(2026, 7, 15, 14, 55);
        stored.setCreatedAt(LocalDateTime.of(2026, 7, 15, 0, 0));
        source = source.withSla(correctedLaterSource, null, null, null);

        assertTrue((Boolean) method.invoke(service, stored, source));
        assertEquals(correctedLaterSource, stored.getCreatedAt());
    }

    @Test
    void commonInvoiceControlPassesStatusesThatRequireAllOrdersReady() throws Exception {
        Manager manager = new Manager();
        when(commonInvoiceRepository.countManagerControlInvoices(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L);

        Method method = ManagerControlService.class.getDeclaredMethod("commonInvoiceActionCount", Manager.class);
        method.setAccessible(true);

        assertEquals(0L, method.invoke(service, manager));
        verify(commonInvoiceRepository).countManagerControlInvoices(
                eq(manager),
                any(),
                any(),
                eq(com.hunt.otziv.common_billing.model.CommonInvoiceStatus.PARTIALLY_PAID),
                eq(com.hunt.otziv.common_billing.model.CommonInvoiceStatus.COLLECTING),
                any(),
                any()
        );
    }

    @Test
    void manualWorkerActionSendsTelegramAndSnoozesForThreeHoursWhenDelivered() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "BAD_REVIEW_TASK");
        stubSuccessfulConcreteAction(concrete, parent);
        BadReviewTask task = new BadReviewTask();
        Worker worker = new Worker();
        User workerUser = new User();
        workerUser.setId(501L);
        workerUser.setWorkerTelegramGroupChatId(-100123L);
        worker.setUser(workerUser);
        task.setWorker(worker);
        Company company = new Company();
        company.setTitle("Для Вас");
        Order order = new Order();
        order.setId(777L);
        order.setCompany(company);
        task.setOrder(order);
        concrete.setReason("Заказ ждет текст клиента, но автоответчик не отправляет напоминания: нет записи в очереди CLIENT_TEXT_REMINDER.");
        concrete.setComment("Специалисту отправлено напоминание. Повторный контроль завтра.");
        when(badReviewTaskService.getTask(concrete.getEntityId())).thenReturn(task);
        when(notificationMediaDeliveryService.send(
                any(),
                eq(-100123L),
                eq(501L),
                any(),
                any(),
                any()
        )).thenReturn(true);

        LocalDateTime before = LocalDateTime.now();
        ManagerControlConcreteItemResponse response = service.actionConcreteItem(
                concrete.getId(),
                new ManagerControlItemActionRequest("ACTION_TAKEN", null, true),
                principal(),
                adminAuth()
        );
        LocalDateTime after = LocalDateTime.now();

        assertEquals(ManagerDailyControlItemStatus.ACTION_TAKEN, concrete.getStatus());
        assertEquals(ManagerDailyControlActionType.ACTION_TAKEN, concrete.getActionType());
        assertNotNull(concrete.getLastManualTouchAt());
        assertNotNull(concrete.getFollowUpAt());
        assertFalse(concrete.getFollowUpAt().isBefore(before.plusHours(3)));
        assertFalse(concrete.getFollowUpAt().isAfter(after.plusHours(3)));
        assertNotNull(concrete.getWorkerNotificationAttemptedAt());
        assertNotNull(concrete.getWorkerNotificationSentAt());
        assertNull(concrete.getWorkerNotificationFailureReason());
        assertTrue(concrete.getComment().contains("Повторный контроль через 3 ч."));
        assertEquals("ACTION_TAKEN", response.itemStatus());
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationMediaDeliveryService).send(
                any(),
                eq(-100123L),
                eq(501L),
                messageCaptor.capture(),
                any(),
                any()
        );
        String message = messageCaptor.getValue();
        assertTrue(message.contains("Причина: Заказ ждет текст клиента, автонапоминание не ушло."));
        assertTrue(message.contains("Заказ: #777"));
        assertTrue(message.contains("Фирма: Для Вас"));
        assertTrue(message.contains("Что сделать: нажмите кнопку"));
        assertFalse(message.contains("Менеджер:"));
        assertFalse(message.contains("CLIENT_TEXT_REMINDER"));
        assertFalse(message.contains("Повторный контроль"));
    }

    @Test
    void manualWorkerActionKeepsCardOpenWhenTelegramIsNotDelivered() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "BAD_REVIEW_TASK");
        when(dailyControlConcreteItemRepository.findById(concrete.getId())).thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlConcreteItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dailyControlRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        BadReviewTask task = new BadReviewTask();
        Worker worker = new Worker();
        User workerUser = new User();
        workerUser.setId(501L);
        worker.setUser(workerUser);
        task.setWorker(worker);
        when(badReviewTaskService.getTask(concrete.getEntityId())).thenReturn(task);

        ManagerControlConcreteItemResponse response = service.actionConcreteItem(
                concrete.getId(),
                new ManagerControlItemActionRequest("ACTION_TAKEN", null, true),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.OPEN, concrete.getStatus());
        assertNull(concrete.getActionType());
        assertNull(concrete.getFollowUpAt());
        assertNotNull(concrete.getWorkerNotificationAttemptedAt());
        assertNull(concrete.getWorkerNotificationSentAt());
        assertEquals("Telegram-группа специалиста не привязана", concrete.getWorkerNotificationFailureReason());
        assertEquals("OPEN", response.itemStatus());
        verify(telegramService, never()).sendMessageWithInlineKeyboard(anyLong(), any(), any(), any());
    }

    @Test
    void acknowledgedIsRejectedForCriticalConcreteActionItem() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "BAD_REVIEW_TASK");

        when(dailyControlConcreteItemRepository.findById(concrete.getId())).thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.actionConcreteItem(
                concrete.getId(),
                new ManagerControlItemActionRequest("ACKNOWLEDGED", null, null),
                principal(),
                adminAuth()
        ));

        assertEquals(400, ex.getStatusCode().value());
        assertEquals(ManagerDailyControlItemStatus.OPEN, concrete.getStatus());
        verify(dailyControlConcreteItemRepository, never()).save(any());
        verify(dailyControlEventRepository, never()).save(any());
    }

    @Test
    void criticalAggregateActionIsRejectedBecauseConcreteCardsMustBeHandled() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        when(dailyControlItemRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.actionItem(
                parent.getId(),
                new ManagerControlItemActionRequest("ACTION_TAKEN", "Разобрано общим комментарием", null),
                principal(),
                adminAuth()
        ));

        assertEquals(400, ex.getStatusCode().value());
        assertEquals(ManagerDailyControlItemStatus.OPEN, parent.getStatus());
        verify(dailyControlItemRepository, never()).save(any());
        verify(dailyControlEventRepository, never()).save(any());
    }

    @Test
    void manualPaymentOrderSendMovesInvoiceStatusToReminderAndSnoozesForTwoDays() throws Exception {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setEntityId(77L);
        concrete.setStatusLabel("Выставлен счет");
        Order order = new Order();
        order.setId(77L);
        order.setStatus(OrderStatus.builder().title("Выставлен счет").build());
        stubSuccessfulConcreteAction(concrete, parent);
        when(orderRepository.findById(77L)).thenReturn(Optional.of(order));
        when(orderService.changeStatusForOrder(77L, "Напоминание")).thenReturn(true);

        LocalDateTime before = LocalDateTime.now();
        service.actionConcreteItem(
                concrete.getId(),
                new ManagerControlItemActionRequest("ACTION_TAKEN", "Сообщение отправлено клиенту", null),
                principal(),
                adminAuth()
        );
        LocalDateTime after = LocalDateTime.now();

        assertEquals("Напоминание", concrete.getStatusLabel());
        assertNotNull(concrete.getFollowUpAt());
        assertFalse(concrete.getFollowUpAt().isBefore(before.plusDays(2)));
        assertFalse(concrete.getFollowUpAt().isAfter(after.plusDays(2)));
        verify(orderService).changeStatusForOrder(77L, "Напоминание");
    }

    @Test
    void successfulClientMessageClosesConcreteCardImmediately() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setEntityId(77L);
        concrete.setStatusLabel("Опубликовано");
        Company company = new Company();
        company.setTitle("Галерея");
        Order order = new Order();
        order.setId(77L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Опубликовано").build());
        when(dailyControlConcreteItemRepository.findByIdForUpdate(concrete.getId())).thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlConcreteItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dailyControlConcreteItemRepository.findByParentItem(parent)).thenReturn(List.of(concrete));
        when(dailyControlItemRepository.findByControl(control)).thenReturn(List.of(parent));
        when(orderRepository.findByIdForCounterUpdate(77L)).thenReturn(Optional.of(order));
        when(orderRepository.findById(77L)).thenReturn(Optional.of(order));
        when(paymentInstructionOrchestrator.prepareAuthorized(eq(77L), any(Authentication.class)))
                .thenReturn(new BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction(
                        "canonical payment", "token-77", 77L, true, "2202208238396676"));
        TelegramTransferCopyButton copyButton = TelegramTransferCopyButton
                .fromFrozenTransferNumber("2202208238396676")
                .orElseThrow();
        when(clientChatMessageSender.send(any(), any(), any(), any(), eq(copyButton)))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp"));

        ManagerControlConcreteItemResponse response = service.sendClientMessage(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.RESOLVED, concrete.getStatus());
        assertEquals(ManagerDailyControlActionType.RESOLVED, concrete.getActionType());
        assertNotNull(concrete.getResolvedAt());
        assertNull(concrete.getFollowUpAt());
        assertEquals(ManagerDailyControlItemStatus.RESOLVED.name(), response.itemStatus());
        verify(clientChatMessageSender).send(
                eq(company), any(), any(), eq("canonical payment"), eq(copyButton)
        );
    }

    @Test
    void statusChangedAfterExternalSendIsQuarantinedWithoutClosingCardOrReleasingSource() throws Exception {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setEntityId(82L);
        Company company = new Company();
        Order order = new Order();
        order.setId(82L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Опубликовано").build());
        var prepared = new BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction(
                "payment", "fresh-82", 82L, true);
        when(dailyControlConcreteItemRepository.findByIdForUpdate(concrete.getId()))
                .thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlConcreteItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.findByIdForCounterUpdate(82L)).thenReturn(Optional.of(order));
        when(paymentInstructionOrchestrator.prepareAuthorized(eq(82L), any(Authentication.class)))
                .thenReturn(prepared);
        when(clientChatMessageSender.send(any(), any(), any(), eq("payment"), isNull())).thenAnswer(invocation -> {
            order.setStatus(OrderStatus.builder().title("Бан").build());
            return ClientMessageSendResult.sent("WhatsApp");
        });

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.sendClientMessage(concrete.getId(), principal(), adminAuth())
        );

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, failure.getStatusCode());
        assertEquals(ManagerDailyControlItemStatus.ACTION_TAKEN, concrete.getStatus());
        assertTrue(concrete.getComment().startsWith("client_message_delivery_unknown:"));
        verify(paymentInstructionOrchestrator, never()).releaseKnownUnsent(any(), any());
        verify(orderService, never()).changeStatusForOrder(eq(82L), anyString());
    }

    @Test
    void knownUnsentFreshPaymentInstructionIsReleasedAndCardIsRestored() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setEntityId(80L);
        concrete.setStatus(ManagerDailyControlItemStatus.OPEN);
        Company company = new Company();
        Order order = new Order();
        order.setId(80L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());
        var prepared = new BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction(
                "payment", "fresh-80", 80L, true);
        when(dailyControlConcreteItemRepository.findByIdForUpdate(concrete.getId())).thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlConcreteItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.findByIdForCounterUpdate(80L)).thenReturn(Optional.of(order));
        when(paymentInstructionOrchestrator.prepareAuthorized(eq(80L), any(Authentication.class))).thenReturn(prepared);
        when(clientChatMessageSender.send(any(), any(), any(), eq("payment"), isNull()))
                .thenReturn(ClientMessageSendResult.failed("DOWN", "unavailable"));

        assertThrows(ResponseStatusException.class,
                () -> service.sendClientMessage(concrete.getId(), principal(), adminAuth()));

        assertEquals(ManagerDailyControlItemStatus.OPEN, concrete.getStatus());
        verify(paymentInstructionOrchestrator).releaseKnownUnsent(eq(prepared), any(Authentication.class));
    }

    @Test
    void unknownSendFailureRetainsPaymentSourceAndMarksManualReview() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setEntityId(81L);
        Company company = new Company();
        Order order = new Order();
        order.setId(81L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());
        var prepared = new BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction(
                "payment", "fresh-81", 81L, true);
        when(dailyControlConcreteItemRepository.findByIdForUpdate(concrete.getId())).thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlConcreteItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.findByIdForCounterUpdate(81L)).thenReturn(Optional.of(order));
        when(paymentInstructionOrchestrator.prepareAuthorized(eq(81L), any(Authentication.class))).thenReturn(prepared);
        when(clientChatMessageSender.send(any(), any(), any(), eq("payment"), isNull()))
                .thenThrow(new IllegalStateException("timeout"));

        assertThrows(ResponseStatusException.class,
                () -> service.sendClientMessage(concrete.getId(), principal(), adminAuth()));

        assertTrue(concrete.getComment().startsWith("client_message_delivery_unknown:"));
        verify(paymentInstructionOrchestrator, never()).releaseKnownUnsent(any(), any());
    }

    @Test
    void commonInvoiceGuardStopsManagerControlStandalonePaymentSend() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setEntityId(78L);
        Company company = new Company();
        Order order = new Order();
        order.setId(78L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());
        when(dailyControlConcreteItemRepository.findByIdForUpdate(concrete.getId())).thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(orderRepository.findByIdForCounterUpdate(78L)).thenReturn(Optional.of(order));
        when(paymentInstructionOrchestrator.prepareAuthorized(eq(78L), any(Authentication.class)))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "Заказ входит в общий счет"
                ));

        assertThrows(
                ResponseStatusException.class,
                () -> service.sendClientMessage(concrete.getId(), principal(), adminAuth())
        );

        verify(clientChatMessageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void stalePreparedDeliveryBecomesManualReviewWithoutResend() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setComment("client_message_delivery_prepared:abandoned-token");
        concrete.setLastManualTouchAt(LocalDateTime.now().minusMinutes(16));
        when(dailyControlConcreteItemRepository.findByIdForUpdate(concrete.getId())).thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlConcreteItemRepository.save(concrete)).thenReturn(concrete);

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.sendClientMessage(concrete.getId(), principal(), adminAuth())
        );

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, failure.getStatusCode());
        assertEquals(ManagerDailyControlItemStatus.ACTION_TAKEN, concrete.getStatus());
        assertEquals(ManagerDailyControlActionType.ACTION_TAKEN, concrete.getActionType());
        assertTrue(concrete.getComment().startsWith("client_message_delivery_unknown:"));
        verify(dailyControlConcreteItemRepository, times(1)).findByIdForUpdate(concrete.getId());
        verify(orderRepository, never()).findByIdForCounterUpdate(anyLong());
        verify(paymentInstructionOrchestrator, never())
                .prepareAuthorized(anyLong(), any(Authentication.class));
        verify(clientChatMessageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void reassignedOrderRejectsStaleManagerCardBeforePaymentInstruction() {
        User oldManagerUser = new User();
        oldManagerUser.setId(2L);
        oldManagerUser.setUsername("manager");
        Manager oldManager = new Manager();
        oldManager.setId(11L);
        oldManager.setUser(oldManagerUser);
        Manager newManager = new Manager();
        newManager.setId(12L);

        ManagerDailyControl control = control();
        control.setManager(oldManager);
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setEntityId(79L);
        concrete.setStatusLabel("Не оплачено");
        Order order = new Order();
        order.setId(79L);
        order.setManager(newManager);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());
        Principal managerPrincipal = () -> "manager";
        Authentication managerAuthentication = new UsernamePasswordAuthenticationToken(
                "manager",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        );

        when(dailyControlConcreteItemRepository.findByIdForUpdate(concrete.getId())).thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(managerAuthentication, "ADMIN")).thenReturn(false);
        when(managerPermissionService.hasRole(managerAuthentication, "OWNER")).thenReturn(false);
        when(managerPermissionService.hasRole(managerAuthentication, "MANAGER")).thenReturn(true);
        when(userService.findByUserName("manager")).thenReturn(Optional.of(oldManagerUser));
        when(managerRepository.findByUserId(2L)).thenReturn(Optional.of(oldManager));
        when(orderRepository.findByIdForCounterUpdate(79L)).thenReturn(Optional.of(order));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Заказ не найден"))
                .when(managerAccessService).requireOrderAccess(79L, managerAuthentication);

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.sendClientMessage(concrete.getId(), managerPrincipal, managerAuthentication)
        );

        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, failure.getStatusCode());
        verify(orderRepository).findByIdForCounterUpdate(79L);
        verify(paymentInstructionOrchestrator, never())
                .prepareAuthorized(anyLong(), any(Authentication.class));
        verify(clientChatMessageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void resolvedRiskActionClosesConcreteCardAndParentImmediately() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        parent.setReasonCode("OPEN_RISKS");
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "RISK");
        stubSuccessfulConcreteAction(concrete, parent);
        when(managerPermissionService.hasAnyRole(any(), eq("ADMIN"), eq("OWNER"))).thenReturn(true);

        ManagerControlConcreteItemResponse response = service.actionConcreteItem(
                concrete.getId(),
                new ManagerControlItemActionRequest(
                        "RESOLVED",
                        "Проверено администратором/владельцем",
                        null
                ),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.RESOLVED, concrete.getStatus());
        assertEquals(ManagerDailyControlActionType.RESOLVED, concrete.getActionType());
        assertNotNull(concrete.getResolvedAt());
        assertEquals(ManagerDailyControlItemStatus.RESOLVED, parent.getStatus());
        assertEquals(ManagerDailyControlActionType.RESOLVED, parent.getActionType());
        assertEquals("RESOLVED", response.itemStatus());
        verify(dailyControlItemRepository).save(parent);
    }

    @Test
    void commonInvoiceConcreteActionClosesCardWhenInvoiceIsNoLongerAProblem() throws Exception {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "COMMON_INVOICE");
        concrete.setEntityId(88L);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(88L);
        invoice.setStatus(CommonInvoiceStatus.INVOICED);
        invoice.setUpdatedAt(LocalDateTime.now());
        stubSuccessfulConcreteAction(concrete, parent);
        when(commonInvoiceRepository.findById(88L)).thenReturn(Optional.of(invoice));

        ManagerControlConcreteItemResponse response = service.actionConcreteItem(
                concrete.getId(),
                new ManagerControlItemActionRequest("ACTION_TAKEN", "Ошибка счета показана в правой панели", null),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.RESOLVED, concrete.getStatus());
        assertEquals("RESOLVED", response.itemStatus());
        assertEquals("Ошибка счета показана в правой панели", concrete.getComment());
        verify(orderRepository, never()).findById(any());
        verify(orderService, never()).changeStatusForOrder(any(), any());
    }

    @Test
    void commonInvoiceConcreteActionRejectsFalseDoneWhileInvoiceIsStillStale() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "COMMON_INVOICE");
        concrete.setEntityId(88L);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(88L);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setUpdatedAt(LocalDateTime.now().minusDays(5));
        when(dailyControlConcreteItemRepository.findById(concrete.getId())).thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(commonInvoiceRepository.findById(88L)).thenReturn(Optional.of(invoice));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.actionConcreteItem(
                concrete.getId(),
                new ManagerControlItemActionRequest("ACTION_TAKEN", "Готово", null),
                principal(),
                adminAuth()
        ));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("все еще требует внимания"));
        verify(dailyControlConcreteItemRepository, never()).save(any());
    }

    @Test
    void clientChatUnansweredActionResolvesConcreteCard() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "CLIENT_CHAT_UNANSWERED");
        concrete.setEntityId(101L);
        stubSuccessfulConcreteAction(concrete, parent);

        ManagerControlConcreteItemResponse response = service.actionConcreteItem(
                concrete.getId(),
                new ManagerControlItemActionRequest("ACTION_TAKEN", "Ответ клиенту проверен вручную", null),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.RESOLVED, concrete.getStatus());
        assertEquals(ManagerDailyControlActionType.ACTION_TAKEN, concrete.getActionType());
        assertNotNull(concrete.getResolvedAt());
        assertEquals(ManagerDailyControlItemStatus.RESOLVED, parent.getStatus());
        assertEquals("RESOLVED", response.itemStatus());
        verify(clientChatMessageTrackerService).markFromManagerControl(
                101L,
                ManagerDailyControlActionType.ACTION_TAKEN,
                "Ответ клиенту проверен вручную",
                1L
        );
    }

    @Test
    void deferredClientChatUnansweredCardStaysOpenWithoutReplyEvidence() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        parent.setStatus(ManagerDailyControlItemStatus.DEFERRED);
        parent.setActionType(ManagerDailyControlActionType.DEFERRED);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "CLIENT_CHAT_UNANSWERED");
        concrete.setEntityId(102L);
        stubSuccessfulConcreteAction(concrete, parent);

        ManagerControlConcreteItemResponse response = service.actionConcreteItem(
                concrete.getId(),
                new ManagerControlItemActionRequest("DEFERRED", "Ответ был в личных сообщениях", null),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.OPEN, concrete.getStatus());
        assertEquals(ManagerDailyControlActionType.DEFERRED, concrete.getActionType());
        assertNull(concrete.getResolvedAt());
        assertNull(concrete.getFollowUpAt());
        assertEquals(0L, concrete.getDeferredEpisodeCount());
        assertEquals(ManagerDailyControlItemStatus.OPEN, parent.getStatus());
        assertNull(parent.getActionType());
        assertEquals("OPEN", response.itemStatus());
        verify(clientChatMessageTrackerService).markFromManagerControl(
                102L,
                ManagerDailyControlActionType.DEFERRED,
                "Ответ был в личных сообщениях",
                1L
        );
    }

    @Test
    void repairAutomationFailureRetriesSourceTaskAndResolvesAfterFreshCheck() {
        Manager manager = new Manager();
        manager.setId(3L);
        ManagerDailyControl control = control();
        control.setManager(manager);
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(
                control,
                parent,
                ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE
        );
        concrete.setEntityId(501L);
        ManagerAutomationFailureService.AutomationFailureIssue issue =
                new ManagerAutomationFailureService.AutomationFailureIssue(
                        "AUTOMATION_FAILURE:501",
                        ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE,
                        501L,
                        501L,
                        null,
                        "Компания",
                        "Повторная отправка счета · заказ #77",
                        "Ошибка автоматизации · 3",
                        "payment_instruction_failed",
                        "/orders",
                        null,
                        ClientMessageScenario.PAYMENT_INVOICE_RETRY,
                        3,
                        LocalDateTime.now().minusDays(1),
                        LocalDateTime.now().minusHours(1),
                        LocalDateTime.now().plusHours(1)
                );
        stubSuccessfulConcreteAction(concrete, parent);
        when(managerAutomationFailureService.findIssue(
                manager,
                ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE,
                501L
        )).thenReturn(Optional.of(issue), Optional.empty());
        when(scheduledClientMessageService.retryNow(501L)).thenReturn(
                new ScheduledClientMessageService.ManualRetryResult(
                        501L,
                        true,
                        ScheduledMessageStateStatus.DONE,
                        null,
                        null,
                        0,
                        null
                )
        );

        ManagerControlConcreteItemResponse response = service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.RESOLVED, concrete.getStatus());
        assertEquals("RESOLVED", response.itemStatus());
        assertTrue(concrete.getComment().contains("перезапущена"));
        verify(scheduledClientMessageService).retryNow(501L);
    }

    @Test
    void repairAutomationFailureWaitsForTelegramBindingWithoutRetrying() {
        Manager manager = new Manager();
        manager.setId(3L);
        ManagerDailyControl control = control();
        control.setManager(manager);
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(
                control,
                parent,
                ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE
        );
        concrete.setEntityId(501L);
        Company company = Company.builder()
                .id(77L)
                .title("Юпитер")
                .urlChat("https://t.me/+private")
                .manager(manager)
                .build();
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(501L)
                .companyId(77L)
                .scenario(ClientMessageScenario.ARCHIVE_REORDER_OFFER)
                .lastErrorCode("telegram_group_missing")
                .lastErrorMessage("Для Telegram-группы не задан chatId")
                .build();
        ManagerAutomationFailureService.AutomationFailureIssue issue =
                new ManagerAutomationFailureService.AutomationFailureIssue(
                        "AUTOMATION_FAILURE:501",
                        ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE,
                        501L,
                        501L,
                        null,
                        "Юпитер",
                        "Предложение повторного заказа",
                        "Ошибка автоматизации · 4",
                        "telegram_group_missing · Для Telegram-группы не задан chatId",
                        "/orders",
                        company.getUrlChat(),
                        ClientMessageScenario.ARCHIVE_REORDER_OFFER,
                        4,
                        LocalDateTime.now().minusHours(5),
                        LocalDateTime.now().minusMinutes(1),
                        LocalDateTime.now().plusDays(1)
                );

        when(dailyControlConcreteItemRepository.findById(concrete.getId())).thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(managerAutomationFailureService.findIssue(
                manager,
                ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE,
                501L
        )).thenReturn(Optional.of(issue));
        when(scheduledClientMessageStateRepository.findById(501L)).thenReturn(Optional.of(state));
        when(companyRepository.findById(77L)).thenReturn(Optional.of(company));
        when(telegramGroupLinkService.buildInviteUrl(company))
                .thenReturn("https://t.me/O_Company_Bot?startgroup=cSignedToken");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.repairConcreteItem(concrete.getId(), principal(), adminAuth())
        );

        assertEquals(409, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("Если бот уже добавлен"));
        verify(scheduledClientMessageService, never()).retryNow(anyLong());
    }

    @Test
    void repairCollectingInvoiceRemovesHealthyInProgressInvoiceFromRemarks() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "COMMON_INVOICE");
        concrete.setEntityId(88L);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(88L);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        var details = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse.class
        );
        var summary = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse.class
        );

        stubSuccessfulConcreteAction(concrete, parent);
        when(commonInvoiceRepository.findByIdWithAccount(88L)).thenReturn(Optional.of(invoice));
        when(commonBillingService.invoice(88L)).thenReturn(details);
        when(details.summary()).thenReturn(summary);
        when(details.orders()).thenReturn(List.of());
        when(summary.status()).thenReturn(CommonInvoiceStatus.COLLECTING.name());
        when(summary.readyOrders()).thenReturn(2);
        when(summary.totalOrders()).thenReturn(5);

        ManagerControlConcreteItemResponse response = service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals("RESOLVED", response.itemStatus());
        assertTrue(concrete.getComment().contains("3 из 5 заказов еще в работе"));
        assertTrue(concrete.getComment().contains("убрана из замечаний"));
        verify(commonBillingService, never()).sendInvoice(anyLong(), eq(true));
    }

    @Test
    void repairEmptyCollectingInvoiceDisablesTechnicalTail() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "COMMON_INVOICE");
        concrete.setEntityId(196L);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(196L);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        var collecting = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse.class
        );
        var collectingSummary = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse.class
        );
        var disabled = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse.class
        );
        var disabledSummary = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse.class
        );

        stubSuccessfulConcreteAction(concrete, parent);
        when(commonInvoiceRepository.findByIdWithAccount(196L)).thenReturn(Optional.of(invoice));
        when(commonBillingService.invoice(196L)).thenReturn(collecting);
        when(collecting.summary()).thenReturn(collectingSummary);
        when(collecting.orders()).thenReturn(List.of());
        when(collectingSummary.status()).thenReturn(CommonInvoiceStatus.COLLECTING.name());
        when(collectingSummary.totalOrders()).thenReturn(0);
        when(commonBillingService.disableEmptyInvoice(196L)).thenReturn(disabled);
        when(disabled.summary()).thenReturn(disabledSummary);
        when(disabledSummary.status()).thenReturn(CommonInvoiceStatus.DISABLED.name());

        ManagerControlConcreteItemResponse response = service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals("RESOLVED", response.itemStatus());
        assertTrue(concrete.getComment().contains("Пустой технический счет отключен"));
        verify(commonBillingService).disableEmptyInvoice(196L);
        verify(commonBillingService, never()).sendInvoice(anyLong(), eq(true));
    }

    @Test
    void repairWhatsappInvoiceTailDelegatesToLockedBillingOperationWithoutSavingDetachedInvoice() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "COMMON_INVOICE");
        concrete.setEntityId(88L);

        Company primaryCompany = new Company();
        primaryCompany.setId(501L);
        primaryCompany.setTitle("Компания");
        primaryCompany.setGroupId("120363501@g.us");
        CommonBillingAccount account = new CommonBillingAccount();
        account.setId(7L);
        account.setInvoiceCompany(primaryCompany);
        CommonInvoice detachedSnapshot = new CommonInvoice();
        detachedSnapshot.setId(88L);
        detachedSnapshot.setAccount(account);
        detachedSnapshot.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        detachedSnapshot.setLastError("whatsapp_group_missing: groupId отсутствовал при отправке");

        stubSuccessfulConcreteAction(concrete, parent);
        when(commonInvoiceRepository.findByIdWithAccount(88L)).thenReturn(Optional.of(detachedSnapshot));
        when(commonInvoiceOrderRepository.findByInvoiceIdWithOrders(88L)).thenReturn(List.of());

        ManagerControlConcreteItemResponse response = service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals("RESOLVED", response.itemStatus());
        verify(commonBillingService).resolveWhatsappGroupTail(88L);
        verify(commonInvoiceRepository, never()).save(any(CommonInvoice.class));
        assertEquals("whatsapp_group_missing: groupId отсутствовал при отправке", detachedSnapshot.getLastError());
    }

    @Test
    void commonInvoiceRepairSuspendsControlTransactionAndResolvesOnlyAfterSend() throws Exception {
        Transactional controlTransaction = ManagerControlService.class
                .getMethod("repairConcreteItem", Long.class, Principal.class, Authentication.class)
                .getAnnotation(Transactional.class);
        Transactional invoiceBoundary = ManagerControlInvoiceOperationExecutor.class
                .getMethod("execute", java.util.function.Supplier.class)
                .getAnnotation(Transactional.class);
        assertNotNull(controlTransaction);
        assertNotNull(invoiceBoundary);
        assertEquals(Propagation.REQUIRED, controlTransaction.propagation());
        assertEquals(Propagation.NOT_SUPPORTED, invoiceBoundary.propagation());

        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "COMMON_INVOICE");
        concrete.setEntityId(88L);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(88L);
        invoice.setStatus(CommonInvoiceStatus.READY);
        var readyDetails = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse.class
        );
        var readySummary = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse.class
        );
        var sentDetails = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse.class
        );
        var sentSummary = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse.class
        );

        stubSuccessfulConcreteAction(concrete, parent);
        when(commonInvoiceRepository.findByIdWithAccount(88L)).thenReturn(Optional.of(invoice));
        when(commonBillingService.invoice(88L)).thenReturn(readyDetails);
        when(readyDetails.summary()).thenReturn(readySummary);
        when(readySummary.status()).thenReturn(CommonInvoiceStatus.READY.name());
        when(commonBillingService.sendInvoice(88L, true)).thenReturn(sentDetails);
        when(sentDetails.summary()).thenReturn(sentSummary);
        when(sentSummary.status()).thenReturn(CommonInvoiceStatus.INVOICED.name());
        when(sentSummary.lastError()).thenReturn(null);

        ManagerControlConcreteItemResponse response = service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals("RESOLVED", response.itemStatus());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                commonBillingService,
                dailyControlConcreteItemRepository
        );
        order.verify(commonBillingService).invoice(88L);
        order.verify(commonBillingService).sendInvoice(88L, true);
        order.verify(dailyControlConcreteItemRepository).save(concrete);
    }

    @Test
    void repairUnsentTlsPaymentInitArchivesAttemptAndSendsFreshInvoice() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "COMMON_INVOICE");
        concrete.setEntityId(51L);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(51L);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError(CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_ERROR_CODE
                + ": certificate_unknown; PKIX path building failed");
        var recovered = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse.class
        );
        var recoveredSummary = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse.class
        );
        var sent = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse.class
        );
        var sentSummary = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse.class
        );

        stubSuccessfulConcreteAction(concrete, parent);
        when(commonInvoiceRepository.findByIdWithAccount(51L)).thenReturn(Optional.of(invoice));
        when(commonBillingService.recoverUnsentPaymentInitTlsFailure(51L)).thenReturn(recovered);
        when(recovered.summary()).thenReturn(recoveredSummary);
        when(recoveredSummary.status()).thenReturn(CommonInvoiceStatus.READY.name());
        when(commonBillingService.sendInvoice(51L, true)).thenReturn(sent);
        when(sent.summary()).thenReturn(sentSummary);
        when(sentSummary.status()).thenReturn(CommonInvoiceStatus.INVOICED.name());
        when(sentSummary.lastError()).thenReturn(null);

        ManagerControlConcreteItemResponse response = service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals("RESOLVED", response.itemStatus());
        assertTrue(concrete.getComment().contains("новая платежная ссылка создана"));
        verify(commonBillingService).recoverUnsentPaymentInitTlsFailure(51L);
        verify(commonBillingService).sendInvoice(51L, true);
    }

    @Test
    void repairUnsentTlsPaymentInitResendsRemainingBalanceWhenRecoveryIsPartiallyPaid() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "COMMON_INVOICE");
        concrete.setEntityId(51L);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(51L);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError(CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_ERROR_CODE
                + ": certificate_unknown; PKIX path building failed");
        var recovered = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse.class
        );
        var recoveredSummary = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse.class
        );
        var sent = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse.class
        );
        var sentSummary = org.mockito.Mockito.mock(
                com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse.class
        );

        stubSuccessfulConcreteAction(concrete, parent);
        when(commonInvoiceRepository.findByIdWithAccount(51L)).thenReturn(Optional.of(invoice));
        when(commonBillingService.recoverUnsentPaymentInitTlsFailure(51L)).thenReturn(recovered);
        when(recovered.summary()).thenReturn(recoveredSummary);
        when(recoveredSummary.status()).thenReturn(CommonInvoiceStatus.PARTIALLY_PAID.name());
        when(commonBillingService.sendInvoice(51L, true)).thenReturn(sent);
        when(sent.summary()).thenReturn(sentSummary);
        when(sentSummary.status()).thenReturn(CommonInvoiceStatus.PARTIALLY_PAID.name());
        when(sentSummary.lastError()).thenReturn(null);

        ManagerControlConcreteItemResponse response = service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals("RESOLVED", response.itemStatus());
        assertTrue(concrete.getComment().contains("повторно отправлен"));
        verify(commonBillingService).sendInvoice(51L, true);
    }

    @Test
    void tlsRepairAvailabilityUsesSameStandaloneProviderEvidenceAsExecutionGuard() throws Exception {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(51L);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError(CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_ERROR_CODE
                + ": certificate_unknown; PKIX path building failed");
        Order order = new Order();
        order.setId(101L);
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setOrder(order);

        PaymentLink initReserved = new PaymentLink();
        initReserved.setStatus(PaymentLinkStatus.REFUNDED);
        initReserved.setBankInitNonce("init-reservation");
        PaymentLink cancelReserved = new PaymentLink();
        cancelReserved.setStatus(PaymentLinkStatus.EXPIRED);
        cancelReserved.setBankCancelOriginStatus(PaymentLinkStatus.INITIATED);
        PaymentLink ambiguousCanceled = new PaymentLink();
        ambiguousCanceled.setStatus(PaymentLinkStatus.CANCELED);
        ambiguousCanceled.setTbankPaymentId("provider-payment-id");
        ambiguousCanceled.setLastError("cancel_result_unknown");

        assertTrue(StandaloneBankPaymentPolicy.blocksCommonInvoiceTlsRecovery(initReserved));
        assertTrue(StandaloneBankPaymentPolicy.blocksCommonInvoiceTlsRecovery(cancelReserved));
        assertTrue(StandaloneBankPaymentPolicy.blocksCommonInvoiceTlsRecovery(ambiguousCanceled));
        when(paymentLinkRepository.findByOrderIdInForRead(List.of(101L)))
                .thenReturn(List.of(cancelReserved));

        Method method = ManagerControlService.class.getDeclaredMethod(
                "commonInvoiceLastErrorReason",
                CommonInvoice.class,
                String.class,
                List.class
        );
        method.setAccessible(true);
        String reason = (String) method.invoke(service, invoice, invoice.getLastError(), List.of(item));

        assertTrue(reason.contains("отдельный незавершенный платеж"));
        assertFalse(reason.contains("нажмите «Починить»"));
        verify(paymentLinkRepository).findByOrderIdInForRead(List.of(101L));
    }

    @Test
    void standaloneRouteConflictReasonOffersGuardedAutomaticRepair() throws Exception {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(51L);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("standalone_payment_route_conflict: order=23293; link=940; status=CREATED");

        Method method = ManagerControlService.class.getDeclaredMethod(
                "commonInvoiceLastErrorReason",
                CommonInvoice.class,
                String.class,
                List.class
        );
        method.setAccessible(true);
        String reason = (String) method.invoke(service, invoice, invoice.getLastError(), List.of());

        assertTrue(reason.contains("Нажмите «Починить»"));
        assertTrue(reason.contains("сверит начатые платежи"));
        assertTrue(reason.contains("автоматическая починка остановится"));
    }

    @Test
    void repairTelegramChatBindingResolvesWhenSharedSyncFindsBinding() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        parent.setReasonCode("CHAT_BINDING_ISSUES");
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setEntityId(77L);
        concrete.setReason("Почему в контроле: Telegram-группа из ссылки не привязана к компании");
        Company company = new Company();
        company.setId(501L);
        company.setTitle("Тропа");
        company.setUrlChat("https://t.me/tropa_group");
        Order order = new Order();
        order.setId(77L);
        order.setCompany(company);
        stubSuccessfulConcreteAction(concrete, parent);
        when(orderRepository.findById(77L)).thenReturn(Optional.of(order));
        when(sharedChatLinkSyncService.syncSharedChatIds()).thenAnswer(invocation -> {
            company.setTelegramGroupChatId(-100501L);
            return null;
        });
        when(companyRepository.findById(501L)).thenReturn(Optional.of(company));

        ManagerControlConcreteItemResponse response = service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.RESOLVED, concrete.getStatus());
        assertEquals("RESOLVED", response.itemStatus());
        assertTrue(concrete.getComment().contains("Группа уже была привязана"));
        verify(sharedChatLinkSyncService).syncSharedChatIds();
    }

    @Test
    void repairWhatsAppChatBindingRunsTargetedRepairAndResolvesWhenBindingAppears() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        parent.setReasonCode("CHAT_BINDING_ISSUES");
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setEntityId(77L);
        concrete.setReason("Почему в контроле: WhatsApp-группа из ссылки не привязана к компании");
        Company company = new Company();
        company.setId(501L);
        company.setTitle("Gallery and more, Колодец дракона");
        company.setUrlChat("https://chat.whatsapp.com/GqLRY4e7slyOFKjoLjIBPa");
        Order order = new Order();
        order.setId(77L);
        order.setCompany(company);
        stubSuccessfulConcreteAction(concrete, parent);
        when(orderRepository.findById(77L)).thenReturn(Optional.of(order));
        when(whatsAppGroupLinkSyncService.repairCompanyLink(company)).thenAnswer(invocation -> {
            company.setGroupId("120363501@g.us");
            return new WhatsAppGroupLinkSyncService.WhatsAppGroupRepairResult(
                    true,
                    "WhatsApp-группа найдена у клиента whatsapp_lika"
            );
        });
        when(companyRepository.findById(501L)).thenReturn(Optional.of(company));

        ManagerControlConcreteItemResponse response = service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.RESOLVED, concrete.getStatus());
        assertEquals("RESOLVED", response.itemStatus());
        assertTrue(concrete.getComment().contains("WhatsApp-группа найдена"));
        verify(whatsAppGroupLinkSyncService).repairCompanyLink(company);
    }

    @Test
    void repairCompanyChatBindingResolvesWithoutSyncWhenCompanyIsBanned() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        parent.setReasonCode("CHAT_BINDING_ISSUES");
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "COMPANY_CHAT_BINDING");
        concrete.setEntityId(501L);
        concrete.setReason("Почему в контроле: WhatsApp-группа не привязана к компании");
        Company company = new Company();
        company.setId(501L);
        company.setTitle("Компания без связи");
        company.setUrlChat("https://chat.whatsapp.com/old-link");
        company.setStatus(CompanyStatus.builder().title("Бан").build());
        stubSuccessfulConcreteAction(concrete, parent);
        when(companyRepository.findById(501L)).thenReturn(Optional.of(company));

        ManagerControlConcreteItemResponse response = service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.RESOLVED, concrete.getStatus());
        assertEquals("RESOLVED", response.itemStatus());
        assertTrue(concrete.getComment().contains("статусе «Бан»"));
        verify(whatsAppGroupLinkSyncService, never()).repairCompanyLink(any());
        verify(sharedChatLinkSyncService, never()).syncSharedChatIds();
    }

    @Test
    void repairRegularOrderCardRestoresClientTextReminderForNewWaitingOrder() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setEntityId(25442L);
        concrete.setReason("Автоответчик не обработал заказ: нет записи в очереди CLIENT_TEXT_REMINDER");

        LocalDateTime waitingChangedAt = LocalDate.now()
                .minusDays(1)
                .atTime(14, 50, 19);
        Company company = new Company();
        company.setId(2155L);
        company.setTitle("Хэлп Девелопмент");
        company.setUrlChat("https://chat.whatsapp.com/test");
        company.setGroupId("120363000@g.us");
        OrderStatus newStatus = new OrderStatus();
        newStatus.setId(1L);
        newStatus.setTitle("Новый");
        Order order = new Order();
        order.setId(25442L);
        order.setCompany(company);
        order.setStatus(newStatus);
        order.setWaitingForClient(true);
        order.setWaitingForClientChangedAt(waitingChangedAt);

        ScheduledClientMessageState healthyState = ScheduledClientMessageState.builder()
                .id(4200L)
                .scenario(ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .targetKey("client-text:25442:" + waitingChangedAt)
                .orderId(25442L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(LocalDateTime.now().plusDays(1))
                .build();

        stubSuccessfulConcreteAction(concrete, parent);
        when(orderRepository.findById(25442L)).thenReturn(Optional.of(order));
        when(scheduledClientMessageStateRepository.findByOrderIdIn(List.of(25442L)))
                .thenReturn(List.of(healthyState));

        ManagerControlConcreteItemResponse response = service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.RESOLVED, concrete.getStatus());
        assertEquals("RESOLVED", response.itemStatus());
        assertTrue(concrete.getComment().contains("CLIENT_TEXT_REMINDER"));
        verify(scheduledClientMessageService).ensureClientTextReminderForOrder(order);
        verify(scheduledClientMessageService, never()).ensureOrderAutomationForOrder(order);
    }

    @Test
    void repairTelegramChatBindingFailsImmediatelyWhenBindingStillMissing() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        parent.setReasonCode("CHAT_BINDING_ISSUES");
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "ORDER");
        concrete.setEntityId(77L);
        concrete.setReason("Почему в контроле: Telegram-группа из ссылки не привязана к компании");
        Company company = new Company();
        company.setId(501L);
        company.setTitle("Тропа");
        company.setUrlChat("https://t.me/tropa_group");
        Order order = new Order();
        order.setId(77L);
        order.setCompany(company);
        when(dailyControlConcreteItemRepository.findById(concrete.getId())).thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(orderRepository.findById(77L)).thenReturn(Optional.of(order));
        when(companyRepository.findById(501L)).thenReturn(Optional.of(company));
        when(telegramGroupLinkService.buildInviteUrl(company))
                .thenReturn("https://t.me/O_Company_Bot?startgroup=c501_token");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.repairConcreteItem(
                concrete.getId(),
                principal(),
                adminAuth()
        ));

        assertEquals(409, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("Telegram-группа пока не привязана"));
        assertTrue(ex.getReason().contains("замените ссылку вручную"));
        verify(sharedChatLinkSyncService).syncSharedChatIds();
        verify(dailyControlConcreteItemRepository, never()).save(any());
    }

    @Test
    void closeDayIsBlockedWhenCriticalActionItemIsStillOpen() {
        ManagerDailyControl control = control();
        control.setStatus(ManagerDailyControlStatus.RED);
        ManagerDailyControlItem parent = actionParent(control);
        when(dailyControlRepository.findById(control.getId())).thenReturn(Optional.of(control));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlItemRepository.findByControl(control)).thenReturn(List.of(parent));
        when(dailyControlConcreteItemRepository.findByParentItemIn(any())).thenReturn(List.of());
        when(dailyControlEventRepository.findByControlOrderByCreatedAtDesc(control)).thenReturn(List.of());
        when(dailyControlRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dailyControlEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user()));

        ManagerControlCloseResponse response = service.closeDay(
                control.getId(),
                new ManagerControlCloseRequest("Пробую закрыть"),
                principal(),
                adminAuth()
        );

        assertFalse(response.closed());
        assertTrue(response.blockers().stream().anyMatch(blocker -> blocker.contains("Остались открытые пункты")));
        assertNull(control.getClosedAt());
    }

    @Test
    void closeControlIsBlockedWhenActionItemsExistButControlWasNotAccepted() {
        ManagerDailyControl control = controlReadyForClose();
        control.setMorningCompletedAt(null);
        ManagerDailyControlItem parent = actionParent(control);
        parent.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        parent.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        parent.setComment("Взято в работу");
        when(dailyControlRepository.findById(control.getId())).thenReturn(Optional.of(control));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlItemRepository.findByControl(control)).thenReturn(List.of(parent));

        ManagerControlCloseResponse response = service.closeDay(
                control.getId(),
                new ManagerControlCloseRequest("Пробую закрыть"),
                principal(),
                adminAuth()
        );

        assertFalse(response.closed());
        assertTrue(response.blockers().stream().anyMatch(blocker -> blocker.contains("Контроль не принят")));
        assertNull(control.getMorningCompletedAt());
    }

    @Test
    void dayStageIsBlockedUntilMorningStageIsCompleted() {
        ManagerDailyControl control = controlReadyForClose();
        control.setMorningCompletedAt(null);
        control.setDayCheckedAt(null);
        when(dailyControlRepository.findById(control.getId())).thenReturn(Optional.of(control));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlItemRepository.findByControl(control)).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.markStage(
                control.getId(),
                new ManagerControlStageRequest("DAY_CHECK", null),
                principal(),
                adminAuth()
        ));

        assertEquals(400, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("Сначала отметьте начало дня"));
        assertNull(control.getDayCheckedAt());
        verify(dailyControlRepository, never()).save(any());
        verify(dailyControlEventRepository, never()).save(any());
    }

    @Test
    void closeControlDoesNotRequireSeparateFinalStage() {
        ManagerDailyControl control = controlReadyForClose();
        control.setFinalCheckedAt(null);
        when(dailyControlRepository.findById(control.getId())).thenReturn(Optional.of(control));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlItemRepository.findByControl(control)).thenReturn(List.of());
        when(dailyControlRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dailyControlEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerControlCloseResponse response = service.closeDay(
                control.getId(),
                new ManagerControlCloseRequest("Закрываю"),
                principal(),
                adminAuth()
        );

        assertTrue(response.closed());
        assertNotNull(control.getClosedAt());
        assertNotNull(control.getFinalCheckedAt());
    }

    @Test
    void closeDayIsBlockedWhenCriticalAggregateWasHandledButConcreteCardIsOpen() {
        ManagerDailyControl control = controlReadyForClose();
        control.setStatus(ManagerDailyControlStatus.YELLOW);
        ManagerDailyControlItem parent = actionParent(control);
        parent.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        parent.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        parent.setComment("Проверили карточки");
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "BAD_REVIEW_TASK");
        concrete.setStatus(ManagerDailyControlItemStatus.OPEN);

        when(dailyControlRepository.findById(control.getId())).thenReturn(Optional.of(control));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlItemRepository.findByControl(control)).thenReturn(List.of(parent));
        when(dailyControlConcreteItemRepository.findByParentItemIn(any())).thenReturn(List.of(concrete));
        when(dailyControlEventRepository.findByControlOrderByCreatedAtDesc(control)).thenReturn(List.of());
        when(dailyControlRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dailyControlEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user()));

        ManagerControlCloseResponse response = service.closeDay(
                control.getId(),
                new ManagerControlCloseRequest("Пробую закрыть"),
                principal(),
                adminAuth()
        );

        assertFalse(response.closed());
        assertTrue(response.blockers().stream().anyMatch(blocker -> blocker.contains("Остались открытые карточки")));
        assertNull(control.getClosedAt());
    }

    @Test
    void syncManagerDetailsLoadsPublicationRemarksOnlyBeforeToday() {
        LocalDate today = LocalDate.now();
        LocalDate overdueDate = today.minusDays(1);
        Manager manager = managerWithWorker(11L, 21L);
        ManagerDailyControl control = control();
        control.setControlDate(today);
        control.setManager(manager);
        ManagerDailyControlItem publish = actionParent(control);
        publish.setItemKey("worker:publish");
        publish.setItemType(ManagerDailyControlItemType.WORKER_SECTION);
        publish.setSectionCode("publish");
        publish.setReasonCode("publish");
        publish.setLabel("Публикация");
        publish.setTargetUrl("/worker?section=publish");

        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(managerRepository.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
        when(dailyControlRepository.findByControlDateAndManager(today, manager)).thenReturn(Optional.of(control));
        when(dailyControlItemRepository.findByControl(control)).thenReturn(List.of(publish));
        when(dailyControlRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dailyControlConcreteItemRepository.findByControlAndFollowUpAtAfter(eq(control), any())).thenReturn(List.of());
        when(orderRepository.summarizeManagerControlOverdueOrdersByManager(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(List.of());
        when(reviewService.countOrdersByWorkerIdsAndStatusPublish(List.of(21L), overdueDate))
                .thenReturn(Map.of(21L, 1));
        when(reviewService.countOrdersByWorkerIdsAndStatusVigul(eq(List.of(21L)), any())).thenReturn(Map.of());
        when(reviewRepository.findManagerControlPublishReviewsByWorkerIds(eq(List.of(21L)), eq(overdueDate), any()))
                .thenReturn(List.of());

        service.syncManagerDetails(11L, principal(), adminAuth());

        verify(reviewService).countOrdersByWorkerIdsAndStatusPublish(List.of(21L), overdueDate);
        verify(reviewService, never()).countOrdersByWorkerIdsAndStatusPublish(List.of(21L), today);
        verify(reviewRepository).findManagerControlPublishReviewsByWorkerIds(eq(List.of(21L)), eq(overdueDate), any());
        verify(reviewRepository, never()).findManagerControlPublishReviewsByWorkerIds(eq(List.of(21L)), eq(today), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void overdueStatusesUseReviewCheckIntervalForMissingReviewReminderQueue() throws Exception {
        LocalDate today = LocalDate.of(2026, 7, 8);
        Manager manager = managerWithWorker(11L, 21L);
        OrderDTOList order = OrderDTOList.builder()
                .id(25100L)
                .status("На проверке")
                .companyTitle("22 Философа")
                .changed(today.minusDays(2))
                .build();

        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS,
                ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS
        )).thenReturn(2);
        when(dailyControlRepository.findByControlDateAndManager(today, manager)).thenReturn(Optional.empty());
        when(orderRepository.summarizeManagerControlOverdueOrdersByManager(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(List.of());
        when(orderService.getManagerControlOverdueOrdersByManager(
                eq(manager),
                eq(""),
                eq("На проверке"),
                eq(today.minusDays(2)),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq(0),
                eq(1),
                eq("desc")
        )).thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 1), 1));

        Method method = ManagerControlService.class.getDeclaredMethod("overdueStatuses", Manager.class, LocalDate.class);
        method.setAccessible(true);
        List<ManagerControlOverdueStatusResponse> statuses =
                (List<ManagerControlOverdueStatusResponse>) method.invoke(service, manager, today);

        assertEquals(1, statuses.size());
        assertEquals("На проверке", statuses.get(0).status());
        assertEquals(1, statuses.get(0).count());
        assertEquals(2, statuses.get(0).maxDays());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregateOverdueExamplesUseEachStatusSpecificCutoff() throws Exception {
        LocalDate today = LocalDate.of(2026, 7, 25);
        Manager manager = managerWithWorker(11L, 21L);
        OrderDTOList order = OrderDTOList.builder()
                .id(25101L)
                .status("На проверке")
                .companyTitle("Компания с заказом")
                .changed(today.minusDays(2))
                .clientMessageStatus(new ClientMessageOrderStatusResponse(
                        "scheduled",
                        "Ожидает отправки",
                        "wait",
                        "OTHER_SCENARIO",
                        "rate_limited",
                        "Следующий слот отправки",
                        null,
                        null,
                        LocalDateTime.of(2026, 7, 25, 15, 57),
                        1,
                        0
                ))
                .build();

        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS,
                ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS
        )).thenReturn(2);
        when(dailyControlRepository.findByControlDateAndManager(today, manager)).thenReturn(Optional.empty());
        when(orderRepository.summarizeManagerControlOverdueOrdersByManager(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(List.of());
        when(orderService.getManagerControlOverdueOrdersByManager(
                eq(manager), eq(""), eq("На проверке"), eq(today.minusDays(2)),
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(0), eq(1), eq("desc")
        )).thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 1), 1));
        when(orderService.getManagerControlOverdueOrdersByManager(
                eq(manager), eq(""), eq("На проверке"), eq(today.minusDays(2)),
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(0), eq(5), eq("desc")
        )).thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 5), 1));

        Method method = ManagerControlService.class.getDeclaredMethod(
                "overdueOrderExamples",
                Manager.class,
                String.class,
                LocalDate.class,
                int.class
        );
        method.setAccessible(true);
        List<ManagerControlConcreteItemResponse> examples =
                (List<ManagerControlConcreteItemResponse>) method.invoke(service, manager, "Все", today, 5);

        assertEquals(1, examples.size());
        assertEquals(25101L, examples.getFirst().entityId());
        assertEquals("Компания с заказом", examples.getFirst().title());
    }

    @Test
    @SuppressWarnings("unchecked")
    void overdueExamplesExcludeHealthyScheduledClientMessageQueue() throws Exception {
        LocalDate today = LocalDate.of(2026, 7, 25);
        Manager manager = managerWithWorker(11L, 21L);
        OrderDTOList order = OrderDTOList.builder()
                .id(25362L)
                .status("На проверке")
                .companyTitle("Галерея")
                .changed(today.minusDays(2))
                .clientMessageStatus(new ClientMessageOrderStatusResponse(
                        "scheduled",
                        "Ожидает отправки",
                        "wait",
                        "REVIEW_CHECK_REMINDER",
                        "rate_limited",
                        "Следующий слот отправки",
                        null,
                        null,
                        LocalDateTime.now().plusHours(1),
                        0,
                        0
                ))
                .build();

        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS,
                ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS
        )).thenReturn(2);
        when(orderService.getManagerControlOverdueOrdersByManager(
                eq(manager), eq(""), eq("На проверке"), eq(today.minusDays(2)),
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(0), eq(5), eq("desc")
        )).thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 5), 1));

        Method method = ManagerControlService.class.getDeclaredMethod(
                "overdueOrderExamples",
                Manager.class,
                String.class,
                LocalDate.class,
                int.class
        );
        method.setAccessible(true);
        List<ManagerControlConcreteItemResponse> examples =
                (List<ManagerControlConcreteItemResponse>) method.invoke(service, manager, "На проверке", today, 5);

        assertTrue(examples.isEmpty());
    }

    @Test
    void healthyScheduledClientMessageQueueIsNotAControlRemark() throws Exception {
        OrderDTOList order = OrderDTOList.builder()
                .id(25362L)
                .status("На проверке")
                .companyTitle("Галерея")
                .changed(LocalDate.of(2026, 7, 23))
                .clientMessageStatus(new ClientMessageOrderStatusResponse(
                        "scheduled",
                        "Ожидает отправки",
                        "wait",
                        "REVIEW_CHECK_REMINDER",
                        "rate_limited",
                        "Следующий слот отправки",
                        null,
                        null,
                        LocalDateTime.now().plusHours(1),
                        0,
                        0
                ))
                .build();

        Method method = ManagerControlService.class.getDeclaredMethod(
                "hasHealthyActiveClientMessageQueue",
                OrderDTOList.class
        );
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(service, order));
    }

    @Test
    void dueScheduledAttemptRemainsHealthyWhileItWaitsInTheActiveQueue() throws Exception {
        OrderDTOList order = OrderDTOList.builder()
                .id(25363L)
                .clientMessageStatus(new ClientMessageOrderStatusResponse(
                        "scheduled",
                        "Ожидает отправки",
                        "wait",
                        "REVIEW_CHECK_REMINDER",
                        "rate_limited",
                        "Следующий слот отправки",
                        null,
                        null,
                        LocalDateTime.now().minusMinutes(1),
                        0,
                        0
                ))
                .build();

        Method method = ManagerControlService.class.getDeclaredMethod(
                "hasHealthyActiveClientMessageQueue",
                OrderDTOList.class
        );
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(service, order));
    }

    @Test
    @SuppressWarnings("unchecked")
    void concreteSynchronizationDeduplicatesSameEntityKey() throws Exception {
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(71L);
        ManagerDailyControlItem parent = actionParent(control);
        parent.setId(578L);
        ManagerControlConcreteItemResponse duplicate = new ManagerControlConcreteItemResponse(
                null,
                "CLIENT_CHAT_UNANSWERED",
                1315L,
                "Компания",
                null,
                null,
                0L,
                "Сообщение клиента",
                "/admin/manager-control/11",
                null,
                null,
                null,
                null,
                "OPEN",
                null,
                null,
                null,
                null,
                null
        );
        when(dailyControlConcreteItemRepository.findByParentItemForUpdate(parent)).thenReturn(List.of());
        when(dailyControlConcreteItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Method method = ManagerControlService.class.getDeclaredMethod(
                "syncConcreteExamples",
                ManagerDailyControlItem.class,
                List.class
        );
        method.setAccessible(true);
        List<ManagerControlConcreteItemResponse> synced =
                (List<ManagerControlConcreteItemResponse>) method.invoke(
                        service,
                        parent,
                        List.of(duplicate, duplicate)
                );

        assertEquals(1, synced.size());
        verify(dailyControlConcreteItemRepository, times(1)).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void concreteSynchronizationAutomaticallyClosesStoredOrderWhenHealthyQueueRemovesItFromFreshExamples() throws Exception {
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(72L);
        ManagerDailyControlItem parent = actionParent(control);
        parent.setId(579L);
        parent.setItemType(ManagerDailyControlItemType.ORDER_STATUS);

        ManagerDailyControlConcreteItem stale = concrete(control, parent, "ORDER");
        stale.setStatus(ManagerDailyControlItemStatus.OPEN);
        when(dailyControlConcreteItemRepository.findByParentItemForUpdate(parent)).thenReturn(List.of(stale));
        when(dailyControlConcreteItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Method method = ManagerControlService.class.getDeclaredMethod(
                "syncConcreteExamples",
                ManagerDailyControlItem.class,
                List.class
        );
        method.setAccessible(true);
        List<ManagerControlConcreteItemResponse> synced =
                (List<ManagerControlConcreteItemResponse>) method.invoke(service, parent, List.of());

        assertTrue(synced.isEmpty());
        assertEquals(ManagerDailyControlItemStatus.RESOLVED, stale.getStatus());
        assertEquals(ManagerDailyControlActionType.RESOLVED, stale.getActionType());
        assertTrue(stale.isAutomaticResolution());
        assertEquals("Проблема больше не актуальна и закрыта автоматически", stale.getComment());
        verify(dailyControlConcreteItemRepository).save(stale);
    }

    @Test
    @SuppressWarnings("unchecked")
    void concreteSynchronizationReopensResolvedCardWhenProblemIsStillActive() throws Exception {
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(75L);
        ManagerDailyControlItem parent = actionParent(control);
        parent.setId(607L);

        ManagerDailyControlConcreteItem resolved = concrete(control, parent, "PUBLICATION_DATE_REVIEW");
        resolved.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        resolved.setActionType(ManagerDailyControlActionType.RESOLVED);
        resolved.setResolvedAt(LocalDateTime.now());
        resolved.setAutomaticResolution(true);

        ManagerControlConcreteItemResponse active = new ManagerControlConcreteItemResponse(
                null,
                resolved.getEntityType(),
                resolved.getEntityId(),
                resolved.getTitle(),
                resolved.getSubtitle(),
                "Нет даты публикации",
                0L,
                "Проблема всё ещё актуальна",
                "/admin/orders/25588",
                null,
                null,
                null,
                null,
                "OPEN",
                null,
                null,
                null,
                null,
                null
        );
        when(dailyControlConcreteItemRepository.findByParentItemForUpdate(parent))
                .thenReturn(List.of(resolved));
        when(dailyControlConcreteItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Method method = ManagerControlService.class.getDeclaredMethod(
                "syncConcreteExamples",
                ManagerDailyControlItem.class,
                List.class
        );
        method.setAccessible(true);
        List<ManagerControlConcreteItemResponse> synced =
                (List<ManagerControlConcreteItemResponse>) method.invoke(service, parent, List.of(active));

        assertEquals(1, synced.size());
        assertEquals(ManagerDailyControlItemStatus.OPEN, resolved.getStatus());
        assertEquals("OPEN", synced.getFirst().itemStatus());
        assertNull(resolved.getActionType());
        assertNull(resolved.getResolvedAt());
        verify(dailyControlConcreteItemRepository).save(resolved);
    }

    @Test
    @SuppressWarnings("unchecked")
    void concreteSynchronizationReopensDeferredUnansweredCardWhileMessageIsStillActive() throws Exception {
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(76L);
        ManagerDailyControlItem parent = actionParent(control);
        parent.setId(608L);
        parent.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        parent.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        parent.setComment("Все конкретные карточки внутри пункта обработаны");

        ManagerDailyControlConcreteItem deferred = concrete(control, parent, "CLIENT_CHAT_UNANSWERED");
        deferred.setStatus(ManagerDailyControlItemStatus.DEFERRED);
        deferred.setActionType(ManagerDailyControlActionType.DEFERRED);
        deferred.setComment("Ответ был в личных сообщениях");
        deferred.setFollowUpAt(LocalDateTime.now().plusDays(1));

        ManagerControlConcreteItemResponse active = new ManagerControlConcreteItemResponse(
                null,
                deferred.getEntityType(),
                deferred.getEntityId(),
                deferred.getTitle(),
                deferred.getSubtitle(),
                "Без ответа",
                0L,
                "А мы начали работать с вами?",
                "/admin/manager-control/3",
                null,
                null,
                null,
                null,
                "OPEN",
                null,
                null,
                null,
                null,
                null
        );
        when(dailyControlConcreteItemRepository.findByParentItemForUpdate(parent))
                .thenReturn(List.of(deferred));
        when(dailyControlConcreteItemRepository.findByParentItem(parent))
                .thenReturn(List.of(deferred));
        when(dailyControlConcreteItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dailyControlItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Method method = ManagerControlService.class.getDeclaredMethod(
                "syncConcreteExamples",
                ManagerDailyControlItem.class,
                List.class
        );
        method.setAccessible(true);
        List<ManagerControlConcreteItemResponse> synced =
                (List<ManagerControlConcreteItemResponse>) method.invoke(service, parent, List.of(active));

        assertEquals(1, synced.size());
        assertEquals(ManagerDailyControlItemStatus.OPEN, deferred.getStatus());
        assertNull(deferred.getActionType());
        assertNull(deferred.getFollowUpAt());
        assertEquals("Ответ был в личных сообщениях", deferred.getComment());
        assertEquals(ManagerDailyControlItemStatus.OPEN, parent.getStatus());
        assertNull(parent.getActionType());
        assertNull(parent.getComment());
        assertEquals("OPEN", synced.getFirst().itemStatus());
        verify(dailyControlConcreteItemRepository).save(deferred);
        verify(dailyControlItemRepository).save(parent);
    }

    @Test
    void auditCardCanSendCorrectiveReplyAndClose() {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "CLIENT_CHAT_AUDIT");
        ClientChatUnansweredItem audited = new ClientChatUnansweredItem();
        audited.setId(concrete.getEntityId());
        audited.setStatus(ClientChatUnansweredStatus.ANSWERED);
        audited.setAuditRequired(true);
        audited.setPlatform(com.hunt.otziv.client_chat_control.model.ClientChatPlatform.WHATSAPP);
        audited.setChatId("12001@g.us");
        stubSuccessfulConcreteAction(concrete, parent);
        when(clientChatUnansweredItemRepository.findById(concrete.getEntityId()))
                .thenReturn(Optional.of(audited));
        when(clientChatMessageSender.sendToPlatform(
                any(), any(), any(), any(), any(), any()
        )).thenReturn(ClientMessageSendResult.sent("WhatsApp"));

        ManagerControlConcreteItemResponse response = service.replyToClientMessage(
                concrete.getId(),
                new ManagerControlClientReplyRequest("Исправление проверено, сообщаем результат"),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.RESOLVED.name(), response.itemStatus());
        assertEquals(ManagerDailyControlActionType.RESOLVED.name(), response.actionType());
        verify(clientChatMessageTrackerService).markAuditReplySent(
                audited.getId(),
                1L,
                "Исправление проверено, сообщаем результат",
                "WhatsApp"
        );
    }

    private void stubSuccessfulConcreteAction(
            ManagerDailyControlConcreteItem concrete,
            ManagerDailyControlItem parent
    ) {
        when(dailyControlConcreteItemRepository.findById(concrete.getId())).thenReturn(Optional.of(concrete));
        lenient().when(dailyControlConcreteItemRepository.findByIdForUpdate(concrete.getId()))
                .thenReturn(Optional.of(concrete));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(dailyControlConcreteItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dailyControlConcreteItemRepository.findByParentItem(parent)).thenReturn(List.of(concrete));
        when(dailyControlItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dailyControlItemRepository.findByControl(concrete.getControl())).thenReturn(List.of(parent));
        when(dailyControlRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dailyControlEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user()));
    }

    private ManagerDailyControl control() {
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(10L);
        control.setControlDate(LocalDate.now());
        control.setStatus(ManagerDailyControlStatus.RED);
        return control;
    }

    private ManagerDailyControl controlReadyForClose() {
        ManagerDailyControl control = control();
        LocalDateTime now = LocalDateTime.now();
        control.setStartedAt(now.minusHours(8));
        control.setMorningStartedAt(now.minusHours(8));
        control.setMorningCompletedAt(now.minusHours(7));
        control.setDayCheckedAt(now.minusHours(3));
        control.setFinalCheckedAt(now.minusMinutes(10));
        return control;
    }

    private ManagerDailyControlEvent event(
            ManagerDailyControlItem item,
            Long actorUserId,
            ManagerDailyControlActionType actionType
    ) {
        ManagerDailyControlEvent event = new ManagerDailyControlEvent();
        event.setItem(item);
        event.setActorUserId(actorUserId);
        event.setEventType(ManagerDailyControlEventType.ITEM_ACTION);
        event.setActionType(actionType);
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }

    private ManagerDailyControlItem actionParent(ManagerDailyControl control) {
        ManagerDailyControlItem item = new ManagerDailyControlItem();
        item.setId(20L);
        item.setControl(control);
        item.setItemKey("worker:bad");
        item.setItemType(ManagerDailyControlItemType.WORKER_SECTION);
        item.setReasonCode("BAD_REVIEWS");
        item.setLabel("Плохие");
        item.setCount(1L);
        item.setSeverity(ManagerDailyControlSeverity.CRITICAL);
        item.setGroup(ManagerDailyControlGroup.ACTION);
        item.setStatus(ManagerDailyControlItemStatus.OPEN);
        return item;
    }

    private ManagerDailyControlConcreteItem concrete(
            ManagerDailyControl control,
            ManagerDailyControlItem parent,
            String entityType
    ) {
        ManagerDailyControlConcreteItem item = new ManagerDailyControlConcreteItem();
        item.setId(30L);
        item.setControl(control);
        item.setParentItem(parent);
        item.setEntityKey(entityType + ":30");
        item.setEntityType(entityType);
        item.setEntityId(30L);
        item.setTitle("Проблемная карточка");
        item.setSubtitle("Нужна проверка менеджера");
        item.setReason("Требует внимания");
        item.setStatus(ManagerDailyControlItemStatus.OPEN);
        return item;
    }

    private User user() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        return user;
    }

    private Manager managerWithWorker(Long managerId, Long workerId) {
        User user = user();
        user.setFio("Менеджер");
        Worker worker = new Worker();
        worker.setId(workerId);
        User workerUser = new User();
        workerUser.setId(workerId + 1000);
        workerUser.setUsername("worker" + workerId);
        worker.setUser(workerUser);
        user.setWorkers(Set.of(worker));
        Manager manager = new Manager();
        manager.setId(managerId);
        manager.setUser(user);
        return manager;
    }

    private Principal principal() {
        return () -> "admin";
    }

    private Authentication adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                "admin",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }
}
