package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.service.ClientMessageOrderStatusService;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlItemActionRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlStageRequest;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
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
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    private ManagerPermissionService managerPermissionService;
    @Mock
    private PersonalReminderService personalReminderService;
    @Mock
    private TelegramService telegramService;
    @Mock
    private OrderService orderService;
    @Mock
    private ClientMessageOrderStatusService clientMessageOrderStatusService;
    @Mock
    private ScheduledClientMessageService scheduledClientMessageService;
    @Mock
    private ScheduledClientMessageStateRepository scheduledClientMessageStateRepository;
    @Mock
    private ClientChatMessageSender clientChatMessageSender;
    @Mock
    private ClientChatMessageTrackerService clientChatMessageTrackerService;
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
    private CommonInvoiceRepository commonInvoiceRepository;
    @Mock
    private CommonInvoiceOrderRepository commonInvoiceOrderRepository;
    @Mock
    private CommonBillingService commonBillingService;
    @Mock
    private WorkerRiskIncidentRepository riskIncidentRepository;
    @Mock
    private ManagerDailyControlRepository dailyControlRepository;
    @Mock
    private ManagerDailyControlItemRepository dailyControlItemRepository;
    @Mock
    private ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;
    @Mock
    private ManagerDailyControlEventRepository dailyControlEventRepository;

    @InjectMocks
    private ManagerControlService service;

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
        when(telegramService.sendMessageWithInlineKeyboard(eq(-100123L), any(), any(), any())).thenReturn(true);

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
        verify(telegramService).sendMessageWithInlineKeyboard(eq(-100123L), messageCaptor.capture(), any(), any());
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
    void commonInvoiceConcreteActionIsRecordedWithoutOrderStatusSideEffects() throws Exception {
        ManagerDailyControl control = control();
        ManagerDailyControlItem parent = actionParent(control);
        ManagerDailyControlConcreteItem concrete = concrete(control, parent, "COMMON_INVOICE");
        concrete.setEntityId(88L);
        stubSuccessfulConcreteAction(concrete, parent);

        ManagerControlConcreteItemResponse response = service.actionConcreteItem(
                concrete.getId(),
                new ManagerControlItemActionRequest("ACTION_TAKEN", "Ошибка счета показана в правой панели", null),
                principal(),
                adminAuth()
        );

        assertEquals(ManagerDailyControlItemStatus.ACTION_TAKEN, concrete.getStatus());
        assertEquals("ACTION_TAKEN", response.itemStatus());
        assertEquals("Ошибка счета показана в правой панели", concrete.getComment());
        verify(orderRepository, never()).findById(any());
        verify(orderService, never()).changeStatusForOrder(any(), any());
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

    private void stubSuccessfulConcreteAction(
            ManagerDailyControlConcreteItem concrete,
            ManagerDailyControlItem parent
    ) {
        when(dailyControlConcreteItemRepository.findById(concrete.getId())).thenReturn(Optional.of(concrete));
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
