package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.client_chat_control.dto.ClientChatUnansweredExample;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.service.ClientMessageOrderStatusService;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.manager_control.dto.ManagerControlClientReplyRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlEventResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlItemActionRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlItemDetailResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlManagerDetailResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlManagerResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlOverdueStatusResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlProblemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlSectionResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlStageRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlSummaryResponse;
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
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.t_telegrambot.service.TelegramChatMigrationResult;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.springframework.http.HttpStatus;

import java.security.Principal;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerControlService {

    private static final int DETAIL_EXAMPLE_LIMIT = 5;
    private static final int MANUAL_FOLLOW_UP_DAYS = 2;
    private static final int WORKER_TASK_FOLLOW_UP_HOURS = 3;
    private static final int OVERDUE_NOTIFICATION_DAYS = 4;
    private static final int WORKER_ORDER_UNCHANGED_DAYS = 2;
    private static final int COMMON_INVOICE_STALE_DAYS = 3;
    private static final LocalTime MORNING_STAGE_START = LocalTime.of(5, 0);
    private static final LocalTime START_DAY_DEADLINE = LocalTime.of(14, 0);
    private static final LocalTime FINAL_STAGE_START = LocalTime.of(20, 0);
    private static final String SOURCE_CONTROL_OWNER = "MANAGER_CONTROL_OWNER";
    private static final String SOURCE_WORKER_TASK_REQUEST = "MANAGER_CONTROL_WORKER_TASK_REQUEST";
    private static final String ENTITY_PUBLISH_REVIEW = "PUBLISH_REVIEW";
    private static final String ENTITY_NAGUL_REVIEW = "NAGUL_REVIEW";
    private static final String ENTITY_WORKER_ORDER_NEW = "WORKER_ORDER_NEW";
    private static final String ENTITY_WORKER_ORDER_CORRECT = "WORKER_ORDER_CORRECT";
    private static final String ENTITY_TELEGRAM_CHAT = "TELEGRAM_CHAT";
    private static final String ENTITY_CLIENT_CHAT_UNANSWERED = "CLIENT_CHAT_UNANSWERED";
    private static final Set<String> OVERDUE_IGNORED_STATUSES = Set.of(
            "Оплачено",
            "Архив",
            "Публикация",
            "Не оплачено",
            "Бан"
    );
    private static final List<String> ORDER_ATTENTION_STATUSES = List.of(
            "Новый",
            "В проверку",
            "На проверке",
            "Коррекция",
            "Публикация",
            "Выставлен счет",
            "Напоминание",
            "Требует внимания",
            "Не оплачено"
    );
    private static final List<String> ORDER_STATUS_DISPLAY_ORDER = List.of(
            "Новый",
            "В проверку",
            "На проверке",
            "Коррекция",
            "Публикация",
            "Опубликовано",
            "Ожидает общего счета",
            "Выставлен счет",
            "Напоминание",
            "Требует внимания",
            "Не оплачено",
            "Бан"
    );
    private static final Set<String> PAYMENT_AUTOMATION_STATUSES = Set.of(
            "Опубликовано",
            "Выставлен счет",
            "Напоминание",
            "Не оплачено"
    );
    private static final Set<String> MANUAL_CONTACT_ORDER_STATUSES = Set.of(
            "Новый",
            "На проверке",
            "Опубликовано",
            "Выставлен счет",
            "Напоминание",
            "Не оплачено"
    );
    private static final String ORDER_STATUS_TO_PAY = "Выставлен счет";
    private static final String ORDER_STATUS_REMINDER = "Напоминание";
    private static final Set<ClientMessageScenario> PAYMENT_AUTOMATION_SCENARIOS = Set.of(
            ClientMessageScenario.PAYMENT_INVOICE_RETRY,
            ClientMessageScenario.PAYMENT_REMINDER,
            ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION
    );
    private static final Set<String> REVIEW_CHECK_AUTOMATION_STATUSES = Set.of("На проверке");
    private static final Set<ClientMessageScenario> REVIEW_CHECK_SCENARIOS = Set.of(
            ClientMessageScenario.REVIEW_CHECK_REMINDER
    );
    private static final Set<String> DELIVERY_RETRY_AUTOMATION_STATUSES = Set.of("В проверку");
    private static final Set<ClientMessageScenario> DELIVERY_RETRY_SCENARIOS = Set.of(
            ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY
    );
    private static final Set<String> CLIENT_TEXT_AUTOMATION_STATUSES = Set.of("Новый");
    private static final Set<ClientMessageScenario> CLIENT_TEXT_SCENARIOS = Set.of(
            ClientMessageScenario.CLIENT_TEXT_REMINDER
    );
    private static final Set<CommonInvoiceStatus> COMMON_INVOICE_CONTROL_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID,
            CommonInvoiceStatus.NEEDS_ATTENTION,
            CommonInvoiceStatus.UNPAID,
            CommonInvoiceStatus.BAN
    );
    private static final Set<CommonInvoiceStatus> COMMON_INVOICE_CRITICAL_STATUSES = Set.of(
            CommonInvoiceStatus.NEEDS_ATTENTION,
            CommonInvoiceStatus.UNPAID,
            CommonInvoiceStatus.BAN
    );
    private static final Set<CommonInvoiceStatus> COMMON_INVOICE_STALE_STATUSES = Set.of(
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );
    private final ManagerRepository managerRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ManagerPermissionService managerPermissionService;
    private final PersonalReminderService personalReminderService;
    private final TelegramService telegramService;
    private final OrderService orderService;
    private final ClientMessageOrderStatusService clientMessageOrderStatusService;
    private final ScheduledClientMessageService scheduledClientMessageService;
    private final ScheduledClientMessageStateRepository scheduledClientMessageStateRepository;
    private final ClientChatMessageSender clientChatMessageSender;
    private final ClientChatMessageTrackerService clientChatMessageTrackerService;
    private final ClientChatUnansweredItemRepository clientChatUnansweredItemRepository;
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final CompanyRepository companyRepository;
    private final PaymentLinkRepository paymentLinkRepository;
    private final CommonInvoiceRepository commonInvoiceRepository;
    private final CommonInvoiceOrderRepository commonInvoiceOrderRepository;
    private final CommonBillingService commonBillingService;
    private final WorkerRiskIncidentRepository riskIncidentRepository;
    private final ManagerDailyControlRepository dailyControlRepository;
    private final ManagerDailyControlItemRepository dailyControlItemRepository;
    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;
    private final ManagerDailyControlEventRepository dailyControlEventRepository;

    @Transactional
    public ManagerControlSummaryResponse today(Principal principal, Authentication authentication) {
        reconcileClientMessagesForControl();
        LocalDate today = LocalDate.now();
        List<ManagerControlManagerResponse> managers = visibleManagers(principal, authentication).stream()
                .map(manager -> managerControl(manager, today))
                .sorted(Comparator
                        .comparingInt((ManagerControlManagerResponse manager) -> statusRank(manager.status()))
                        .thenComparing(ManagerControlManagerResponse::totalAttentionCount, Comparator.reverseOrder())
                        .thenComparing(ManagerControlManagerResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        long green = managers.stream().filter(manager -> "GREEN".equals(manager.status())).count();
        long yellow = managers.stream().filter(manager -> "YELLOW".equals(manager.status())).count();
        long red = managers.stream().filter(manager -> "RED".equals(manager.status())).count();
        long critical = managers.stream().mapToLong(ManagerControlManagerResponse::criticalCount).sum();
        long warning = managers.stream().mapToLong(ManagerControlManagerResponse::warningCount).sum();
        long workload = managers.stream().mapToLong(ManagerControlManagerResponse::workloadCount).sum();
        long attention = managers.stream().mapToLong(ManagerControlManagerResponse::totalAttentionCount).sum();

        return new ManagerControlSummaryResponse(
                today,
                LocalDateTime.now(),
                true,
                false,
                managers.size(),
                green,
                yellow,
                red,
                critical,
                warning,
                workload,
                attention,
                managers
        );
    }

    private void reconcileClientMessagesForControl() {
        if (scheduledClientMessageService == null) {
            return;
        }
        try {
            scheduledClientMessageService.reconcileCandidatesNow();
        } catch (Exception e) {
            log.warn("Не удалось досоздать очередь клиентских сообщений перед контролем менеджеров", e);
        }
    }

    @Scheduled(fixedDelay = 600_000L, initialDelay = 120_000L)
    @Transactional
    public void runTestModeNotifications() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        List<Manager> managers = managerRepository.findAllWithUserAndImage();
        if (managers.isEmpty()) {
            return;
        }
        managers = managerRepository.findAllManagersWorkers(managers);
        for (Manager manager : managers) {
            managerControl(manager, today);
            dailyControlRepository.findByControlDateAndManager(today, manager)
                    .ifPresent(control -> sendOverdueStageNotifications(control, now, false));
        }
        if (!now.toLocalTime().isBefore(MORNING_STAGE_START)) {
            LocalDate previousDay = today.minusDays(1);
            for (Manager manager : managers) {
                dailyControlRepository.findByControlDateAndManager(previousDay, manager)
                        .ifPresent(control -> sendOverdueStageNotifications(control, now, true));
            }
        }
    }

    private void sendOverdueStageNotifications(ManagerDailyControl control, LocalDateTime now, boolean previousDayOnly) {
        List<ManagerDailyControlItem> items = activeControlItems(dailyControlItemRepository.findByControl(control));
        long openAction = items.stream().filter(this::isOpenActionItem).count();
        if (!previousDayOnly
                && !now.toLocalTime().isBefore(START_DAY_DEADLINE)
                && control.getMorningCompletedAt() == null
                && control.getMorningNotificationSentAt() == null) {
            control.setMorningNotificationSentAt(now);
            String text = overdueStageText(control, "начало дня", "14:00", openAction);
            saveEvent(control, null, null, ManagerDailyControlEventType.TEST_NOTIFICATION, null, text);
            notifyOwners(control, "Просрочено начало дня", text);
        }
        if ((previousDayOnly || control.getControlDate().isBefore(now.toLocalDate()))
                && !now.toLocalTime().isBefore(MORNING_STAGE_START)
                && control.getFinalCheckedAt() == null
                && control.getEveningNotificationSentAt() == null) {
            control.setEveningNotificationSentAt(now);
            String text = overdueStageText(control, "конец дня", "05:00", openAction);
            saveEvent(control, null, null, ManagerDailyControlEventType.TEST_NOTIFICATION, null, text);
            notifyOwners(control, "Просрочен конец дня", text);
        }
        dailyControlRepository.save(control);
    }

    private String overdueStageText(ManagerDailyControl control, String stageName, String deadline, long openAction) {
        return "Просрочен " + stageName
                + ": " + managerName(control.getManager())
                + ", дата " + control.getControlDate()
                + ", дедлайн " + deadline
                + ", открытых пунктов " + openAction;
    }

    private void notifyOwners(ManagerDailyControl control, String title, String text) {
        List<User> recipients = new ArrayList<>();
        recipients.addAll(userRepository.findAllOwners("ROLE_OWNER"));
        recipients.addAll(userRepository.findAllOwners("ROLE_ADMIN"));
        recipients.stream()
                .filter(Objects::nonNull)
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(User::getId, Function.identity(), (left, right) -> left))
                .values()
                .forEach(user -> {
                    createReminder(user, title, text, SOURCE_CONTROL_OWNER, control.getId());
                    if (user.getTelegramChatId() != null) {
                        telegramService.sendMessage(user.getTelegramChatId(), title + "\n" + text);
                    }
                });
    }

    private void createReminder(User user, String title, String text, String sourceType, Long sourceId) {
        if (user == null || user.getId() == null || sourceId == null) {
            return;
        }
        if (personalReminderService.hasOpenSystemReminder(user, sourceType, sourceId)) {
            return;
        }
        personalReminderService.createSystemReminderDueNow(user, title, text, sourceType, sourceId, null);
    }

    @Transactional
    public void actionItem(Long itemId, ManagerControlItemActionRequest request, Principal principal, Authentication authentication) {
        if (itemId == null || itemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный пункт контроля");
        }
        ManagerDailyControlItem item = dailyControlItemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пункт контроля не найден"));
        ManagerDailyControl control = item.getControl();
        requireControlAccess(control, principal, authentication);
        rejectAggregateActionForConcreteItem(item);

        ManagerDailyControlActionType actionType = parseActionType(request == null ? null : request.actionType());
        String comment = limit(request == null ? null : request.comment(), 1000);
        requireCommentIfNeeded(item, actionType, comment);
        ManagerDailyControlItemStatus status = itemStatusForAction(actionType);
        item.setStatus(status);
        item.setActionType(actionType);
        item.setComment(comment);
        item.setResolvedAt(status == ManagerDailyControlItemStatus.RESOLVED ? LocalDateTime.now() : null);
        dailyControlItemRepository.save(item);

        if (control.getStartedAt() == null) {
            control.setStartedAt(LocalDateTime.now());
        }
        control.setLastActivityAt(LocalDateTime.now());
        control.setStatus(recalculateControlStatus(control));
        dailyControlRepository.save(control);

        saveEvent(
                control,
                item,
                actorUserId(principal),
                status == ManagerDailyControlItemStatus.RESOLVED
                        ? ManagerDailyControlEventType.ITEM_RESOLVED
                        : ManagerDailyControlEventType.ITEM_ACTION,
                actionType,
                item.getComment()
        );
    }

    @Transactional
    public ManagerControlConcreteItemResponse actionConcreteItem(Long concreteItemId, ManagerControlItemActionRequest request, Principal principal, Authentication authentication) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findById(concreteItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        ManagerDailyControl control = concreteItem.getControl();
        requireControlAccess(control, principal, authentication);

        ManagerDailyControlActionType actionType = parseActionType(request == null ? null : request.actionType());
        requireConcreteActionAllowed(concreteItem, actionType);
        ManagerDailyControlItemStatus status = itemStatusForAction(actionType);
        String comment = limit(request == null ? null : request.comment(), 1000);
        boolean manualWorkerNotification = Boolean.TRUE.equals(request == null ? null : request.manualWorkerNotification());
        boolean specialistActionConcrete = isSpecialistActionConcrete(concreteItem);
        if (manualWorkerNotification && specialistActionConcrete && safe(comment).isBlank()) {
            comment = manualWorkerNotificationComment(concreteItem);
        }
        requireCommentIfNeeded(concreteItem.getParentItem(), actionType, comment);
        LocalDateTime now = LocalDateTime.now();
        concreteItem.setComment(comment);
        if (specialistActionConcrete
                && status != ManagerDailyControlItemStatus.RESOLVED
                && actionType == ManagerDailyControlActionType.ACTION_TAKEN
                && !notifyWorkerAboutTaskRequest(concreteItem, control)) {
            concreteItem.setStatus(ManagerDailyControlItemStatus.OPEN);
            concreteItem.setActionType(null);
            concreteItem.setResolvedAt(null);
            concreteItem.setFollowUpAt(null);
            concreteItem.setLastManualTouchAt(now);
            ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);
            control.setLastActivityAt(now);
            dailyControlRepository.save(control);
            return concreteItemResponse(savedConcreteItem);
        }
        concreteItem.setStatus(status);
        concreteItem.setActionType(actionType);
        concreteItem.setResolvedAt(status == ManagerDailyControlItemStatus.RESOLVED ? now : null);
        boolean movedToReminder = false;
        if ("ORDER".equals(concreteItem.getEntityType()) && status != ManagerDailyControlItemStatus.RESOLVED) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(now.plusDays(MANUAL_FOLLOW_UP_DAYS));
            if (actionType == ManagerDailyControlActionType.ACTION_TAKEN) {
                movedToReminder = movePaymentOrderToReminderAfterManualSend(concreteItem);
            }
        } else if (ENTITY_CLIENT_CHAT_UNANSWERED.equals(concreteItem.getEntityType())) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(null);
            clientChatMessageTrackerService.markFromManagerControl(concreteItem.getEntityId(), actionType, comment);
        } else if (specialistActionConcrete
                && status != ManagerDailyControlItemStatus.RESOLVED
                && actionType != ManagerDailyControlActionType.ACKNOWLEDGED) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(workerTaskFollowUpAt(now));
        } else if (status == ManagerDailyControlItemStatus.RESOLVED) {
            concreteItem.setFollowUpAt(null);
            concreteItem.setLastManualTouchAt(now);
        }
        ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);

        updateParentItemFromConcreteItems(savedConcreteItem.getParentItem());

        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        control.setStatus(recalculateControlStatus(control));
        dailyControlRepository.save(control);

        String eventComment = "Карточка: " + concreteItem.getTitle()
                + (movedToReminder ? ". Статус заказа переведен в Напоминание" : "")
                + (concreteItem.getComment() == null || concreteItem.getComment().isBlank()
                ? ""
                : ". " + concreteItem.getComment());
        saveEvent(
                control,
                savedConcreteItem.getParentItem(),
                actorUserId(principal),
                status == ManagerDailyControlItemStatus.RESOLVED
                        ? ManagerDailyControlEventType.ITEM_RESOLVED
                        : ManagerDailyControlEventType.ITEM_ACTION,
                actionType,
                eventComment
        );

        return concreteItemResponse(savedConcreteItem);
    }

    @Transactional
    public ManagerControlConcreteItemResponse sendClientMessage(Long concreteItemId, Principal principal, Authentication authentication) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findById(concreteItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        ManagerDailyControl control = concreteItem.getControl();
        requireControlAccess(control, principal, authentication);
        String entityType = safe(concreteItem.getEntityType());
        if (!"ORDER".equals(entityType) && !ENTITY_WORKER_ORDER_NEW.equals(entityType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Автоотправка клиенту доступна только для заказов");
        }
        Order order = orderRepository.findById(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ карточки контроля не найден"));
        String message = clientControlMessage(concreteItem, order);
        if (message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для карточки не удалось собрать текст клиенту");
        }

        long startedAt = System.currentTimeMillis();
        ClientMessageSendResult result;
        try {
            result = clientChatMessageSender.send(
                    order.getCompany(),
                    order.getManager() == null ? null : order.getManager().getClientId(),
                    order.getCompany() == null ? null : order.getCompany().getGroupId(),
                    message
            );
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сообщение клиенту не отправлено: " + readableException(e), e);
        }
        if (!result.sent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сообщение клиенту не отправлено: " + clientMessageError(result)
            );
        }

        LocalDateTime now = LocalDateTime.now();
        concreteItem.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        concreteItem.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        concreteItem.setLastManualTouchAt(now);
        concreteItem.setFollowUpAt(now.plusDays(MANUAL_FOLLOW_UP_DAYS));
        concreteItem.setResolvedAt(null);
        String statusNote = applyOrderStatusAfterClientSend(concreteItem, order);
        concreteItem.setComment(limit("Сообщение клиенту отправлено через " + safe(result.channel()) + statusNote, 1000));
        ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);

        updateParentItemFromConcreteItems(savedConcreteItem.getParentItem());

        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        control.setStatus(recalculateControlStatus(control));
        dailyControlRepository.save(control);

        saveEvent(
                control,
                savedConcreteItem.getParentItem(),
                actorUserId(principal),
                ManagerDailyControlEventType.ITEM_ACTION,
                ManagerDailyControlActionType.ACTION_TAKEN,
                "Клиенту отправлено сообщение по карточке: " + concreteItem.getTitle()
                        + " через " + safe(result.channel())
                        + " за " + (System.currentTimeMillis() - startedAt) + " мс"
                        + statusNote
        );

        return concreteItemResponse(savedConcreteItem, message);
    }

    @Transactional
    public ManagerControlConcreteItemResponse replyToClientMessage(
            Long concreteItemId,
            ManagerControlClientReplyRequest request,
            Principal principal,
            Authentication authentication
    ) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        String message = safe(request == null ? null : request.message());
        if (message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Введите текст ответа клиенту");
        }
        if (message.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ответ клиенту слишком длинный");
        }

        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findById(concreteItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        ManagerDailyControl control = concreteItem.getControl();
        requireControlAccess(control, principal, authentication);
        if (!ENTITY_CLIENT_CHAT_UNANSWERED.equals(safe(concreteItem.getEntityType()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ответ из карточки доступен только для неотвеченных сообщений");
        }
        if (concreteItem.getEntityId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Карточка не связана с сообщением клиента");
        }

        ClientChatUnansweredItem unansweredItem = clientChatUnansweredItemRepository.findById(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Неотвеченное сообщение не найдено"));
        if (unansweredItem.getStatus() != ClientChatUnansweredStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Это сообщение уже закрыто");
        }
        Company company = unansweredItem.getCompany();
        Manager manager = unansweredItem.getManager() == null ? control.getManager() : unansweredItem.getManager();
        ClientMessageSendResult result = clientChatMessageSender.sendToPlatform(
                unansweredItem.getPlatform(),
                company,
                manager == null ? null : manager.getClientId(),
                unansweredItem.getChatId(),
                unansweredItem.getChatId(),
                message
        );
        if (!result.sent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ответ клиенту не отправлен: " + clientMessageError(result)
            );
        }

        LocalDateTime now = LocalDateTime.now();
        concreteItem.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        concreteItem.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        concreteItem.setLastManualTouchAt(now);
        concreteItem.setResolvedAt(now);
        concreteItem.setFollowUpAt(null);
        concreteItem.setComment(limit("Ответ отправлен через " + safe(result.channel()) + ": " + message, 1000));
        ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);

        clientChatMessageTrackerService.markFromManagerControl(
                unansweredItem.getId(),
                ManagerDailyControlActionType.ACTION_TAKEN,
                "Ответ отправлен из контроля менеджера через " + safe(result.channel())
        );
        updateParentItemFromConcreteItems(savedConcreteItem.getParentItem());

        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        control.setStatus(recalculateControlStatus(control));
        dailyControlRepository.save(control);

        saveEvent(
                control,
                savedConcreteItem.getParentItem(),
                actorUserId(principal),
                ManagerDailyControlEventType.ITEM_ACTION,
                ManagerDailyControlActionType.ACTION_TAKEN,
                "Ответ клиенту отправлен из карточки: " + concreteItem.getTitle()
                        + " через " + safe(result.channel())
        );

        return concreteItemResponse(savedConcreteItem, message);
    }

    @Transactional
    public ManagerControlConcreteItemResponse repairConcreteItem(Long concreteItemId, Principal principal, Authentication authentication) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findById(concreteItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        ManagerDailyControl control = concreteItem.getControl();
        requireControlAccess(control, principal, authentication);
        String entityType = safe(concreteItem.getEntityType());
        if ("COMMON_INVOICE".equals(entityType)) {
            return repairCommonInvoiceConcreteItem(concreteItem, control, principal);
        }
        if (ENTITY_TELEGRAM_CHAT.equals(entityType)) {
            return repairTelegramChatConcreteItem(concreteItem, control, principal);
        }
        if (!ENTITY_WORKER_ORDER_NEW.equals(entityType)
                && !ENTITY_WORKER_ORDER_CORRECT.equals(entityType)
                && !"ORDER".equals(entityType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Автопочинка доступна только для заказов с клиентской автоматизацией");
        }
        Order order = orderRepository.findById(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ карточки контроля не найден"));
        if (!order.isWaitingForClient() && ENTITY_WORKER_ORDER_NEW.equals(entityType)) {
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    "Заказ уже не отмечен как «ждет клиента»",
                    principal,
                    "Статус ожидания клиента уже снят"
            );
        }

        if (!ENTITY_WORKER_ORDER_NEW.equals(entityType) || !order.isWaitingForClient()) {
            return repairOrderAutomationConcreteItem(concreteItem, control, order, principal);
        }

        LocalDate today = LocalDate.now();
        long waitingDays = daysSince(clientTextWaitingControlDate(order), today);
        if (waitingDays > ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS) {
            order.setWaitingForClient(false);
            order.setWaitingForClientChangedAt(null);
            orderRepository.save(order);
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    "Снят зависший статус «ждет клиента» после " + waitingDays + " дн.",
                    principal,
                    "Снят зависший статус ожидания клиента"
            );
        }

        scheduledClientMessageService.ensureClientTextReminderForOrder(order);
        WorkerClientTextDecision decision = workerOrderClientTextDecision(
                order,
                "Новый",
                today,
                scheduledStatesByOrderId(List.of(order))
        );
        if (decision.include()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автоответчик не удалось починить: " + safe(decision.reason())
            );
        }

        return resolveRepairedConcreteItem(
                concreteItem,
                control,
                "Очередь CLIENT_TEXT_REMINDER восстановлена, автоответчик продолжит напоминания",
                principal,
                "Восстановлена очередь CLIENT_TEXT_REMINDER"
        );
    }

    private ManagerControlConcreteItemResponse repairCommonInvoiceConcreteItem(
            ManagerDailyControlConcreteItem concreteItem,
            ManagerDailyControl control,
            Principal principal
    ) {
        Long invoiceId = concreteItem.getEntityId();
        if (invoiceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У карточки общего счета нет ID счета");
        }
        CommonInvoice invoice = commonInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (commonInvoicePaymentNotificationRepairable(invoice)) {
            commonBillingService.resolvePaymentSuccessNotification(invoiceId);
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    "Ошибка уведомления об оплате закрыта",
                    principal,
                    "Закрыта ошибка уведомления об оплате общего счета"
            );
        }
        if (commonInvoiceWhatsappGroupTailRepairable(invoice)) {
            invoice.setLastError(null);
            commonInvoiceRepository.save(invoice);
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    "Старый хвост WhatsApp groupId скрыт из контроля",
                    principal,
                    "Закрыта устаревшая ошибка WhatsApp groupId общего счета"
            );
        }
        if (commonInvoiceTechnicalTailRepairable(invoice)) {
            commonBillingService.resolveTechnicalTail(invoiceId);
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    "Технический хвост общего счета скрыт из контроля",
                    principal,
                    "Закрыт технический хвост общего счета"
            );
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Эту ошибку общего счета нельзя исправить автоматически. Откройте счет и проверьте позиции вручную."
        );
    }

    private ManagerControlConcreteItemResponse repairTelegramChatConcreteItem(
            ManagerDailyControlConcreteItem concreteItem,
            ManagerDailyControl control,
            Principal principal
    ) {
        Long companyId = concreteItem.getEntityId();
        if (companyId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У карточки Telegram-группы нет ID компании");
        }
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
        Long oldChatId = company.getTelegramGroupChatId();
        if (oldChatId == null) {
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    "Telegram-группа уже не привязана к старому chat_id",
                    principal,
                    "Telegram-группа уже отвязана"
            );
        }

        Optional<TelegramChatMigrationResult> result = telegramService.repairMigratedChatId(oldChatId);
        if (result.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Telegram не вернул новый chat_id. Возможно, группа еще не стала супергруппой или бот потерял доступ."
            );
        }

        TelegramChatMigrationResult migration = result.get();
        if (!migration.updated()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Telegram вернул новый chat_id, но в БД не нашлось записей со старым id " + oldChatId
            );
        }

        return resolveRepairedConcreteItem(
                concreteItem,
                control,
                "Telegram chat_id обновлен: " + migration.oldChatId() + " -> " + migration.newChatId(),
                principal,
                "Обновлен Telegram chat_id компании"
        );
    }

    private ManagerControlConcreteItemResponse repairOrderAutomationConcreteItem(
            ManagerDailyControlConcreteItem concreteItem,
            ManagerDailyControl control,
            Order order,
            Principal principal
    ) {
        Optional<ClientMessageScenario> scenario = scheduledClientMessageService.ensureOrderAutomationForOrder(order);
        if (scenario.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Для текущего статуса заказа нет автоматической очереди, которую можно восстановить"
            );
        }

        ScheduledClientMessageState state = currentOrderAutomationState(
                order,
                scenario.get(),
                scheduledStatesByOrderId(List.of(order))
        );
        if (!clientTextReminderIsHealthy(state)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автоответчик не удалось починить: " + clientTextReminderProblem(state)
            );
        }

        return resolveRepairedConcreteItem(
                concreteItem,
                control,
                "Очередь " + scenario.get().name() + " восстановлена, автоответчик продолжит работу",
                principal,
                "Восстановлена очередь " + scenario.get().name()
        );
    }

    private ManagerControlConcreteItemResponse resolveRepairedConcreteItem(
            ManagerDailyControlConcreteItem concreteItem,
            ManagerDailyControl control,
            String comment,
            Principal principal,
            String eventComment
    ) {
        LocalDateTime now = LocalDateTime.now();
        concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        concreteItem.setActionType(ManagerDailyControlActionType.RESOLVED);
        concreteItem.setComment(limit(comment, 1000));
        concreteItem.setResolvedAt(now);
        concreteItem.setFollowUpAt(null);
        concreteItem.setLastManualTouchAt(now);
        ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);

        updateParentItemFromConcreteItems(savedConcreteItem.getParentItem());

        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        control.setStatus(recalculateControlStatus(control));
        dailyControlRepository.save(control);

        saveEvent(
                control,
                savedConcreteItem.getParentItem(),
                actorUserId(principal),
                ManagerDailyControlEventType.ITEM_RESOLVED,
                ManagerDailyControlActionType.RESOLVED,
                eventComment + ": " + savedConcreteItem.getTitle()
        );

        return concreteItemResponse(savedConcreteItem);
    }

    private boolean movePaymentOrderToReminderAfterManualSend(ManagerDailyControlConcreteItem concreteItem) {
        Long orderId = concreteItem.getEntityId();
        if (orderId == null) {
            return false;
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ контроля не найден"));
        String currentStatus = order.getStatus() == null ? "" : safe(order.getStatus().getTitle());
        if (ORDER_STATUS_REMINDER.equals(currentStatus)) {
            concreteItem.setStatusLabel(ORDER_STATUS_REMINDER);
            return false;
        }
        if (!ORDER_STATUS_TO_PAY.equals(currentStatus)) {
            return false;
        }

        try {
            boolean changed = orderService.changeStatusForOrder(orderId, ORDER_STATUS_REMINDER);
            if (!changed) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось перевести заказ в Напоминание");
            }
            concreteItem.setStatusLabel(ORDER_STATUS_REMINDER);
            return true;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось перевести заказ в Напоминание", e);
        }
    }

    private String clientControlMessage(ManagerDailyControlConcreteItem concreteItem, Order order) {
        String status = orderStatusTitle(order);
        if (!MANUAL_CONTACT_ORDER_STATUSES.contains(status)) {
            return "";
        }
        if ("Новый".equals(status) && order != null && order.isWaitingForClient()) {
            return clientTextContactText(order);
        }
        if ("На проверке".equals(status)) {
            String detailsId = orderDetailsId(concreteItem, order);
            if (detailsId.isBlank()) {
                return "";
            }
            return List.of(
                    orderHeading(order),
                    "Здравствуйте, напоминаем, пожалуйста, проверьте шаблоны отзывов и внесите правки, если они нужны.",
                    "Ссылка на проверку отзывов: " + absoluteAppUrl("/" + detailsId)
            ).stream().filter(value -> !safe(value).isBlank()).collect(Collectors.joining("\n\n"));
        }
        return paymentContactText(order, status);
    }

    private String paymentContactText(Order order, String status) {
        String payText = safe(order == null || order.getManager() == null ? null : order.getManager().getPayText());
        if (payText.isBlank()) {
            payText = switch (status) {
                case "Опубликовано" -> "Здравствуйте, ваш заказ выполнен, просьба оплатить.";
                case "Не оплачено" -> "Здравствуйте, напоминаем, пожалуйста, по оплате заказа. Пришлите чек, пожалуйста, как оплатите.";
                default -> "Здравствуйте, напоминаем, пожалуйста, об оплате заказа. Пришлите чек, пожалуйста, как оплатите.";
            };
        }
        String amount = money(order == null ? null : order.getSum());
        String body = amount.isBlank() ? payText : payText + " К оплате: " + amount + " руб.";
        return List.of(orderHeading(order), body).stream()
                .filter(value -> !safe(value).isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    private String clientTextContactText(Order order) {
        if (scheduledClientMessageService != null) {
            try {
                return scheduledClientMessageService.clientTextReminderText(order);
            } catch (Exception e) {
                log.warn("Не удалось собрать текст автонапоминания клиенту для заказа {}", order == null ? null : order.getId(), e);
            }
        }
        return List.of(
                orderHeading(order),
                "Здравствуйте! Напоминаем, пожалуйста, пришлите текст или пожелания для отзывов по заказу №"
                        + (order == null || order.getId() == null ? "" : order.getId())
                        + ", чтобы мы могли продолжить работу."
        ).stream().filter(value -> !safe(value).isBlank()).collect(Collectors.joining("\n\n"));
    }

    private String applyOrderStatusAfterClientSend(ManagerDailyControlConcreteItem concreteItem, Order order) {
        String currentStatus = orderStatusTitle(order);
        String targetStatus = switch (currentStatus) {
            case "Опубликовано" -> ORDER_STATUS_TO_PAY;
            case ORDER_STATUS_TO_PAY -> ORDER_STATUS_REMINDER;
            default -> "";
        };
        if (targetStatus.isBlank() || targetStatus.equals(currentStatus)) {
            return "";
        }
        try {
            boolean changed = orderService.changeStatusForOrder(order.getId(), targetStatus);
            if (changed) {
                concreteItem.setStatusLabel(targetStatus);
                return ". Статус заказа переведен в " + targetStatus;
            }
            return ". Сообщение отправлено, но статус заказа не изменился";
        } catch (Exception e) {
            return ". Сообщение отправлено, но статус заказа не изменился: " + readableException(e);
        }
    }

    private String orderDetailsId(ManagerDailyControlConcreteItem concreteItem, Order order) {
        String detailsId = safe(concreteItem == null ? null : concreteItem.getOrderDetailsId());
        if (!detailsId.isBlank()) {
            return detailsId;
        }
        if (order == null || order.getDetails() == null || order.getDetails().isEmpty() || order.getDetails().getFirst().getId() == null) {
            return "";
        }
        return order.getDetails().getFirst().getId().toString();
    }

    private String orderHeading(Order order) {
        if (order == null) {
            return "";
        }
        String company = order.getCompany() == null ? "" : safe(order.getCompany().getTitle());
        String filial = order.getFilial() == null ? "" : safe(order.getFilial().getTitle());
        return List.of(company, filial).stream()
                .filter(value -> !safe(value).isBlank())
                .collect(Collectors.joining(" - "));
    }

    private String orderStatusTitle(Order order) {
        return order == null || order.getStatus() == null ? "" : safe(order.getStatus().getTitle());
    }

    private String clientMessageError(ClientMessageSendResult result) {
        if (result == null) {
            return "нет ответа от сервиса отправки";
        }
        String message = safe(result.errorMessage());
        if (!message.isBlank()) {
            return message;
        }
        String code = safe(result.errorCode());
        return code.isBlank() ? "сервис отправки не подтвердил доставку" : code;
    }

    private String readableException(Exception e) {
        if (e == null) {
            return "неизвестная ошибка";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    @Transactional
    public ManagerControlManagerDetailResponse markStage(Long controlId, ManagerControlStageRequest request, Principal principal, Authentication authentication) {
        ManagerDailyControl control = controlForAction(controlId, principal, authentication);
        String stage = safe(request == null ? null : request.stage()).toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        switch (stage) {
            case "MORNING_START" -> control.setMorningStartedAt(now);
            case "MORNING_DONE" -> {
                rejectStageCompletionIfProblemsOpen(control, "Начало дня");
                rejectIfOutsideStageWindow("Начало дня", now.toLocalTime());
                if (control.getMorningStartedAt() == null) {
                    control.setMorningStartedAt(now);
                }
                control.setMorningCompletedAt(now);
            }
            case "DAY_CHECK" -> {
                rejectStageCompletionIfProblemsOpen(control, "Дневной контроль");
                rejectIfPreviousStageMissing(control.getMorningCompletedAt(), "Сначала отметьте начало дня");
                rejectIfOutsideStageWindow("Дневной контроль", now.toLocalTime());
                control.setDayCheckedAt(now);
            }
            case "FINAL_CHECK" -> {
                rejectStageCompletionIfProblemsOpen(control, "Конец дня");
                rejectIfPreviousStageMissing(control.getMorningCompletedAt(), "Сначала отметьте начало дня");
                rejectIfOutsideStageWindow("Конец дня", now.toLocalTime());
                control.setFinalCheckedAt(now);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный этап контроля");
        }
        updateQuality(control, dailyControlItemRepository.findByControl(control));
        dailyControlRepository.save(control);
        saveEvent(control, null, actorUserId(principal), ManagerDailyControlEventType.STAGE_MARKED, null,
                stage + (safe(request == null ? null : request.comment()).isBlank() ? "" : ". " + request.comment()));
        return managerDetails(control.getManager().getId(), principal, authentication);
    }

    @Transactional
    public ManagerControlCloseResponse closeDay(Long controlId, ManagerControlCloseRequest request, Principal principal, Authentication authentication) {
        ManagerDailyControl control = controlForAction(controlId, principal, authentication);
        List<ManagerDailyControlItem> items = dailyControlItemRepository.findByControl(control);
        List<String> blockers = closeBlockers(control, items);
        updateQuality(control, items);
        if (!blockers.isEmpty()) {
            dailyControlRepository.save(control);
            saveEvent(control, null, actorUserId(principal), ManagerDailyControlEventType.CLOSE_ATTEMPT_BLOCKED, null,
                    String.join("; ", blockers));
            return closeResponse(control, false, blockers);
        }
        LocalDateTime now = LocalDateTime.now();
        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        if (control.getFinalCheckedAt() == null) {
            control.setFinalCheckedAt(now);
        }
        control.setClosedAt(now);
        control.setClosedByUserId(actorUserId(principal));
        control.setLastActivityAt(now);
        control.setStatus(recalculateControlStatus(items));
        updateQuality(control, items);
        dailyControlRepository.save(control);
        saveEvent(control, null, actorUserId(principal), ManagerDailyControlEventType.CONTROL_CLOSED, null,
                safe(request == null ? null : request.comment()));
        return closeResponse(control, true, List.of());
    }

    @Transactional
    public ManagerControlManagerDetailResponse managerDetails(Long managerId, Principal principal, Authentication authentication) {
        reconcileClientMessagesForControl();
        if (managerId == null || managerId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный менеджер");
        }
        Manager manager = visibleManagers(principal, authentication).stream()
                .filter(item -> managerId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер недоступен"));

        LocalDate today = LocalDate.now();
        managerControl(manager, today);
        ManagerDailyControl control = dailyControlRepository.findByControlDateAndManager(today, manager)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Контроль дня не найден"));
        List<ManagerDailyControlItem> items = dailyControlItemRepository.findByControl(control).stream()
                .filter(this::isActiveControlItem)
                .sorted(Comparator
                        .comparingInt(this::detailItemRank)
                        .thenComparing(ManagerDailyControlItem::getLabel, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ManagerDailyControlItem::getId))
                .toList();
        updateQuality(control, items);
        dailyControlRepository.save(control);
        User user = manager.getUser();
        List<String> blockers = closeBlockers(control, items);

        return new ManagerControlManagerDetailResponse(
                manager.getId(),
                user == null ? null : user.getId(),
                safe(user == null ? null : user.getUsername()),
                managerName(manager),
                control.getId(),
                control.getControlDate(),
                control.getStatus().name(),
                control.getStartedAt(),
                control.getClosedAt(),
                control.getLastActivityAt(),
                control.getMorningStartedAt(),
                control.getMorningCompletedAt(),
                control.getDayCheckedAt(),
                control.getFinalCheckedAt(),
                control.getQualityScore(),
                control.getQualityGrade(),
                control.getRiskScore(),
                control.isFastClickRisk(),
                blockers.isEmpty(),
                blockers,
                items.stream().filter(this::isOpenActionItem).count(),
                items.stream().filter(this::isHandledActionItem).count(),
                items.stream().map(item -> detailItem(manager, item, today)).toList(),
                events(control)
        );
    }

    private List<Manager> visibleManagers(Principal principal, Authentication authentication) {
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            List<Manager> managers = managerRepository.findAllWithUserAndImage();
            return managers.isEmpty() ? List.of() : managerRepository.findAllManagersWorkers(managers);
        }

        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            List<Manager> managers = userService.findManagersByUserName(principal.getName()).stream().toList();
            return managers.isEmpty() ? List.of() : managerRepository.findAllManagersWorkers(managers);
        }

        return List.of();
    }

    private ManagerDailyControl controlForAction(Long controlId, Principal principal, Authentication authentication) {
        if (controlId == null || controlId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный контроль дня");
        }
        ManagerDailyControl control = dailyControlRepository.findById(controlId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Контроль дня не найден"));
        requireControlAccess(control, principal, authentication);
        return control;
    }

    private void requireCommentIfNeeded(ManagerDailyControlItem item, ManagerDailyControlActionType actionType, String comment) {
        if (item == null || actionType == ManagerDailyControlActionType.RESOLVED) {
            return;
        }
        boolean required = actionType == ManagerDailyControlActionType.DEFERRED;
        if (required && safe(comment).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для этого действия нужен комментарий");
        }
    }

    private List<String> closeBlockers(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        List<String> blockers = new ArrayList<>();
        if (control.getMorningCompletedAt() == null) {
            blockers.add("Не отмечено начало дня");
        }
        if (control.getFinalCheckedAt() == null) {
            blockers.add("Не отмечен конец дня");
        }
        blockers.addAll(problemBlockers(items));
        return blockers;
    }

    private void rejectStageCompletionIfProblemsOpen(ManagerDailyControl control, String stageLabel) {
        List<String> blockers = problemBlockers(dailyControlItemRepository.findByControl(control));
        if (!blockers.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    stageLabel + " нельзя завершить: " + String.join("; ", blockers)
            );
        }
    }

    private void rejectIfPreviousStageMissing(LocalDateTime completedAt, String message) {
        if (completedAt == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private void rejectIfOutsideStageWindow(String stageLabel, LocalTime time) {
        boolean allowed = switch (stageLabel) {
            case "Начало дня" -> !time.isBefore(MORNING_STAGE_START) && time.isBefore(FINAL_STAGE_START);
            case "Дневной контроль" -> !time.isBefore(START_DAY_DEADLINE) && time.isBefore(FINAL_STAGE_START);
            case "Конец дня" -> !time.isBefore(FINAL_STAGE_START) || time.isBefore(MORNING_STAGE_START);
            default -> true;
        };
        if (!allowed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    stageLabel + " можно завершить только в свое окно: начало дня 05:00-20:00, конец дня 20:00-04:59"
            );
        }
    }

    private List<String> problemBlockers(List<ManagerDailyControlItem> items) {
        List<String> blockers = new ArrayList<>();
        List<ManagerDailyControlItem> openActionItems = items.stream()
                .filter(this::isOpenActionItem)
                .toList();
        if (!openActionItems.isEmpty()) {
            blockers.add("Остались открытые пункты: " + openActionItems.stream()
                    .limit(5)
                    .map(item -> item.getLabel() + " " + item.getCount())
                    .collect(Collectors.joining(", ")));
        }
        List<ManagerDailyControlItem> concreteParents = items.stream()
                .filter(this::requiresConcreteCardAction)
                .toList();
        if (!concreteParents.isEmpty()) {
            Map<Long, List<ManagerDailyControlConcreteItem>> concreteByParentId = dailyControlConcreteItemRepository
                    .findByParentItemIn(concreteParents).stream()
                    .filter(item -> item.getParentItem() != null && item.getParentItem().getId() != null)
                    .collect(Collectors.groupingBy(item -> item.getParentItem().getId()));
            List<ManagerDailyControlConcreteItem> openConcreteItems = concreteByParentId.values().stream()
                    .flatMap(List::stream)
                    .filter(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN)
                    .toList();
            if (!openConcreteItems.isEmpty()) {
                blockers.add("Остались открытые карточки внутри пунктов: " + openConcreteItems.stream()
                        .limit(5)
                        .map(ManagerDailyControlConcreteItem::getTitle)
                        .collect(Collectors.joining(", ")));
            }
            List<ManagerDailyControlItem> incompleteConcreteParents = concreteParents.stream()
                    .filter(item -> concreteByParentId.getOrDefault(item.getId(), List.of()).size() < item.getCount())
                    .toList();
            if (!incompleteConcreteParents.isEmpty()) {
                blockers.add("Не раскрыты все карточки по красным пунктам: " + incompleteConcreteParents.stream()
                        .limit(5)
                        .map(item -> item.getLabel() + " "
                                + concreteByParentId.getOrDefault(item.getId(), List.of()).size()
                                + "/" + item.getCount())
                        .collect(Collectors.joining(", ")));
            }
        }
        List<ManagerDailyControlItem> criticalWithoutComment = items.stream()
                .filter(item -> item.getGroup() == ManagerDailyControlGroup.ACTION)
                .filter(item -> item.getSeverity() == ManagerDailyControlSeverity.CRITICAL)
                .filter(item -> item.getStatus() != ManagerDailyControlItemStatus.OPEN)
                .filter(item -> item.getStatus() != ManagerDailyControlItemStatus.RESOLVED)
                .filter(item -> safe(item.getComment()).isBlank())
                .toList();
        if (!criticalWithoutComment.isEmpty()) {
            blockers.add("Нет комментария по критичным пунктам: " + criticalWithoutComment.stream()
                    .limit(5)
                    .map(ManagerDailyControlItem::getLabel)
                    .collect(Collectors.joining(", ")));
        }
        return blockers;
    }

    private List<ManagerDailyControlItem> activeControlItems(List<ManagerDailyControlItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .filter(this::isActiveControlItem)
                .toList();
    }

    private boolean isActiveControlItem(ManagerDailyControlItem item) {
        if (item == null) {
            return false;
        }
        if (item.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        if (item.getGroup() == ManagerDailyControlGroup.WORKLOAD) {
            return false;
        }
        return item.getItemType() != ManagerDailyControlItemType.WORKER_SECTION
                || !"risk".equals(item.getSectionCode());
    }

    private ManagerControlCloseResponse closeResponse(ManagerDailyControl control, boolean closed, List<String> blockers) {
        return new ManagerControlCloseResponse(
                closed,
                control.getStatus().name(),
                control.getQualityScore(),
                control.getQualityGrade(),
                control.getRiskScore(),
                control.isFastClickRisk(),
                blockers
        );
    }

    private void updateQuality(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        if (control == null || items == null) {
            return;
        }
        long openCritical = items.stream().filter(this::isOpenCriticalActionItem).count();
        long openAction = items.stream().filter(this::isOpenActionItem).count();
        long deferred = items.stream().filter(item -> item.getStatus() == ManagerDailyControlItemStatus.DEFERRED).count();
        int riskScore = (int) Math.min(100, openCritical * 20 + openAction * 8 + deferred * 4);
        boolean fastClickRisk = hasFastClickRisk(control);
        if (fastClickRisk) {
            riskScore = Math.min(100, riskScore + 25);
        }
        int stageScore = 0;
        stageScore += control.getMorningCompletedAt() == null ? 10 : 0;
        stageScore += control.getFinalCheckedAt() == null ? 12 : 0;
        int quality = Math.max(0, 100 - riskScore - stageScore);
        control.setRiskScore(riskScore);
        control.setFastClickRisk(fastClickRisk);
        control.setQualityScore(quality);
        control.setQualityGrade(quality >= 90 ? "A" : quality >= 75 ? "B" : quality >= 55 ? "C" : "D");
    }

    private boolean hasFastClickRisk(ManagerDailyControl control) {
        List<ManagerDailyControlEvent> actions = dailyControlEventRepository.findByControlOrderByCreatedAtDesc(control).stream()
                .filter(event -> event.getEventType() == ManagerDailyControlEventType.ITEM_ACTION)
                .filter(event -> event.getCreatedAt() != null)
                .toList();
        if (actions.size() < 3) {
            return false;
        }
        LocalDateTime first = actions.stream().map(ManagerDailyControlEvent::getCreatedAt).min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime last = actions.stream().map(ManagerDailyControlEvent::getCreatedAt).max(LocalDateTime::compareTo).orElse(null);
        return first != null && last != null && ChronoUnit.SECONDS.between(first, last) <= 20;
    }

    private List<ManagerControlEventResponse> events(ManagerDailyControl control) {
        return dailyControlEventRepository.findByControlOrderByCreatedAtDesc(control).stream()
                .map(event -> new ManagerControlEventResponse(
                        event.getId(),
                        event.getItem() == null ? null : event.getItem().getId(),
                        event.getItem() == null ? null : event.getItem().getLabel(),
                        event.getActorUserId(),
                        event.getEventType().name(),
                        event.getActionType() == null ? null : event.getActionType().name(),
                        event.getComment(),
                        event.getCreatedAt()
                ))
                .toList();
    }

    private ManagerControlManagerResponse managerControl(Manager manager, LocalDate today) {
        User user = manager.getUser();
        Map<String, Integer> orderCounts = safeMap(orderService.countOrdersByStatusToManager(manager));
        WorkerSectionCounts workerCounts = workerSectionCounts(manager, today);
        List<ManagerControlOverdueStatusResponse> overdueStatuses = overdueStatuses(manager, today);
        long overdueOrders = overdueStatuses.stream().mapToLong(ManagerControlOverdueStatusResponse::count).sum();
        long openRisks = openRiskCount(manager);
        long orderAttention = sum(orderCounts, ORDER_ATTENTION_STATUSES);
        long workerSectionTotal = workerCounts.total();
        long workerActionCount = workerCounts.actionTotal();
        long workerWorkloadCount = workerCounts.workloadTotal();
        long requiresAttention = orderCounts.getOrDefault("Требует внимания", 0);
        long commonInvoiceActionCount = commonInvoiceActionCount(manager);
        long telegramChatIssueCount = telegramChatIssueCompanies(manager, 10_000).size();
        long unansweredClientMessages = clientChatMessageTrackerService.countDue(manager);

        List<ManagerControlProblemResponse> problems = new ArrayList<>();
        addProblem(problems, "OVERDUE_ORDERS", "Просроченные заказы", overdueOrders, "CRITICAL", "ACTION", "schedule", ordersUrl(manager, null));
        addProblem(problems, "OPEN_RISKS", "Риски", openRisks, "CRITICAL", "ACTION", "warning", "/worker/risk");
        addProblem(problems, "REQUIRES_ATTENTION", "Требует внимания", requiresAttention, "CRITICAL", "ACTION", "error", ordersUrl(manager, "Требует внимания"));
        addProblem(problems, "COMMON_INVOICES", "Общие счета", commonInvoiceActionCount, "CRITICAL", "ACTION", "receipt_long", "/admin/common-billing");
        addProblem(problems, "TELEGRAM_CHAT_MIGRATION", "Telegram-группы", telegramChatIssueCount, "CRITICAL", "ACTION", "send", ordersUrl(manager, null));
        addProblem(problems, "UNANSWERED_CLIENT_MESSAGES", "Неотвеченные сообщения", unansweredClientMessages, "CRITICAL", "ACTION", "mark_chat_unread", "/admin/manager-control/" + manager.getId());
        addProblem(problems, "ORDERS_WORKLOAD", "Рабочие заказы", orderAttention, "INFO", "WORKLOAD", "inventory_2", ordersUrl(manager, null));
        addProblem(problems, "WORKER_WORKLOAD", "Нагрузка специалистов", workerWorkloadCount, "INFO", "WORKLOAD", "engineering", firstWorkerSectionUrl(workerCounts.sections(), "WORKLOAD", "new"));

        List<ManagerControlSectionResponse> sections = workerCounts.sections();
        long criticalCount = overdueOrders + openRisks + requiresAttention + commonInvoiceActionCount + telegramChatIssueCount + unansweredClientMessages + workerActionCount;
        long warningCount = 0;
        long workloadCount = orderAttention + workerWorkloadCount;
        DailyControlSyncResult controlSync = syncDailyControl(
                manager,
                today,
                problems,
                sections,
                overdueStatuses
        );
        problems = problems.stream()
                .map(problem -> decorate(problem, controlSync.itemsByKey().get(problemKey(problem.code()))))
                .toList();
        sections = sections.stream()
                .map(section -> decorate(section, controlSync.itemsByKey().get(workerSectionKey(section.code()))))
                .toList();
        overdueStatuses = overdueStatuses.stream()
                .map(statusItem -> decorate(statusItem, controlSync.itemsByKey().get(overdueKey(statusItem.status()))))
                .toList();

        long openItemCount = controlSync.items().stream()
                .filter(this::isOpenActionItem)
                .count();
        long handledItemCount = controlSync.items().stream()
                .filter(this::isHandledActionItem)
                .count();
        long openCriticalCount = controlSync.items().stream()
                .filter(this::isOpenCriticalActionItem)
                .count();
        long handledCriticalCount = controlSync.items().stream()
                .filter(this::isHandledCriticalActionItem)
                .count();
        String status = openCriticalCount > 0 ? "RED" : handledCriticalCount > 0 || warningCount > 0 ? "YELLOW" : "GREEN";
        updateQuality(controlSync.control(), controlSync.items());
        dailyControlRepository.save(controlSync.control());
        List<String> blockers = closeBlockers(controlSync.control(), controlSync.items());

        return new ManagerControlManagerResponse(
                manager.getId(),
                user == null ? null : user.getId(),
                safe(user == null ? null : user.getUsername()),
                managerName(manager),
                user == null || user.isActive(),
                controlSync.control().getId(),
                controlSync.control().getStatus().name(),
                controlSync.control().getStartedAt(),
                controlSync.control().getClosedAt(),
                controlSync.control().getMorningStartedAt(),
                controlSync.control().getMorningCompletedAt(),
                controlSync.control().getDayCheckedAt(),
                controlSync.control().getFinalCheckedAt(),
                controlSync.control().getQualityScore(),
                controlSync.control().getQualityGrade(),
                controlSync.control().getRiskScore(),
                controlSync.control().isFastClickRisk(),
                blockers.isEmpty(),
                openItemCount,
                handledItemCount,
                status,
                criticalCount,
                warningCount,
                workloadCount,
                criticalCount + warningCount,
                overdueOrders,
                openRisks,
                orderAttention,
                workerSectionTotal,
                problems,
                sections,
                overdueStatuses
        );
    }

    private DailyControlSyncResult syncDailyControl(
            Manager manager,
            LocalDate today,
            List<ManagerControlProblemResponse> problems,
            List<ManagerControlSectionResponse> sections,
            List<ManagerControlOverdueStatusResponse> overdueStatuses
    ) {
        ManagerDailyControl control = dailyControlRepository.findByControlDateAndManager(today, manager)
                .orElseGet(() -> {
                    ManagerDailyControl created = new ManagerDailyControl();
                    LocalDateTime now = LocalDateTime.now();
                    created.setControlDate(today);
                    created.setManager(manager);
                    created.setManagerUserId(manager.getUser() == null ? null : manager.getUser().getId());
                    created.setStatus(ManagerDailyControlStatus.IN_PROGRESS);
                    created.setStartedAt(now);
                    created.setMorningStartedAt(now);
                    created.setLastActivityAt(now);
                    ManagerDailyControl saved = dailyControlRepository.save(created);
                    saveEvent(saved, null, null, ManagerDailyControlEventType.CONTROL_CREATED, null, "Контроль дня стартовал автоматически");
                    return saved;
                });

        Map<String, ManagerDailyControlItem> existing = dailyControlItemRepository.findByControl(control).stream()
                .collect(Collectors.toMap(ManagerDailyControlItem::getItemKey, Function.identity(), (left, right) -> left));
        Set<String> activeKeys = new HashSet<>();
        List<ManagerDailyControlItem> currentItems = new ArrayList<>();

        for (ControlItemInput input : controlItemInputs(problems, sections, overdueStatuses)) {
            activeKeys.add(input.itemKey());
            ManagerDailyControlItem item = existing.get(input.itemKey());
            boolean created = false;
            if (item == null) {
                item = new ManagerDailyControlItem();
                item.setControl(control);
                item.setItemKey(input.itemKey());
                item.setStatus(ManagerDailyControlItemStatus.OPEN);
                created = true;
            }
            long previousCount = item.getCount();
            boolean shouldReopen = !created
                    && input.group() == ManagerDailyControlGroup.ACTION
                    && input.count() > previousCount
                    && item.getStatus() != ManagerDailyControlItemStatus.OPEN;
            boolean shouldReopenFollowUp = !created
                    && input.group() == ManagerDailyControlGroup.ACTION
                    && input.count() > 0
                    && item.getStatus() != ManagerDailyControlItemStatus.OPEN
                    && hasDueConcreteFollowUp(item);
            boolean shouldReopenConcrete = !created
                    && input.group() == ManagerDailyControlGroup.ACTION
                    && input.severity() == ManagerDailyControlSeverity.CRITICAL
                    && input.count() > 0
                    && item.getStatus() != ManagerDailyControlItemStatus.OPEN
                    && hasUnfinishedConcreteBreakdown(item, input.count());
            item.setItemType(input.itemType());
            item.setEntityId(input.entityId());
            item.setWorkerId(input.workerId());
            item.setSectionCode(input.sectionCode());
            item.setReasonCode(input.reasonCode());
            item.setLabel(input.label());
            item.setTargetUrl(input.targetUrl());
            item.setCount(input.count());
            item.setSeverity(input.severity());
            item.setGroup(input.group());
            if (shouldReopen || shouldReopenFollowUp || shouldReopenConcrete) {
                item.setStatus(ManagerDailyControlItemStatus.OPEN);
                item.setActionType(null);
                item.setComment(null);
                item.setResolvedAt(null);
            }
            ManagerDailyControlItem saved = dailyControlItemRepository.save(item);
            currentItems.add(saved);
            if (created) {
                saveEvent(control, saved, null, ManagerDailyControlEventType.ITEM_CREATED, null, null);
            } else if (shouldReopen) {
                saveEvent(control, saved, null, ManagerDailyControlEventType.ITEM_CREATED, null, "Пункт снова открыт: счетчик вырос");
            } else if (shouldReopenFollowUp) {
                saveEvent(control, saved, null, ManagerDailyControlEventType.ITEM_CREATED, null, "Пункт снова открыт: наступил повторный контроль");
            } else if (shouldReopenConcrete) {
                saveEvent(control, saved, null, ManagerDailyControlEventType.ITEM_CREATED, null, "Пункт снова открыт: есть необработанные карточки внутри");
            }
        }

        for (ManagerDailyControlItem item : existing.values()) {
            if (!activeKeys.contains(item.getItemKey()) && isOpenActionItem(item)) {
                item.setStatus(ManagerDailyControlItemStatus.RESOLVED);
                item.setResolvedAt(LocalDateTime.now());
                currentItems.add(dailyControlItemRepository.save(item));
                saveEvent(control, item, null, ManagerDailyControlEventType.ITEM_RESOLVED, ManagerDailyControlActionType.RESOLVED, "Автоматически закрыто: пункт больше не требует внимания");
            } else if (!currentItems.contains(item)) {
                currentItems.add(item);
            }
        }

        ManagerDailyControlStatus nextStatus = recalculateControlStatus(currentItems);
        if (control.getStatus() != nextStatus) {
            control.setStatus(nextStatus);
            saveEvent(control, null, null, ManagerDailyControlEventType.CONTROL_STATUS_CHANGED, null, nextStatus.name());
        }
        dailyControlRepository.save(control);

        Map<String, ManagerDailyControlItem> itemsByKey = currentItems.stream()
                .collect(Collectors.toMap(ManagerDailyControlItem::getItemKey, Function.identity(), (left, right) -> left));
        return new DailyControlSyncResult(control, currentItems, itemsByKey);
    }

    private List<ControlItemInput> controlItemInputs(
            List<ManagerControlProblemResponse> problems,
            List<ManagerControlSectionResponse> sections,
            List<ManagerControlOverdueStatusResponse> overdueStatuses
    ) {
        List<ControlItemInput> inputs = new ArrayList<>();
        for (ManagerControlProblemResponse problem : problems) {
            if (problem.count() <= 0) {
                continue;
            }
            if (parseGroup(problem.group()) == ManagerDailyControlGroup.WORKLOAD) {
                continue;
            }
            inputs.add(new ControlItemInput(
                    problemKey(problem.code()),
                    ManagerDailyControlItemType.PROBLEM,
                    null,
                    null,
                    null,
                    problem.code(),
                    problem.label(),
                    problem.targetUrl(),
                    problem.count(),
                    parseSeverity(problem.severity()),
                    parseGroup(problem.group())
            ));
        }
        for (ManagerControlSectionResponse section : sections) {
            if (section.count() <= 0) {
                continue;
            }
            if (parseGroup(section.group()) == ManagerDailyControlGroup.WORKLOAD) {
                continue;
            }
            inputs.add(new ControlItemInput(
                    workerSectionKey(section.code()),
                    ManagerDailyControlItemType.WORKER_SECTION,
                    null,
                    null,
                    section.code(),
                    section.code(),
                    section.label(),
                    section.targetUrl(),
                    section.count(),
                    parseSeverity(section.severity()),
                    parseGroup(section.group())
            ));
        }
        for (ManagerControlOverdueStatusResponse status : overdueStatuses) {
            if (status.count() <= 0) {
                continue;
            }
            inputs.add(new ControlItemInput(
                    overdueKey(status.status()),
                    ManagerDailyControlItemType.ORDER_STATUS,
                    null,
                    null,
                    status.status(),
                    status.status(),
                    status.status(),
                    status.targetUrl(),
                    status.count(),
                    ManagerDailyControlSeverity.CRITICAL,
                    ManagerDailyControlGroup.ACTION
            ));
        }
        return inputs;
    }

    private boolean hasDueConcreteFollowUp(ManagerDailyControlItem item) {
        if (item == null || item.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return dailyControlConcreteItemRepository.findByParentItem(item).stream()
                .anyMatch(concrete -> concrete.getFollowUpAt() != null
                        && !concrete.getFollowUpAt().isAfter(now)
                        && concrete.getStatus() != ManagerDailyControlItemStatus.OPEN
                        && concrete.getStatus() != ManagerDailyControlItemStatus.RESOLVED);
    }

    private boolean hasUnfinishedConcreteBreakdown(ManagerDailyControlItem item, long expectedCount) {
        if (!requiresConcreteCardAction(item) || item.getId() == null) {
            return false;
        }
        List<ManagerDailyControlConcreteItem> concreteItems = dailyControlConcreteItemRepository.findByParentItem(item);
        return concreteItems.size() < expectedCount
                || concreteItems.stream().anyMatch(concrete -> concrete.getStatus() == ManagerDailyControlItemStatus.OPEN);
    }

    private ManagerControlProblemResponse decorate(ManagerControlProblemResponse response, ManagerDailyControlItem item) {
        if (item == null) {
            return response;
        }
        return new ManagerControlProblemResponse(
                response.code(),
                response.label(),
                response.count(),
                response.severity(),
                response.group(),
                response.icon(),
                response.targetUrl(),
                item.getId(),
                item.getStatus().name(),
                item.getActionType() == null ? null : item.getActionType().name(),
                item.getComment()
        );
    }

    private ManagerControlSectionResponse decorate(ManagerControlSectionResponse response, ManagerDailyControlItem item) {
        if (item == null) {
            return response;
        }
        return new ManagerControlSectionResponse(
                response.code(),
                response.label(),
                response.count(),
                response.severity(),
                response.group(),
                response.targetUrl(),
                item.getId(),
                item.getStatus().name(),
                item.getActionType() == null ? null : item.getActionType().name(),
                item.getComment()
        );
    }

    private ManagerControlOverdueStatusResponse decorate(ManagerControlOverdueStatusResponse response, ManagerDailyControlItem item) {
        if (item == null) {
            return response;
        }
        return new ManagerControlOverdueStatusResponse(
                response.status(),
                response.count(),
                response.maxDays(),
                response.targetUrl(),
                item.getId(),
                item.getStatus().name(),
                item.getActionType() == null ? null : item.getActionType().name(),
                item.getComment()
        );
    }

    private ManagerControlItemDetailResponse detailItem(Manager manager, ManagerDailyControlItem item, LocalDate today) {
        List<ManagerControlConcreteItemResponse> examples = syncConcreteExamples(item, detailExamples(manager, item, today));
        return new ManagerControlItemDetailResponse(
                item.getId(),
                item.getItemKey(),
                item.getItemType().name(),
                item.getReasonCode(),
                reasonLabel(item),
                item.getSectionCode(),
                item.getLabel(),
                item.getTargetUrl(),
                item.getCount(),
                item.getSeverity().name(),
                item.getGroup().name(),
                item.getStatus().name(),
                item.getActionType() == null ? null : item.getActionType().name(),
                item.getComment(),
                examples,
                Math.max(0, item.getCount() - examples.size()),
                item.getCreatedAt(),
                item.getUpdatedAt(),
                item.getResolvedAt()
        );
    }

    private List<ManagerControlConcreteItemResponse> detailExamples(Manager manager, ManagerDailyControlItem item, LocalDate today) {
        if (item == null || item.getCount() <= 0) {
            return List.of();
        }
        int limit = concreteSyncLimit(item);
        if (item.getItemType() == ManagerDailyControlItemType.ORDER_STATUS || "OVERDUE_ORDERS".equals(item.getReasonCode())) {
            String status = item.getItemType() == ManagerDailyControlItemType.ORDER_STATUS ? item.getReasonCode() : "Все";
            return overdueOrderExamples(manager, status, today, limit);
        }
        if ("REQUIRES_ATTENTION".equals(item.getReasonCode())) {
            return orderStatusExamples(manager, "Требует внимания", limit);
        }
        if ("COMMON_INVOICES".equals(item.getReasonCode())) {
            return commonInvoiceExamples(manager, today, limit);
        }
        if ("TELEGRAM_CHAT_MIGRATION".equals(item.getReasonCode())) {
            return telegramChatIssueExamples(manager, limit);
        }
        if ("UNANSWERED_CLIENT_MESSAGES".equals(item.getReasonCode())) {
            return unansweredClientMessageExamples(manager, limit);
        }
        if ("OPEN_RISKS".equals(item.getReasonCode()) || "risk".equals(item.getSectionCode())) {
            return riskExamples(manager, limit);
        }
        if ("WORKER_ACTIONS".equals(item.getReasonCode())) {
            return workerActionExamples(manager, today, limit);
        }
        if ("new_overdue".equals(item.getSectionCode())) {
            return workerStaleOrderExamples(manager, "Новый", today, limit);
        }
        if ("correct_overdue".equals(item.getSectionCode())) {
            return workerStaleOrderExamples(manager, "Коррекция", today, limit);
        }
        if ("nagul_overdue".equals(item.getSectionCode())) {
            return nagulReviewExamples(manager, today, limit);
        }
        if ("recovery".equals(item.getSectionCode())) {
            return recoveryTaskExamples(manager, today, limit);
        }
        if ("publish".equals(item.getSectionCode())) {
            return publishReviewExamples(manager, today, limit);
        }
        if ("bad".equals(item.getSectionCode())) {
            return badReviewTaskExamples(manager, today, limit);
        }
        return List.of();
    }

    private int concreteSyncLimit(ManagerDailyControlItem item) {
        long requested = Math.max(DETAIL_EXAMPLE_LIMIT, item == null ? 0 : item.getCount());
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, requested));
    }

    private List<ManagerControlConcreteItemResponse> syncConcreteExamples(
            ManagerDailyControlItem parentItem,
            List<ManagerControlConcreteItemResponse> examples
    ) {
        if (parentItem == null || examples.isEmpty()) {
            return examples;
        }
        Map<String, ManagerDailyControlConcreteItem> existing = dailyControlConcreteItemRepository.findByParentItem(parentItem).stream()
                .collect(Collectors.toMap(ManagerDailyControlConcreteItem::getEntityKey, Function.identity(), (left, right) -> left));
        List<ManagerControlConcreteItemResponse> synced = new ArrayList<>();
        for (ManagerControlConcreteItemResponse example : examples) {
            String key = concreteEntityKey(example);
            ManagerDailyControlConcreteItem concreteItem = existing.get(key);
            if (concreteItem == null) {
                concreteItem = new ManagerDailyControlConcreteItem();
                concreteItem.setControl(parentItem.getControl());
                concreteItem.setParentItem(parentItem);
                concreteItem.setEntityKey(key);
                concreteItem.setStatus(ManagerDailyControlItemStatus.OPEN);
            }
            concreteItem.setEntityType(limit(safe(example.type()).isBlank() ? "UNKNOWN" : example.type(), 40));
            concreteItem.setEntityId(example.entityId());
            concreteItem.setTitle(limit(safe(example.title()).isBlank() ? "Карточка контроля" : example.title(), 220));
            concreteItem.setSubtitle(limit(example.subtitle(), 500));
            concreteItem.setStatusLabel(limit(example.status(), 120));
            concreteItem.setAgeDays(example.ageDays());
            concreteItem.setReason(limit(example.reason(), 500));
            concreteItem.setTargetUrl(limit(example.targetUrl(), 500));
            concreteItem.setOrderDetailsId(limit(example.orderDetailsId(), 36));
            concreteItem.setChatUrl(limit(example.chatUrl(), 500));
            reopenConcreteItemIfFollowUpDue(concreteItem);
            if (isConcreteSnoozed(concreteItem)) {
                dailyControlConcreteItemRepository.save(concreteItem);
                continue;
            }
            synced.add(concreteItemResponse(
                    dailyControlConcreteItemRepository.save(concreteItem),
                    example.contactText(),
                    example.specialistName()
            ));
        }
        return synced;
    }

    private String concreteEntityKey(ManagerControlConcreteItemResponse example) {
        String type = safe(example.type()).isBlank() ? "UNKNOWN" : example.type();
        Long id = example.entityId();
        if (id != null) {
            return type + ":" + id;
        }
        return type + ":" + safe(example.title()) + ":" + safe(example.targetUrl());
    }

    private ManagerControlConcreteItemResponse concreteItemResponse(ManagerDailyControlConcreteItem item) {
        return concreteItemResponse(item, null);
    }

    private ManagerControlConcreteItemResponse concreteItemResponse(ManagerDailyControlConcreteItem item, String contactText) {
        return concreteItemResponse(item, contactText, null);
    }

    private ManagerControlConcreteItemResponse concreteItemResponse(
            ManagerDailyControlConcreteItem item,
            String contactText,
            String specialistNameOverride
    ) {
        String specialistName = safe(specialistNameOverride).isBlank()
                ? specialistNameForConcreteItem(item)
                : specialistNameOverride;
        return new ManagerControlConcreteItemResponse(
                item.getId(),
                item.getEntityType(),
                item.getEntityId(),
                item.getTitle(),
                item.getSubtitle(),
                item.getStatusLabel(),
                item.getAgeDays(),
                item.getReason(),
                item.getTargetUrl(),
                item.getOrderDetailsId(),
                item.getChatUrl(),
                item.getFollowUpAt(),
                item.getLastManualTouchAt(),
                item.getStatus().name(),
                item.getActionType() == null ? null : item.getActionType().name(),
                item.getComment(),
                item.getUpdatedAt(),
                item.getResolvedAt(),
                item.getWorkerNotificationAttemptedAt(),
                item.getWorkerNotificationSentAt(),
                item.getWorkerNotificationAcceptedAt(),
                item.getWorkerNotificationAcceptedByUserId(),
                item.getWorkerNotificationFailureReason(),
                contactText,
                specialistName
        );
    }

    private boolean isConcreteSnoozed(ManagerDailyControlConcreteItem item) {
        return item != null
                && item.getFollowUpAt() != null
                && item.getFollowUpAt().isAfter(LocalDateTime.now())
                && item.getStatus() != ManagerDailyControlItemStatus.OPEN;
    }

    private void reopenConcreteItemIfFollowUpDue(ManagerDailyControlConcreteItem item) {
        if (item == null
                || item.getFollowUpAt() == null
                || item.getFollowUpAt().isAfter(LocalDateTime.now())
                || item.getStatus() == ManagerDailyControlItemStatus.OPEN
                || item.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            return;
        }
        item.setStatus(ManagerDailyControlItemStatus.OPEN);
        item.setActionType(null);
        item.setResolvedAt(null);
        item.setFollowUpAt(null);
    }

    private void updateParentItemFromConcreteItems(ManagerDailyControlItem parentItem) {
        if (parentItem == null || parentItem.getGroup() != ManagerDailyControlGroup.ACTION) {
            return;
        }
        List<ManagerDailyControlConcreteItem> concreteItems = dailyControlConcreteItemRepository.findByParentItem(parentItem);
        if (concreteItems.isEmpty() || concreteItems.size() < parentItem.getCount()) {
            return;
        }
        boolean allHandled = concreteItems.stream().noneMatch(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN);
        if (!allHandled) {
            return;
        }
        parentItem.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        parentItem.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        parentItem.setComment("Все конкретные карточки внутри пункта обработаны");
        parentItem.setResolvedAt(null);
        dailyControlItemRepository.save(parentItem);
    }

    private List<ManagerControlConcreteItemResponse> overdueOrderExamples(Manager manager, String status, LocalDate today, int limit) {
        LocalDate cutoff = today.minusDays(OVERDUE_NOTIFICATION_DAYS + 1L);
        Set<Long> snoozedOrderIds = snoozedOrderIds(manager, today);
        List<OrderDTOList> orders = orderService.getManagerControlOverdueOrdersByManager(
                        manager,
                        "",
                        safe(status).isBlank() ? "Все" : status,
                        cutoff,
                        OVERDUE_IGNORED_STATUSES,
                        COMMON_INVOICE_CONTROL_STATUSES,
                        PAYMENT_AUTOMATION_STATUSES,
                        PAYMENT_AUTOMATION_SCENARIOS,
                        REVIEW_CHECK_AUTOMATION_STATUSES,
                        REVIEW_CHECK_SCENARIOS,
                        DELIVERY_RETRY_AUTOMATION_STATUSES,
                        DELIVERY_RETRY_SCENARIOS,
                        CLIENT_TEXT_AUTOMATION_STATUSES,
                        CLIENT_TEXT_SCENARIOS,
                        ScheduledMessageStateStatus.ACTIVE,
                        ScheduledMessageStateStatus.DONE,
                        0,
                        limit,
                        "desc"
                ).getContent();
        clientMessageOrderStatusService.enrichOrderList(orders);
        return orders.stream()
                .filter(order -> order.getId() == null || !snoozedOrderIds.contains(order.getId()))
                .map(order -> orderExample(order, today, orderManagerReason(order, today), manager))
                .limit(limit)
                .toList();
    }

    private List<ManagerControlConcreteItemResponse> orderStatusExamples(Manager manager, String status, int limit) {
        List<OrderDTOList> orders = orderService.getAllOrderDTOAndKeywordByManager(
                        manager,
                        "",
                        status,
                        0,
                        limit,
                        "desc"
                ).getContent();
        clientMessageOrderStatusService.enrichOrderList(orders);
        return orders.stream()
                .map(order -> orderExample(order, LocalDate.now(), orderManagerReason(order, LocalDate.now()), manager))
                .toList();
    }

    private ManagerControlConcreteItemResponse orderExample(OrderDTOList order, LocalDate today, String reason, Manager manager) {
        LocalDate changed = order.getChanged();
        return new ManagerControlConcreteItemResponse(
                null,
                "ORDER",
                order.getId(),
                safe(order.getCompanyTitle()).isBlank() ? "Заказ #" + order.getId() : order.getCompanyTitle(),
                orderSubtitle(order),
                safe(order.getStatus()),
                changed == null ? null : daysSince(changed, today),
                reason,
                orderTargetUrl(order, manager),
                order.getOrderDetailsId() == null ? null : order.getOrderDetailsId().toString(),
                orderChatUrl(order),
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                orderContactText(order)
        );
    }

    private String orderManagerReason(OrderDTOList order, LocalDate today) {
        String status = safe(order == null ? null : order.getStatus());
        long days = order == null || order.getChanged() == null ? 0 : daysSince(order.getChanged(), today);
        String age = days > 0 ? days + " дн." : "сегодня";
        String controlReason = orderControlReason(order);
        return switch (status) {
            case "На проверке" -> "Клиент не проверил шаблоны " + age
                    + ". " + controlReason + " Скопируйте текст, откройте чат, отправьте ссылку на проверку и нажмите «Отправлено».";
            case "Опубликовано" -> "Заказ опубликован " + age
                    + ", нужна ручная проверка оплаты/счета. " + controlReason + " Отправьте клиенту сообщение или закройте причину.";
            case "Выставлен счет" -> "Оплаты нет. " + controlReason
                    + " Отправьте напоминание клиенту; после отправки заказ уйдет в «Напоминание».";
            case "Напоминание" -> "Клиент не оплатил после напоминания. " + controlReason
                    + " Повторите напоминание или укажите, почему откладываем.";
            case "Не оплачено" -> "Заказ отмечен как неоплаченный " + age
                    + ". " + controlReason + " Проверьте историю общения и решите: повторить контакт, оставить в работе или архивировать.";
            case "В проверку" -> "Доставка/ссылка на проверку зависла " + age
                    + ". " + controlReason + " Проверьте чат и отправку ссылки, затем отметьте действие.";
            case "Новый" -> "Новый заказ без движения " + age
                    + ". " + controlReason + " Проверьте, что клиенту отправлен первый текст/запрос и задача не потерялась.";
            default -> "Статус «" + (status.isBlank() ? "не указан" : status) + "» без движения " + age
                    + ". " + controlReason + " Откройте заказ, проверьте следующий шаг и зафиксируйте действие.";
        };
    }

    private String orderControlReason(OrderDTOList order) {
        if (order == null) {
            return "Почему в контроле: заказ попал в просрочку, но детали автоответчика недоступны.";
        }
        if (order.getClientMessageStatus() != null) {
            String label = safe(order.getClientMessageStatus().label());
            String error = safe(order.getClientMessageStatus().errorMessage());
            if (!error.isBlank()) {
                return clientMessageControlErrorReason(error);
            }
            if (!label.isBlank()) {
                return "Почему в контроле: автоответчик не закрыл задачу — " + label + ".";
            }
        }

        String bindingReason = chatBindingControlReason(order);
        if (!bindingReason.isBlank()) {
            return "Почему в контроле: автоответчик не может отправить сообщение — " + bindingReason + ".";
        }

        String status = safe(order.getStatus());
        if (PAYMENT_AUTOMATION_STATUSES.contains(status)) {
            return "Почему в контроле: для заказа нет активного или успешного автонапоминания об оплате.";
        }
        if (REVIEW_CHECK_AUTOMATION_STATUSES.contains(status)) {
            return "Почему в контроле: для заказа нет активного или успешного автонапоминания о проверке шаблонов.";
        }
        if (DELIVERY_RETRY_AUTOMATION_STATUSES.contains(status)) {
            return "Почему в контроле: для заказа нет активной или успешной автодоставки ссылки.";
        }
        if (CLIENT_TEXT_AUTOMATION_STATUSES.contains(status) && order.isWaitingForClient()) {
            return "Почему в контроле: клиентский текст ожидается, но нет активного или успешного автозапроса.";
        }
        return "Почему в контроле: заказ просрочен, автоматическое действие не найдено или не применимо.";
    }

    private String clientMessageControlErrorReason(String error) {
        String cleaned = safe(error);
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.contains("чат") && lower.contains("не привязан")) {
            return "Почему в контроле: автоответчик не может отправить сообщение — чат компании не привязан к боту. "
                    + "Автопочинка недоступна: привяжите чат компании к боту или отправьте сообщение вручную.";
        }
        return "Почему в контроле: автоответчик не обработал заказ — " + cleaned + ".";
    }

    private String chatBindingControlReason(OrderDTOList order) {
        String chat = safe(order.getCompanyUrlChat()).toLowerCase(Locale.ROOT);
        if (chat.isBlank()) {
            return "";
        }
        if (isWhatsAppChat(chat) && safe(order.getGroupId()).isBlank()) {
            return "WhatsApp-группа из ссылки не привязана к компании";
        }
        if (isTelegramChat(chat) && order.getTelegramGroupChatId() == null) {
            return "Telegram-группа из ссылки не привязана к компании";
        }
        if (isMaxChat(chat) && order.getMaxGroupChatId() == null) {
            return "MAX-группа из ссылки не привязана к компании";
        }
        return "";
    }

    private boolean isWhatsAppChat(String chat) {
        return chat.startsWith("chat.whatsapp.com/")
                || chat.startsWith("https://chat.whatsapp.com/")
                || chat.startsWith("http://chat.whatsapp.com/");
    }

    private boolean isTelegramChat(String chat) {
        return chat.startsWith("t.me/")
                || chat.startsWith("https://t.me/")
                || chat.startsWith("http://t.me/")
                || chat.startsWith("telegram.me/")
                || chat.startsWith("https://telegram.me/")
                || chat.startsWith("http://telegram.me/")
                || chat.startsWith("telegram.dog/")
                || chat.startsWith("https://telegram.dog/")
                || chat.startsWith("http://telegram.dog/")
                || chat.startsWith("tg://resolve?");
    }

    private boolean isMaxChat(String chat) {
        return chat.startsWith("max.ru/")
                || chat.startsWith("https://max.ru/")
                || chat.startsWith("http://max.ru/")
                || chat.startsWith("web.max.ru/")
                || chat.startsWith("https://web.max.ru/")
                || chat.startsWith("http://web.max.ru/");
    }

    private boolean isWorkerTaskConcrete(ManagerDailyControlConcreteItem item) {
        String type = item == null ? "" : safe(item.getEntityType());
        return "BAD_REVIEW_TASK".equals(type) || "RECOVERY_TASK".equals(type);
    }

    private boolean isPublishReviewConcrete(ManagerDailyControlConcreteItem item) {
        return ENTITY_PUBLISH_REVIEW.equals(safe(item == null ? null : item.getEntityType()));
    }

    private boolean isNagulReviewConcrete(ManagerDailyControlConcreteItem item) {
        return ENTITY_NAGUL_REVIEW.equals(safe(item == null ? null : item.getEntityType()));
    }

    private boolean isWorkerFlowOrderConcrete(ManagerDailyControlConcreteItem item) {
        String type = safe(item == null ? null : item.getEntityType());
        return ENTITY_WORKER_ORDER_NEW.equals(type) || ENTITY_WORKER_ORDER_CORRECT.equals(type);
    }

    private boolean isWorkerRiskConcrete(ManagerDailyControlConcreteItem item) {
        return "RISK".equals(safe(item == null ? null : item.getEntityType()));
    }

    private boolean isSpecialistActionConcrete(ManagerDailyControlConcreteItem item) {
        return isWorkerTaskConcrete(item)
                || isPublishReviewConcrete(item)
                || isNagulReviewConcrete(item)
                || isWorkerFlowOrderConcrete(item)
                || isWorkerRiskConcrete(item);
    }

    private LocalDateTime workerTaskFollowUpAt(LocalDateTime now) {
        LocalDateTime base = now == null ? LocalDateTime.now() : now;
        return base.plusHours(WORKER_TASK_FOLLOW_UP_HOURS);
    }

    private String manualWorkerNotificationComment(ManagerDailyControlConcreteItem concreteItem) {
        return "Специалисту отправлен запрос в группу: " + specialistProblemLabel(concreteItem) + ". Повторный контроль через "
                + WORKER_TASK_FOLLOW_UP_HOURS + " ч.";
    }

    private String specialistProblemLabel(ManagerDailyControlConcreteItem concreteItem) {
        return switch (safe(concreteItem == null ? null : concreteItem.getEntityType())) {
            case "RECOVERY_TASK" -> "проверьте восстановление";
            case "BAD_REVIEW_TASK" -> "проверьте плохой отзыв";
            case ENTITY_PUBLISH_REVIEW -> "проверьте публикацию";
            case ENTITY_NAGUL_REVIEW -> "проверьте выгул";
            case ENTITY_WORKER_ORDER_NEW -> "подготовьте текст нового заказа";
            case ENTITY_WORKER_ORDER_CORRECT -> "проверьте коррекцию";
            case "RISK" -> "проверьте открытый риск";
            default -> "проверьте проблему";
        };
    }

    private void clearWorkerTelegramState(ManagerDailyControlConcreteItem concreteItem) {
        concreteItem.setWorkerNotificationAttemptedAt(null);
        concreteItem.setWorkerNotificationSentAt(null);
        concreteItem.setWorkerNotificationAcceptedAt(null);
        concreteItem.setWorkerNotificationAcceptedByUserId(null);
        concreteItem.setWorkerNotificationFailureReason(null);
    }

    private boolean notifyWorkerAboutTaskRequest(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control) {
        User workerUser = workerUserForTask(concreteItem);
        if (workerUser == null || workerUser.getId() == null || concreteItem.getId() == null) {
            concreteItem.setWorkerNotificationFailureReason("Специалист карточки не найден");
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        concreteItem.setWorkerNotificationAttemptedAt(now);
        concreteItem.setWorkerNotificationSentAt(null);
        concreteItem.setWorkerNotificationAcceptedAt(null);
        concreteItem.setWorkerNotificationAcceptedByUserId(null);
        concreteItem.setWorkerNotificationFailureReason(null);
        String managerName = control == null ? "" : managerName(control.getManager());
        String title = workerTaskRequestTitle(concreteItem);
        String text = List.of(
                        "Менеджер запросил действие: " + specialistProblemLabel(concreteItem) + ".",
                        isWorkerRiskConcrete(concreteItem)
                                ? "Статус: принято, нужен комментарий после нажатия кнопки."
                                : "",
                        "Менеджер: " + managerName,
                        "Карточка: " + safe(concreteItem.getTitle()),
                        safe(concreteItem.getSubtitle()),
                        safe(concreteItem.getReason()),
                        safe(concreteItem.getComment())
                ).stream()
                .filter(value -> !safe(value).isBlank())
                .collect(Collectors.joining("\n"));
        if (!personalReminderService.hasOpenSystemReminder(workerUser, SOURCE_WORKER_TASK_REQUEST, concreteItem.getId())) {
            personalReminderService.createSystemReminderDueNow(
                    workerUser,
                    title,
                    text,
                    SOURCE_WORKER_TASK_REQUEST,
                    concreteItem.getId(),
                    orderIdForTask(concreteItem)
            );
        }
        if (workerUser.getWorkerTelegramGroupChatId() == null) {
            concreteItem.setWorkerNotificationFailureReason("Telegram-группа специалиста не привязана");
            return false;
        }
        boolean sent = telegramService.sendMessageWithInlineKeyboard(
                workerUser.getWorkerTelegramGroupChatId(),
                text,
                null,
                List.of(List.of(workerTaskTelegramButton(concreteItem)))
        );
        if (sent) {
            concreteItem.setWorkerNotificationSentAt(now);
            return true;
        } else {
            concreteItem.setWorkerNotificationFailureReason("Telegram не отправил сообщение");
            return false;
        }
    }

    private InlineKeyboardButton workerTaskTelegramButton(ManagerDailyControlConcreteItem concreteItem) {
        if (isWorkerRiskConcrete(concreteItem)) {
            return ManagerControlWorkerTaskTelegramCallbackService.riskExplanationButton(concreteItem.getId());
        }
        return ManagerControlWorkerTaskTelegramCallbackService.acceptButton(concreteItem.getId());
    }

    private String workerTaskRequestTitle(ManagerDailyControlConcreteItem concreteItem) {
        if (ENTITY_WORKER_ORDER_NEW.equals(safe(concreteItem == null ? null : concreteItem.getEntityType()))) {
            return "Подготовьте текст нового заказа";
        }
        return "Проверьте проблему";
    }

    private User workerUserForTask(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getEntityId() == null) {
            return null;
        }
        try {
            return switch (safe(concreteItem.getEntityType())) {
                case "BAD_REVIEW_TASK" -> {
                    BadReviewTask task = badReviewTaskService.getTask(concreteItem.getEntityId());
                    yield task == null || task.getWorker() == null ? null : task.getWorker().getUser();
                }
                case "RECOVERY_TASK" -> {
                    ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(concreteItem.getEntityId());
                    yield task == null || task.getWorker() == null ? null : task.getWorker().getUser();
                }
                case ENTITY_PUBLISH_REVIEW -> {
                    Review review = reviewRepository.findById(concreteItem.getEntityId()).orElse(null);
                    Worker worker = review == null ? null : review.getWorker();
                    if (worker == null) {
                        Order order = reviewOrder(review);
                        worker = order == null ? null : order.getWorker();
                    }
                    yield worker == null ? null : worker.getUser();
                }
                case ENTITY_NAGUL_REVIEW -> {
                    Review review = reviewRepository.findById(concreteItem.getEntityId()).orElse(null);
                    Worker worker = review == null ? null : review.getWorker();
                    if (worker == null) {
                        Order order = reviewOrder(review);
                        worker = order == null ? null : order.getWorker();
                    }
                    yield worker == null ? null : worker.getUser();
                }
                case "ORDER", ENTITY_WORKER_ORDER_NEW, ENTITY_WORKER_ORDER_CORRECT -> {
                    Order order = orderRepository.findById(concreteItem.getEntityId()).orElse(null);
                    yield order == null || order.getWorker() == null ? null : order.getWorker().getUser();
                }
                case "RISK" -> {
                    WorkerRiskIncident incident = riskIncidentRepository.findById(concreteItem.getEntityId()).orElse(null);
                    yield incident == null || incident.getWorkerUserId() == null
                            ? null
                            : userRepository.findById(incident.getWorkerUserId()).orElse(null);
                }
                default -> null;
            };
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String specialistNameForConcreteItem(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null) {
            return "";
        }
        if ("COMMON_INVOICE".equals(safe(concreteItem.getEntityType()))) {
            return commonInvoiceSpecialistName(concreteItem.getEntityId());
        }
        return userDisplayName(workerUserForTask(concreteItem));
    }

    private String commonInvoiceSpecialistName(Long invoiceId) {
        if (invoiceId == null) {
            return "";
        }
        try {
            List<String> names = commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId).stream()
                    .map(CommonInvoiceOrder::getOrder)
                    .map(order -> order == null || order.getWorker() == null ? null : order.getWorker().getUser())
                    .map(this::userDisplayName)
                    .filter(name -> !name.isBlank())
                    .distinct()
                    .toList();
            if (names.size() == 1) {
                return names.getFirst();
            }
            if (names.size() > 1) {
                return names.size() + " специалистов";
            }
            return "";
        } catch (RuntimeException exception) {
            log.warn("Не удалось получить специалистов общего счета invoiceId={}: {}", invoiceId, exception.getMessage());
            return "";
        }
    }

    private String userDisplayName(User user) {
        String fio = safe(user == null ? null : user.getFio());
        if (!fio.isBlank()) {
            return fio;
        }
        return safe(user == null ? null : user.getUsername());
    }

    private Long orderIdForTask(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getEntityId() == null) {
            return null;
        }
        try {
            return switch (safe(concreteItem.getEntityType())) {
                case "BAD_REVIEW_TASK" -> {
                    BadReviewTask task = badReviewTaskService.getTask(concreteItem.getEntityId());
                    yield task == null || task.getOrder() == null ? null : task.getOrder().getId();
                }
                case "RECOVERY_TASK" -> {
                    ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(concreteItem.getEntityId());
                    yield task == null || task.getOrder() == null ? null : task.getOrder().getId();
                }
                case ENTITY_PUBLISH_REVIEW -> {
                    Review review = reviewRepository.findById(concreteItem.getEntityId()).orElse(null);
                    Order order = reviewOrder(review);
                    yield order == null ? null : order.getId();
                }
                case ENTITY_NAGUL_REVIEW -> {
                    Review review = reviewRepository.findById(concreteItem.getEntityId()).orElse(null);
                    Order order = reviewOrder(review);
                    yield order == null ? null : order.getId();
                }
                case ENTITY_WORKER_ORDER_NEW, ENTITY_WORKER_ORDER_CORRECT -> concreteItem.getEntityId();
                case "RISK" -> {
                    WorkerRiskIncident incident = riskIncidentRepository.findById(concreteItem.getEntityId()).orElse(null);
                    yield incident == null ? null : incident.getOrderId();
                }
                default -> null;
            };
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String orderContactText(OrderDTOList order) {
        String status = safe(order.getStatus());
        if (!MANUAL_CONTACT_ORDER_STATUSES.contains(status)) {
            return null;
        }
        if ("Новый".equals(status) && order.isWaitingForClient() && order.getId() != null) {
            return orderRepository.findById(order.getId())
                    .map(this::clientTextContactText)
                    .filter(text -> !safe(text).isBlank())
                    .orElse(null);
        }
        if ("На проверке".equals(status)) {
            if (order.getOrderDetailsId() == null) {
                return null;
            }
            return List.of(
                    orderHeading(order),
                    "Здравствуйте, напоминаем, пожалуйста, проверьте шаблоны отзывов и внесите правки, если они нужны.",
                    "Ссылка на проверку отзывов: " + absoluteAppUrl("/" + order.getOrderDetailsId())
            ).stream().filter(value -> !safe(value).isBlank()).collect(Collectors.joining("\n\n"));
        }
        return paymentContactText(order, status);
    }

    private String paymentContactText(OrderDTOList order, String status) {
        String payText = safe(order.getManagerPayText());
        if (payText.isBlank()) {
            payText = switch (status) {
                case "Опубликовано" -> "Здравствуйте, ваш заказ выполнен, просьба оплатить.";
                case "Не оплачено" -> "Здравствуйте, напоминаем, пожалуйста, по оплате заказа. Пришлите чек, пожалуйста, как оплатите.";
                default -> "Здравствуйте, напоминаем, пожалуйста, об оплате заказа. Пришлите чек, пожалуйста, как оплатите.";
            };
        }
        String amount = money(orderPayableSum(order));
        String body = amount.isBlank() ? payText : payText + " К оплате: " + amount + " руб.";
        return List.of(orderHeading(order), body).stream()
                .filter(value -> !safe(value).isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    private BigDecimal orderPayableSum(OrderDTOList order) {
        if (order == null) {
            return BigDecimal.ZERO;
        }
        if (order.getTotalSumWithBadReviews() != null) {
            return order.getTotalSumWithBadReviews();
        }
        return order.getSum() == null ? BigDecimal.ZERO : order.getSum();
    }

    private String orderHeading(OrderDTOList order) {
        if (order == null) {
            return "";
        }
        return List.of(safe(order.getCompanyTitle()), safe(order.getFilialTitle())).stream()
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(" - "));
    }

    private String absoluteAppUrl(String path) {
        return "https://o-ogo.ru" + (path == null || path.startsWith("/") ? safe(path) : "/" + path);
    }

    private String orderChatUrl(OrderDTOList order) {
        String chat = safe(order.getCompanyUrlChat());
        if (!chat.isBlank()) {
            return chat;
        }
        String phone = safe(order.getCompanyTelephone());
        return phone.isBlank() ? null : "tel:" + phone;
    }

    private String orderTargetUrl(OrderDTOList order, Manager manager) {
        if (order == null) {
            return "/orders";
        }
        String keyword = safe(order.getCompanyTitle());
        if (keyword.isBlank()) {
            keyword = order.getId() == null ? "" : String.valueOf(order.getId());
        }
        List<String> params = new ArrayList<>();
        params.add("status=" + encode(safe(order.getStatus()).isBlank() ? "Все" : order.getStatus()));
        params.add("pageNumber=0");
        params.add("pageSize=10");
        params.add("sortDirection=desc");
        if (!keyword.isBlank()) {
            params.add("keyword=" + encode(keyword));
        }
        if (manager != null && manager.getId() != null) {
            params.add("managerId=" + manager.getId());
        }
        params.add("control=manager-overdue");
        return "/orders?" + String.join("&", params);
    }

    private String orderSubtitle(OrderDTOList order) {
        List<String> parts = new ArrayList<>();
        if (!safe(order.getFilialTitle()).isBlank()) {
            parts.add(order.getFilialTitle());
        }
        if (order.getAmount() != null && order.getAmount() > 0) {
            parts.add(order.getAmount() + " шт.");
        }
        if (order.getSum() != null) {
            parts.add(order.getSum() + " руб.");
        }
        if (order.isWaitingForClient()) {
            parts.add("ждет клиента");
        }
        return String.join(" · ", parts);
    }

    private List<ManagerControlConcreteItemResponse> workerActionExamples(Manager manager, LocalDate today, int limit) {
        List<ManagerControlConcreteItemResponse> examples = new ArrayList<>();
        examples.addAll(workerStaleOrderExamples(manager, "Новый", today, limit));
        examples.addAll(workerStaleOrderExamples(manager, "Коррекция", today, limit));
        examples.addAll(nagulReviewExamples(manager, today, limit));
        examples.addAll(recoveryTaskExamples(manager, today, limit));
        examples.addAll(publishReviewExamples(manager, today, limit));
        examples.addAll(badReviewTaskExamples(manager, today, limit));
        return examples.stream()
                .sorted(Comparator
                        .comparing((ManagerControlConcreteItemResponse item) -> item.ageDays() == null ? 0L : item.ageDays(), Comparator.reverseOrder())
                        .thenComparing(ManagerControlConcreteItemResponse::title, String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .toList();
    }

    private List<ManagerControlConcreteItemResponse> workerStaleOrderExamples(Manager manager, String status, LocalDate today, int limit) {
        List<Long> workerIds = workerIds(manager);
        if (workerIds.isEmpty()) {
            return List.of();
        }
        return workerStaleOrderEntriesForControl(workerIds, status, today).stream()
                .map(entry -> workerStaleOrderExample(entry.order(), status, today, entry.clientTextDecision()))
                .limit(limit)
                .toList();
    }

    private ManagerControlConcreteItemResponse workerStaleOrderExample(
            Order order,
            String status,
            LocalDate today,
            WorkerClientTextDecision clientTextDecision
    ) {
        String entityType = "Коррекция".equals(status) ? ENTITY_WORKER_ORDER_CORRECT : ENTITY_WORKER_ORDER_NEW;
        String contactText = ENTITY_WORKER_ORDER_NEW.equals(entityType) && order != null && order.isWaitingForClient()
                ? clientTextContactText(order)
                : null;
        String reason = clientTextDecision == null || safe(clientTextDecision.reason()).isBlank()
                ? workerOrderReason(order, status, today)
                : clientTextDecision.reason();
        return new ManagerControlConcreteItemResponse(
                null,
                entityType,
                order.getId(),
                orderTitle(order, "Заказ #" + order.getId()),
                workerOrderSubtitle(order, today),
                status,
                daysSince(order.getChanged(), today),
                reason,
                orderTargetUrl(order),
                orderDetailsId(null, order),
                orderChatUrl(order),
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                contactText
        );
    }

    private List<Order> workerStaleOrdersForControl(List<Long> workerIds, String status, LocalDate today) {
        return workerStaleOrderEntriesForControl(workerIds, status, today).stream()
                .map(WorkerOrderControlEntry::order)
                .toList();
    }

    private List<WorkerOrderControlEntry> workerStaleOrderEntriesForControl(List<Long> workerIds, String status, LocalDate today) {
        if (workerIds.isEmpty()) {
            return List.of();
        }
        LocalDate cutoff = managerControlWorkerOrderOverdueDate(today);
        List<Order> orders = "Новый".equals(status)
                ? orderRepository.findManagerControlWorkerNewOrdersForControl(workerIds, cutoff)
                : orderRepository.findManagerControlWorkerStaleOrders(workerIds, status, cutoff);
        Map<Long, List<ScheduledClientMessageState>> statesByOrderId = scheduledStatesByOrderId(orders);
        return orders.stream()
                .map(order -> new WorkerOrderControlEntry(
                        order,
                        workerOrderClientTextDecision(order, status, today, statesByOrderId)
                ))
                .filter(entry -> entry.clientTextDecision().include())
                .toList();
    }

    private String workerOrderSubtitle(Order order, LocalDate today) {
        List<String> parts = new ArrayList<>();
        String workerName = workerName(order == null ? null : order.getWorker());
        if (!workerName.isBlank()) {
            parts.add(workerName);
        }
        if (order != null && order.getChanged() != null) {
            parts.add("без изменений " + daysSince(order.getChanged(), today) + " дн.");
        }
        if (order != null && order.isWaitingForClient()) {
            parts.add("ждет клиента");
        }
        if (order != null && order.getAmount() > 0) {
            parts.add(order.getAmount() + " шт.");
        }
        if (order != null && order.getSum() != null) {
            parts.add(order.getSum() + " руб.");
        }
        return String.join(" · ", parts);
    }

    private String workerOrderReason(Order order, String status, LocalDate today) {
        long days = daysSince(order == null ? null : order.getChanged(), today);
        return "Заказ специалиста в статусе \"" + status + "\" без изменений " + days
                + " дн. Проверьте работу специалиста и устраните просрочку.";
    }

    private WorkerClientTextDecision workerOrderClientTextDecision(
            Order order,
            String status,
            LocalDate today,
            Map<Long, List<ScheduledClientMessageState>> statesByOrderId
    ) {
        if (order == null
                || !"Новый".equals(status)
                || !order.isWaitingForClient()) {
            return WorkerClientTextDecision.includeDefault();
        }

        long days = daysSince(clientTextWaitingControlDate(order), today);
        if (days > ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS) {
            return new WorkerClientTextDecision(
                    true,
                    "Клиент не прислал текст больше "
                            + ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS
                            + " дн., но заказ все еще отмечен как «ждет клиента». Решение: снимите статус \"ждет клиента\"."
            );
        }

        String bindingProblem = clientTextChatBindingProblem(order.getCompany());
        if (!bindingProblem.isBlank()) {
            return new WorkerClientTextDecision(
                    true,
                    "Заказ ждет текст клиента, но автоответчик не отправляет напоминания: "
                            + bindingProblem + ". Проверьте привязку чата или отправьте запрос вручную."
            );
        }

        ScheduledClientMessageState state = currentClientTextReminderState(order, statesByOrderId);
        if (state == null) {
            return new WorkerClientTextDecision(
                    true,
                    "Заказ ждет текст клиента, но автоответчик не отправляет напоминания: нет записи в очереди CLIENT_TEXT_REMINDER."
            );
        }
        if (!clientTextReminderIsHealthy(state)) {
            return new WorkerClientTextDecision(
                    true,
                    "Заказ ждет текст клиента, но автоответчик не отправляет напоминания: "
                            + clientTextReminderProblem(state) + "."
            );
        }

        return WorkerClientTextDecision.suppress();
    }

    private LocalDate clientTextWaitingControlDate(Order order) {
        if (order == null) {
            return LocalDate.now();
        }
        if (order.getChanged() != null) {
            return order.getChanged();
        }
        if (order.getWaitingForClientChangedAt() != null) {
            return order.getWaitingForClientChangedAt().toLocalDate();
        }
        return LocalDate.now();
    }

    private Map<Long, List<ScheduledClientMessageState>> scheduledStatesByOrderId(List<Order> orders) {
        List<Long> orderIds = orders == null
                ? List.of()
                : orders.stream()
                .filter(order -> order != null && order.getId() != null)
                .map(Order::getId)
                .distinct()
                .toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return scheduledClientMessageStateRepository.findByOrderIdIn(orderIds).stream()
                .filter(state -> state.getOrderId() != null)
                .collect(Collectors.groupingBy(ScheduledClientMessageState::getOrderId));
    }

    private ScheduledClientMessageState currentClientTextReminderState(
            Order order,
            Map<Long, List<ScheduledClientMessageState>> statesByOrderId
    ) {
        if (order == null || order.getId() == null) {
            return null;
        }
        String targetKey = clientTextWaitingTargetKey(order);
        return statesByOrderId.getOrDefault(order.getId(), List.of()).stream()
                .filter(state -> state.getScenario() == ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .filter(state -> Objects.equals(targetKey, state.getTargetKey()))
                .max(Comparator
                        .comparingInt(this::clientTextReminderStatePriority)
                        .thenComparing(this::clientTextReminderStateActivity, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(state -> state.getId() == null ? 0L : state.getId()))
                .orElse(null);
    }

    private ScheduledClientMessageState currentOrderAutomationState(
            Order order,
            ClientMessageScenario scenario,
            Map<Long, List<ScheduledClientMessageState>> statesByOrderId
    ) {
        if (order == null || order.getId() == null || scenario == null) {
            return null;
        }
        String targetKey = orderTargetKey(order);
        return statesByOrderId.getOrDefault(order.getId(), List.of()).stream()
                .filter(state -> state.getScenario() == scenario)
                .filter(state -> Objects.equals(targetKey, state.getTargetKey()))
                .max(Comparator
                        .comparingInt(this::clientTextReminderStatePriority)
                        .thenComparing(this::clientTextReminderStateActivity, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(state -> state.getId() == null ? 0L : state.getId()))
                .orElse(null);
    }

    private int clientTextReminderStatePriority(ScheduledClientMessageState state) {
        if (state == null) {
            return 0;
        }
        if (!safe(state.getLastErrorCode()).isBlank() && state.getConsecutiveFailures() > 0) {
            return 40;
        }
        if (state.getLastSuccessAt() != null || state.getSentCount() > 0) {
            return 30;
        }
        if (state.getStatus() == ScheduledMessageStateStatus.ACTIVE && state.getNextAttemptAt() != null) {
            return 20;
        }
        return 10;
    }

    private LocalDateTime clientTextReminderStateActivity(ScheduledClientMessageState state) {
        if (state == null) {
            return null;
        }
        if (state.getUpdatedAt() != null) {
            return state.getUpdatedAt();
        }
        if (state.getLastAttemptAt() != null) {
            return state.getLastAttemptAt();
        }
        if (state.getLastSuccessAt() != null) {
            return state.getLastSuccessAt();
        }
        return state.getNextAttemptAt();
    }

    private boolean clientTextReminderIsHealthy(ScheduledClientMessageState state) {
        if (state == null) {
            return false;
        }
        if (state.getStatus() == ScheduledMessageStateStatus.DISABLED || state.getStatus() == ScheduledMessageStateStatus.PAUSED) {
            return false;
        }
        String errorCode = safe(state.getLastErrorCode()).toLowerCase(Locale.ROOT);
        if (!errorCode.isBlank()
                && !errorCode.contains("dry_run")
                && !errorCode.contains("client_text_received")
                && !errorCode.contains("client_text_cycle_changed")
                && !errorCode.contains("order_status_changed")
                && !errorCode.contains("status_change")) {
            return false;
        }
        return state.getSentCount() > 0
                || state.getLastSuccessAt() != null
                || (state.getStatus() == ScheduledMessageStateStatus.ACTIVE && state.getNextAttemptAt() != null);
    }

    private String clientTextReminderProblem(ScheduledClientMessageState state) {
        if (state == null) {
            return "нет записи в очереди CLIENT_TEXT_REMINDER";
        }
        if (!safe(state.getLastErrorMessage()).isBlank()) {
            return state.getLastErrorMessage();
        }
        if (!safe(state.getLastErrorCode()).isBlank()) {
            return "ошибка " + state.getLastErrorCode();
        }
        if (state.getStatus() == ScheduledMessageStateStatus.DISABLED) {
            return "очередь автоответчика отключена";
        }
        if (state.getStatus() == ScheduledMessageStateStatus.PAUSED) {
            return "очередь автоответчика на паузе";
        }
        return "нет активной успешной или запланированной отправки";
    }

    private String clientTextWaitingTargetKey(Order order) {
        return "client-text:" + order.getId() + ":" + clientTextWaitingChangedAt(order).withNano(0);
    }

    private String orderTargetKey(Order order) {
        return "order:" + order.getId() + ":" + orderStatusChangedAt(order).withNano(0);
    }

    private LocalDateTime clientTextWaitingChangedAt(Order order) {
        if (order.getWaitingForClientChangedAt() != null) {
            return order.getWaitingForClientChangedAt();
        }
        if (order.getStatusChangedAt() != null) {
            return order.getStatusChangedAt();
        }
        if (order.getChanged() != null) {
            return order.getChanged().atStartOfDay();
        }
        return LocalDateTime.now().withNano(0);
    }

    private LocalDateTime orderStatusChangedAt(Order order) {
        if (order.getStatusChangedAt() != null) {
            return order.getStatusChangedAt();
        }
        if (order.getChanged() != null) {
            return order.getChanged().atStartOfDay();
        }
        if (order.getCreated() != null) {
            return order.getCreated().atStartOfDay();
        }
        return LocalDateTime.now().withNano(0);
    }

    private String clientTextChatBindingProblem(Company company) {
        if (company == null) {
            return "компания не найдена";
        }
        String chat = safe(company.getUrlChat()).toLowerCase(Locale.ROOT);
        if (chat.isBlank()) {
            return "у компании не указан чат";
        }
        if (isWhatsAppChat(chat) && safe(company.getGroupId()).isBlank()) {
            return "WhatsApp-группа не привязана к боту";
        }
        if (isTelegramChat(chat) && company.getTelegramGroupChatId() == null) {
            return "Telegram-группа не привязана к боту";
        }
        if (isMaxChat(chat) && company.getMaxGroupChatId() == null) {
            return "MAX-группа не привязана к боту";
        }
        if (!isWhatsAppChat(chat) && !isTelegramChat(chat) && !isMaxChat(chat)) {
            return "чат компании не распознан";
        }
        return "";
    }

    private List<ManagerControlConcreteItemResponse> recoveryTaskExamples(Manager manager, LocalDate today, int limit) {
        return reviewRecoveryTaskService.getDueTasksToManager(
                        manager,
                        managerControlWorkerTaskOverdueDate(today),
                        "",
                        PageRequest.of(0, Math.max(1, limit))
                ).getContent().stream()
                .map(task -> recoveryTaskExample(task, today))
                .toList();
    }

    private ManagerControlConcreteItemResponse recoveryTaskExample(ReviewRecoveryTask task, LocalDate today) {
        Order order = task.getOrder();
        return new ManagerControlConcreteItemResponse(
                null,
                "RECOVERY_TASK",
                task.getId(),
                orderTitle(order, "Восстановление #" + task.getId()),
                taskSubtitle("Восстановление", task.getWorker(), task.getScheduledDate(), today),
                task.getStatus() == null ? null : task.getStatus().name(),
                daysSince(task.getScheduledDate(), today),
                "Задача восстановления требует проверки менеджера",
                orderTargetUrl(order),
                null,
                orderChatUrl(order),
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private List<ManagerControlConcreteItemResponse> nagulReviewExamples(Manager manager, LocalDate today, int limit) {
        List<Long> workerIds = workerIds(manager);
        if (workerIds.isEmpty()) {
            return List.of();
        }
        return reviewRepository.findManagerControlNagulReviewsByWorkerIds(
                        workerIds,
                        managerControlPublicationOverdueDate(today),
                        PageRequest.of(0, Math.max(1, limit))
                ).stream()
                .map(review -> nagulReviewExample(review, today))
                .toList();
    }

    private ManagerControlConcreteItemResponse nagulReviewExample(Review review, LocalDate today) {
        Order order = reviewOrder(review);
        return new ManagerControlConcreteItemResponse(
                null,
                ENTITY_NAGUL_REVIEW,
                review.getId(),
                orderTitle(order, "Отзыв #" + review.getId()),
                taskSubtitle("Выгул", review.getWorker(), review.getPublishedDate(), today),
                "Выгул",
                daysSince(review.getPublishedDate(), today),
                nagulReviewReason(review, today),
                orderTargetUrl(order),
                review.getOrderDetails() == null || review.getOrderDetails().getId() == null
                        ? null
                        : review.getOrderDetails().getId().toString(),
                orderChatUrl(order),
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private String nagulReviewReason(Review review, LocalDate today) {
        long days = daysSince(review == null ? null : review.getPublishedDate(), today);
        return "Выгул просрочен " + days + " дн. Проверьте карточку отзыва и специалиста.";
    }

    private List<ManagerControlConcreteItemResponse> publishReviewExamples(Manager manager, LocalDate today, int limit) {
        List<Long> workerIds = workerIds(manager);
        if (workerIds.isEmpty()) {
            return List.of();
        }
        return reviewRepository.findManagerControlPublishReviewsByWorkerIds(
                        workerIds,
                        managerControlPublicationOverdueDate(today),
                        PageRequest.of(0, Math.max(1, limit))
                ).stream()
                .map(review -> publishReviewExample(review, today))
                .toList();
    }

    private ManagerControlConcreteItemResponse publishReviewExample(Review review, LocalDate today) {
        Order order = reviewOrder(review);
        return new ManagerControlConcreteItemResponse(
                null,
                ENTITY_PUBLISH_REVIEW,
                review.getId(),
                orderTitle(order, "Отзыв #" + review.getId()),
                publishReviewSubtitle(review, order, today),
                "Публикация",
                daysSince(review.getPublishedDate(), today),
                publishReviewReason(review, today),
                orderTargetUrl(order),
                review.getOrderDetails() == null || review.getOrderDetails().getId() == null
                        ? null
                        : review.getOrderDetails().getId().toString(),
                orderChatUrl(order),
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private Order reviewOrder(Review review) {
        return review == null || review.getOrderDetails() == null ? null : review.getOrderDetails().getOrder();
    }

    private String publishReviewSubtitle(Review review, Order order, LocalDate today) {
        List<String> parts = new ArrayList<>();
        String workerName = workerName(review == null ? null : review.getWorker());
        if (!workerName.isBlank()) {
            parts.add(workerName);
        }
        if (review != null && review.getPublishedDate() != null) {
            parts.add("план " + review.getPublishedDate());
            long days = daysSince(review.getPublishedDate(), today);
            if (days > 0) {
                parts.add(days + " дн.");
            }
        }
        if (order != null && order.getStatus() != null && !safe(order.getStatus().getTitle()).isBlank()) {
            parts.add("заказ " + order.getStatus().getTitle());
        }
        return String.join(" · ", parts);
    }

    private String publishReviewReason(Review review, LocalDate today) {
        long days = daysSince(review == null ? null : review.getPublishedDate(), today);
        String overdue = days > 0 ? days + " дн." : "сегодня";
        return "Публикация просрочена " + overdue + ". Проверьте карточку отзыва и специалиста.";
    }

    private List<ManagerControlConcreteItemResponse> badReviewTaskExamples(Manager manager, LocalDate today, int limit) {
        return badReviewTaskService.getDueTasksToManager(
                        manager,
                        managerControlWorkerTaskOverdueDate(today),
                        "",
                        PageRequest.of(0, Math.max(1, limit))
                ).getContent().stream()
                .map(task -> badReviewTaskExample(task, today))
                .toList();
    }

    private ManagerControlConcreteItemResponse badReviewTaskExample(BadReviewTask task, LocalDate today) {
        Order order = task.getOrder();
        return new ManagerControlConcreteItemResponse(
                null,
                "BAD_REVIEW_TASK",
                task.getId(),
                orderTitle(order, "Плохой отзыв #" + task.getId()),
                taskSubtitle("Плохие", task.getWorker(), task.getScheduledDate(), today),
                task.getStatus() == null ? null : task.getStatus().name(),
                daysSince(task.getScheduledDate(), today),
                "Задача по плохому отзыву требует проверки менеджера",
                orderTargetUrl(order),
                null,
                orderChatUrl(order),
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private String taskSubtitle(String type, Worker worker, LocalDate scheduledDate, LocalDate today) {
        List<String> parts = new ArrayList<>();
        parts.add(type);
        String workerName = workerName(worker);
        if (!workerName.isBlank()) {
            parts.add(workerName);
        }
        if (scheduledDate != null) {
            parts.add("план " + scheduledDate);
            long days = daysSince(scheduledDate, today);
            if (days > 0) {
                parts.add(days + " дн.");
            }
        }
        return String.join(" · ", parts);
    }

    private String workerName(Worker worker) {
        if (worker == null || worker.getUser() == null) {
            return "";
        }
        String fio = safe(worker.getUser().getFio());
        return fio.isBlank() ? safe(worker.getUser().getUsername()) : fio;
    }

    private String companyWorkerName(Company company) {
        if (company == null || company.getWorkers() == null || company.getWorkers().isEmpty()) {
            return "Исполнитель не назначен";
        }
        return company.getWorkers().stream()
                .map(this::workerName)
                .filter(value -> !safe(value).isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .findFirst()
                .orElse("Исполнитель не назначен");
    }

    private String orderTitle(Order order, String fallback) {
        if (order == null) {
            return fallback;
        }
        String company = order.getCompany() == null ? "" : safe(order.getCompany().getTitle());
        String filial = order.getFilial() == null ? "" : safe(order.getFilial().getTitle());
        String title = List.of(company, filial).stream()
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(" - "));
        return title.isBlank() ? "Заказ #" + order.getId() : title;
    }

    private String orderTargetUrl(Order order) {
        if (order == null || order.getId() == null) {
            return "/worker";
        }
        Long companyId = order.getCompany() == null ? null : order.getCompany().getId();
        if (companyId != null) {
            return "/orders/" + companyId + "/" + order.getId();
        }
        return "/orders?keyword=" + encode(String.valueOf(order.getId()));
    }

    private String orderChatUrl(Order order) {
        if (order == null || order.getCompany() == null) {
            return null;
        }
        String chat = safe(order.getCompany().getUrlChat());
        if (!chat.isBlank()) {
            return chat;
        }
        String phone = safe(order.getCompany().getTelephone());
        return phone.isBlank() ? null : "tel:" + phone;
    }

    private List<ManagerControlConcreteItemResponse> riskExamples(Manager manager, int limit) {
        List<Long> userIds = workerUserIds(manager);
        if (userIds.isEmpty()) {
            return List.of();
        }
        return riskIncidentRepository.findByWorkerUserIdInAndStatusOrderByCreatedAtDesc(
                        userIds,
                        WorkerRiskIncidentStatus.OPEN,
                        PageRequest.of(0, limit)
                ).getContent().stream()
                .map(this::riskExample)
                .toList();
    }

    private ManagerControlConcreteItemResponse riskExample(WorkerRiskIncident incident) {
        Long targetId = incident.getOrderId() != null ? incident.getOrderId() : incident.getEntityId();
        return new ManagerControlConcreteItemResponse(
                null,
                "RISK",
                incident.getId(),
                safe(incident.getTitle()).isBlank() ? "Риск специалиста #" + incident.getId() : incident.getTitle(),
                safe(incident.getWorkerName()).isBlank() ? incident.getWorkerUsername() : incident.getWorkerName(),
                incident.getLevel() == null ? null : incident.getLevel().name(),
                incident.getCreatedAt() == null ? null : Math.max(0, ChronoUnit.DAYS.between(incident.getCreatedAt().toLocalDate(), LocalDate.now())),
                limit(safe(incident.getMessage()), 500),
                targetId == null ? "/worker/risk" : "/worker/risk?targetId=" + targetId,
                null,
                null,
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                null,
                incident.getResolutionAction() == null ? null : incident.getResolutionAction().name(),
                incident.getWorkerExplanation(),
                incident.getWorkerExplanationAt(),
                incident.getPenaltyPoints(),
                incident.getRollbackStatus() == null ? null : incident.getRollbackStatus().name(),
                incident.getRollbackMessage(),
                canRollbackRiskIncident(incident),
                safe(incident.getWorkerName()).isBlank() ? incident.getWorkerUsername() : incident.getWorkerName()
        );
    }

    private boolean canRollbackRiskIncident(WorkerRiskIncident incident) {
        if (incident == null
                || incident.getStatus() != WorkerRiskIncidentStatus.VIOLATION
                || incident.getRollbackStatus() != null) {
            return false;
        }
        return "BAD_TASK_COMPLETE".equals(incident.getAction())
                || "RECOVERY_TASK_COMPLETE".equals(incident.getAction());
    }

    private long commonInvoiceActionCount(Manager manager) {
        return commonInvoiceRepository.countManagerControlInvoices(
                manager,
                COMMON_INVOICE_CRITICAL_STATUSES,
                COMMON_INVOICE_STALE_STATUSES,
                LocalDateTime.now().minusDays(COMMON_INVOICE_STALE_DAYS)
        );
    }

    private List<Company> telegramChatIssueCompanies(Manager manager, int limit) {
        Map<Long, Company> companies = new LinkedHashMap<>();
        companyRepository.findTelegramChatIssueCompanies(manager, PageRequest.of(0, Math.max(1, limit)))
                .forEach(company -> addTelegramIssueCompany(companies, company));
        if (companies.size() < limit) {
            paymentLinkRepository.findTelegramSuccessNotificationErrorsByManager(manager).stream()
                    .map(PaymentLink::getOrder)
                    .filter(Objects::nonNull)
                    .map(Order::getCompany)
                    .filter(Objects::nonNull)
                    .limit(Math.max(0, limit - companies.size()))
                    .forEach(company -> addTelegramIssueCompany(companies, company));
        }
        return new ArrayList<>(companies.values());
    }

    private void addTelegramIssueCompany(Map<Long, Company> companies, Company company) {
        if (company == null || company.getId() == null || company.getTelegramGroupChatId() == null) {
            return;
        }
        companies.putIfAbsent(company.getId(), company);
    }

    private List<ManagerControlConcreteItemResponse> telegramChatIssueExamples(Manager manager, int limit) {
        return telegramChatIssueCompanies(manager, limit).stream()
                .map(company -> telegramChatIssueExample(manager, company))
                .toList();
    }

    private ManagerControlConcreteItemResponse telegramChatIssueExample(Manager manager, Company company) {
        String specialistName = companyWorkerName(company);
        return new ManagerControlConcreteItemResponse(
                null,
                ENTITY_TELEGRAM_CHAT,
                company.getId(),
                safe(company.getTitle()).isBlank() ? "Компания #" + company.getId() : company.getTitle(),
                specialistName,
                "Telegram",
                null,
                "Telegram-отправка по компании получила ошибку. Если группа стала супергруппой, нажмите «Починить»: система запросит новый chat_id у Telegram и обновит привязку.",
                companyTargetUrl(manager, company),
                null,
                normalizedChatUrl(company.getUrlChat()),
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                specialistName
        );
    }

    private List<ManagerControlConcreteItemResponse> unansweredClientMessageExamples(Manager manager, int limit) {
        return clientChatMessageTrackerService.dueExamples(manager, limit).stream()
                .map(this::unansweredClientMessageExample)
                .toList();
    }

    private ManagerControlConcreteItemResponse unansweredClientMessageExample(ClientChatUnansweredExample example) {
        String companyTitle = safe(example.companyTitle()).isBlank()
                ? "Компания не определена"
                : example.companyTitle();
        String sender = safe(example.senderName()).isBlank() ? "Клиент" : example.senderName();
        String waiting = waitingLabel(example.waitingMinutes());
        return new ManagerControlConcreteItemResponse(
                null,
                ENTITY_CLIENT_CHAT_UNANSWERED,
                example.id(),
                companyTitle,
                platformLabel(example.platform()) + " · " + safe(example.chatTitle()),
                waiting,
                Math.max(0, example.waitingMinutes() / (60L * 24L)),
                sender + " написал " + waiting + ". Последнее сообщение: " + compact(example.lastMessageText(), 260),
                example.targetUrl(),
                null,
                example.chatUrl(),
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                compact(example.lastMessageText(), 1000),
                example.specialistName()
        );
    }

    private String platformLabel(com.hunt.otziv.client_chat_control.model.ClientChatPlatform platform) {
        if (platform == null) {
            return "Чат";
        }
        return switch (platform) {
            case TELEGRAM -> "Telegram";
            case WHATSAPP -> "WhatsApp";
            case MAX -> "MAX";
        };
    }

    private String waitingLabel(long minutes) {
        long safeMinutes = Math.max(0, minutes);
        if (safeMinutes < 60) {
            return safeMinutes + " мин. без ответа";
        }
        long hours = safeMinutes / 60;
        long restMinutes = safeMinutes % 60;
        if (hours < 24) {
            return restMinutes == 0
                    ? hours + " ч. без ответа"
                    : hours + " ч. " + restMinutes + " мин. без ответа";
        }
        long days = hours / 24;
        long restHours = hours % 24;
        return restHours == 0
                ? days + " дн. без ответа"
                : days + " дн. " + restHours + " ч. без ответа";
    }

    private String companyTargetUrl(Manager manager, Company company) {
        StringBuilder url = new StringBuilder(ordersUrl(manager, null));
        String title = safe(company == null ? null : company.getTitle());
        if (!title.isBlank()) {
            url.append("&keyword=").append(encode(title));
        }
        return url.toString();
    }

    private List<ManagerControlConcreteItemResponse> commonInvoiceExamples(Manager manager, LocalDate today, int limit) {
        return commonInvoiceRepository.findManagerControlInvoices(
                        manager,
                        COMMON_INVOICE_CRITICAL_STATUSES,
                        COMMON_INVOICE_STALE_STATUSES,
                        LocalDateTime.now().minusDays(COMMON_INVOICE_STALE_DAYS),
                        PageRequest.of(0, limit)
                ).stream()
                .map(invoice -> commonInvoiceExample(invoice, today))
                .toList();
    }

    private ManagerControlConcreteItemResponse commonInvoiceExample(CommonInvoice invoice, LocalDate today) {
        String accountName = invoice.getAccount() == null ? "" : safe(invoice.getAccount().getName());
        long remainingKopecks = Math.max(0, invoice.getAmountKopecks() - invoice.getPaidKopecks());
        return new ManagerControlConcreteItemResponse(
                null,
                "COMMON_INVOICE",
                invoice.getId(),
                safe(invoice.getTitle()).isBlank() ? "Общий счет #" + invoice.getId() : invoice.getTitle(),
                commonInvoiceSubtitle(accountName, invoice.getAmountKopecks(), remainingKopecks),
                commonInvoiceStatusLabel(invoice.getStatus()),
                invoice.getUpdatedAt() == null ? null : daysSince(invoice.getUpdatedAt().toLocalDate(), today),
                commonInvoiceReason(invoice, today),
                "/admin/common-billing?invoiceId=" + invoice.getId(),
                null,
                null,
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private String commonInvoiceSubtitle(String accountName, long amountKopecks, long remainingKopecks) {
        List<String> parts = new ArrayList<>();
        if (!accountName.isBlank()) {
            parts.add(accountName);
        }
        parts.add("сумма " + rubles(amountKopecks));
        if (remainingKopecks > 0) {
            parts.add("остаток " + rubles(remainingKopecks));
        }
        return String.join(" · ", parts);
    }

    private String commonInvoiceReason(CommonInvoice invoice, LocalDate today) {
        String lastError = safe(invoice.getLastError());
        if (!lastError.isBlank()) {
            return commonInvoiceLastErrorReason(invoice, lastError);
        }
        String notificationError = safe(invoice.getPaymentSuccessNotificationError());
        if (!notificationError.isBlank()) {
            return commonInvoicePaymentNotificationReason(invoice, notificationError);
        }
        CommonInvoiceStatus status = invoice.getStatus();
        if (status == CommonInvoiceStatus.NEEDS_ATTENTION) {
            return "Счет требует ручного разбора. Рекомендация: откройте «Счет», проверьте позиции и выберите подходящее действие в правой панели.";
        }
        if (status == CommonInvoiceStatus.UNPAID) {
            return "Счет переведен в «Не оплачено». Рекомендация: проверьте, нужно ли вернуть позиции в работу или закрыть карточку контроля.";
        }
        if (status == CommonInvoiceStatus.BAN) {
            return "Счет в бане. Рекомендация: проверьте причину блокировки в карточке счета.";
        }
        long ageDays = invoice.getUpdatedAt() == null ? 0 : daysSince(invoice.getUpdatedAt().toLocalDate(), today);
        return "Счет завис в статусе «" + commonInvoiceStatusLabel(status) + "» " + ageDays
                + " дн. Рекомендация: откройте «Счет» и проверьте следующий шаг оплаты.";
    }

    private String commonInvoiceLastErrorReason(CommonInvoice invoice, String rawError) {
        String error = safe(rawError).toLowerCase(Locale.ROOT);
        if (error.startsWith("manual_fix:") && error.contains("moved_to_invoice_")) {
            String targetInvoice = valueAfter(error, "moved_to_invoice_");
            return "Заказ уже перенесен в другой общий счет"
                    + (targetInvoice.isBlank() ? "" : " #" + targetInvoice)
                    + ". Это технический хвост старого счета. Рекомендация: нажмите «Починить», чтобы скрыть старую карточку из контроля.";
        }
        if (error.startsWith("merged_into:")) {
            String targetInvoice = valueAfter(error, "common_invoice_");
            return "Этот общий счет объединен с другим счетом"
                    + (targetInvoice.isBlank() ? "" : " #" + targetInvoice)
                    + ". Рекомендация: нажмите «Починить», чтобы скрыть старую карточку из контроля.";
        }
        if (error.startsWith("empty:")) {
            return "В общем счете больше нет заказов. Рекомендация: нажмите «Починить», чтобы убрать пустой счет из контроля.";
        }
        if (error.startsWith("disabled:")) {
            return "Общий счет отключен. Рекомендация: нажмите «Починить», если в нем не осталось неоплаченных позиций.";
        }
        if (error.startsWith("whatsapp_group_missing") || error.contains("whatsapp-групп")) {
            return commonInvoiceWhatsappGroupMissingReason(invoice, false);
        }
        if (error.startsWith("auto_send_disabled")) {
            return "Автоматическая отправка клиентских сообщений выключена. Рекомендация: включите моментальные сообщения или обработайте счет вручную.";
        }
        if (error.startsWith("message_send_stale") || error.startsWith("message_send_in_progress")) {
            return "Отправка сообщения по счету зависла. Рекомендация: откройте «Счет» и повторите отправку вручную.";
        }
        if (error.startsWith("payment_init")) {
            return "Проблема при создании платежной ссылки T-Bank. Рекомендация: откройте «Счет» и сверьте состояние платежа в банке.";
        }
        if (error.startsWith("close_failed")) {
            return "Оплата получена, но часть заказов не закрылась. Рекомендация: исправьте заказы и повторите действие в карточке счета.";
        }
        if (error.startsWith("next_order_failed")) {
            return "Платеж закрыт, но следующие заказы не создались. Рекомендация: откройте «Счет» и повторите создание следующих заказов.";
        }
        if (commonInvoiceTechnicalTailRepairable(invoice)) {
            return "У общего счета остался технический хвост. Рекомендация: нажмите «Починить», чтобы скрыть старую карточку из контроля.";
        }
        return "Ошибка общего счета: " + limit(rawError, 160)
                + ". Рекомендация: откройте «Счет» и проверьте причину вручную.";
    }

    private String commonInvoicePaymentNotificationReason(CommonInvoice invoice, String rawError) {
        String error = safe(rawError).toLowerCase(Locale.ROOT);
        if (error.startsWith("immediate_messages_disabled")) {
            return "Уведомление об оплате не отправлено: моментальные клиентские сообщения выключены. Рекомендация: включите отправку или нажмите «Починить», чтобы закрыть эту ошибку.";
        }
        if (error.startsWith("whatsapp_group_missing") || error.contains("groupid")) {
            return commonInvoiceWhatsappGroupMissingReason(invoice, true);
        }
        return "Ошибка уведомления об оплате: " + limit(rawError, 160)
                + ". Рекомендация: проверьте сообщение клиенту или нажмите «Починить», чтобы закрыть ошибку уведомления.";
    }

    private String commonInvoiceWhatsappGroupMissingReason(CommonInvoice invoice, boolean paymentNotification) {
        CommonInvoiceChatBinding binding = commonInvoiceChatBinding(invoice);
        Company primaryCompany = binding.primaryCompany();
        Company linkedCompanyWithGroup = binding.linkedCompanyWithGroup();
        String primaryName = safe(primaryCompany == null ? null : primaryCompany.getTitle());
        String prefix = paymentNotification
                ? "Уведомление об оплате не отправлено"
                : "Сообщение общего счета не отправлено";

        if (hasText(primaryCompany == null ? null : primaryCompany.getGroupId())) {
            return prefix + ": у компании"
                    + (primaryName.isBlank() ? "" : " «" + primaryName + "»")
                    + " сейчас уже есть groupId, но в общем счете осталась старая ошибка WhatsApp. "
                    + "Рекомендация: повторите отправку из «Счета» или нажмите «Починить», если сообщение уже отправлено вручную или больше не нужно.";
        }

        if (linkedCompanyWithGroup != null) {
            String linkedName = safe(linkedCompanyWithGroup.getTitle());
            return prefix + ": у главной компании общего счета"
                    + (primaryName.isBlank() ? "" : " «" + primaryName + "»")
                    + " нет groupId. У связанной компании"
                    + (linkedName.isBlank() ? "" : " «" + linkedName + "»")
                    + " groupId есть, но общий счет отправляется через главную компанию. "
                    + "Рекомендация: привяжите WhatsApp-группу главной компании к боту или смените главную компанию счета"
                    + (paymentNotification ? ", либо нажмите «Починить», если уведомление уже не нужно." : ".");
        }

        return prefix + ": у WhatsApp-группы главной компании общего счета не задан groupId. "
                + "Рекомендация: откройте «Счет», затем заказ/компанию и привяжите WhatsApp-группу к боту"
                + (paymentNotification ? ", либо нажмите «Починить», если уведомление уже не нужно." : ".");
    }

    private CommonInvoiceChatBinding commonInvoiceChatBinding(CommonInvoice invoice) {
        List<CommonInvoiceOrder> items = invoice == null || invoice.getId() == null
                ? List.of()
                : commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        Company primaryCompany = commonInvoicePrimaryChatCompany(invoice, items);
        Long primaryCompanyId = primaryCompany == null ? null : primaryCompany.getId();
        Company linkedCompanyWithGroup = items.stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getCompany)
                .filter(Objects::nonNull)
                .filter(company -> company.getId() != null && !Objects.equals(company.getId(), primaryCompanyId))
                .filter(company -> hasText(company.getGroupId()))
                .findFirst()
                .orElse(null);
        return new CommonInvoiceChatBinding(primaryCompany, linkedCompanyWithGroup);
    }

    private Company commonInvoicePrimaryChatCompany(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null) {
            return null;
        }
        if (invoice.getAccount() != null && invoice.getAccount().getInvoiceCompany() != null) {
            return invoice.getAccount().getInvoiceCompany();
        }
        return (items == null ? List.<CommonInvoiceOrder>of() : items).stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getCompany)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean commonInvoiceTechnicalTailRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.DISABLED
                && (error.startsWith("disabled:")
                || error.startsWith("empty:")
                || error.startsWith("merged_into:")
                || error.startsWith("manual_fix:"));
    }

    private boolean commonInvoiceWhatsappGroupTailRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        if (invoice == null || !(error.startsWith("whatsapp_group_missing") || error.contains("whatsapp-групп"))) {
            return false;
        }
        CommonInvoiceChatBinding binding = commonInvoiceChatBinding(invoice);
        return hasText(binding.primaryCompany() == null
                ? null
                : binding.primaryCompany().getGroupId());
    }

    private boolean commonInvoicePaymentNotificationRepairable(CommonInvoice invoice) {
        return !safe(invoice == null ? null : invoice.getPaymentSuccessNotificationError()).isBlank();
    }

    private boolean hasText(String value) {
        return !safe(value).isBlank();
    }

    private record CommonInvoiceChatBinding(Company primaryCompany, Company linkedCompanyWithGroup) {
    }

    private String valueAfter(String value, String marker) {
        int index = safe(value).indexOf(marker);
        if (index < 0) {
            return "";
        }
        String suffix = value.substring(index + marker.length()).trim();
        int end = 0;
        while (end < suffix.length() && Character.isDigit(suffix.charAt(end))) {
            end++;
        }
        return end == 0 ? "" : suffix.substring(0, end);
    }

    private String commonInvoiceStatusLabel(CommonInvoiceStatus status) {
        if (status == null) {
            return "Без статуса";
        }
        return switch (status) {
            case COLLECTING -> "Сбор";
            case READY -> "Готов к счету";
            case INVOICED -> "Выставлен счет";
            case REMINDER -> "Напоминание";
            case PARTIALLY_PAID -> "Частично оплачен";
            case NEEDS_ATTENTION -> "Требует внимания";
            case PAID -> "Оплачен";
            case UNPAID -> "Не оплачен";
            case BAN -> "Бан";
            case DISABLED -> "Отключен";
        };
    }

    private String rubles(long kopecks) {
        return (kopecks / 100) + " руб.";
    }

    private String money(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return "";
        }
        BigDecimal value = amount.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    private String reasonLabel(ManagerDailyControlItem item) {
        if (item.getItemType() == ManagerDailyControlItemType.ORDER_STATUS) {
            return "Просрочка в статусе заказа";
        }
        if (item.getItemType() == ManagerDailyControlItemType.WORKER_SECTION) {
            return item.getGroup() == ManagerDailyControlGroup.ACTION
                    ? "Раздел специалиста требует действия"
                    : "Рабочая нагрузка специалиста";
        }
        return switch (safe(item.getReasonCode())) {
            case "OVERDUE_ORDERS" -> "Есть заказы без нужного действия";
            case "OPEN_RISKS" -> "Есть открытые риски специалистов";
            case "REQUIRES_ATTENTION" -> "Есть заказы в статусе требует внимания";
            case "COMMON_INVOICES" -> "Есть общие счета с ошибкой или зависшим статусом";
            case "WORKER_ACTIONS" -> "Есть задачи специалистов, которые надо разобрать";
            case "ORDERS_WORKLOAD" -> "Общий объем рабочих заказов";
            case "WORKER_WORKLOAD" -> "Нагрузка специалистов";
            default -> item.getLabel();
        };
    }

    private int detailItemRank(ManagerDailyControlItem item) {
        return detailWorkflowRank(item) * 10 + detailStateRank(item);
    }

    private int detailWorkflowRank(ManagerDailyControlItem item) {
        if (item == null) {
            return 999;
        }
        String reason = safe(item.getReasonCode());
        String section = safe(item.getSectionCode());
        if ("new_overdue".equals(section)) {
            return 10;
        }
        if (item.getItemType() == ManagerDailyControlItemType.ORDER_STATUS) {
            return 10 + orderStatusDisplayRank(reason) * 10;
        }
        if ("REQUIRES_ATTENTION".equals(reason)) {
            return 10 + orderStatusDisplayRank("Требует внимания") * 10;
        }
        if ("correct_overdue".equals(section)) {
            return 10 + orderStatusDisplayRank("Коррекция") * 10;
        }
        if ("nagul_overdue".equals(section)) {
            return 45;
        }
        if ("recovery".equals(section)) {
            return 48;
        }
        if ("publish".equals(section)) {
            return 50;
        }
        if ("bad".equals(section)) {
            return 55;
        }
        if ("COMMON_INVOICES".equals(reason)) {
            return 75;
        }
        if ("OPEN_RISKS".equals(reason) || "risk".equals(section)) {
            return 150;
        }
        if ("WORKER_ACTIONS".equals(reason)) {
            return 160;
        }
        if ("OVERDUE_ORDERS".equals(reason)) {
            return 170;
        }
        if ("ORDERS_WORKLOAD".equals(reason) || "WORKER_WORKLOAD".equals(reason)) {
            return 900;
        }
        if (item.getGroup() == ManagerDailyControlGroup.WORKLOAD) {
            return 910 + workloadSectionRank(section);
        }
        return item.getGroup() == ManagerDailyControlGroup.ACTION ? 800 : 950;
    }

    private int workloadSectionRank(String section) {
        return switch (safe(section)) {
            case "new" -> 0;
            case "correct" -> 1;
            case "nagul" -> 2;
            default -> 20;
        };
    }

    private int detailStateRank(ManagerDailyControlItem item) {
        if (isOpenActionItem(item)) {
            return 0;
        }
        if (isHandledActionItem(item)) {
            return 1;
        }
        if (item != null && item.getGroup() == ManagerDailyControlGroup.ACTION) {
            return 2;
        }
        return 5;
    }

    private String problemKey(String code) {
        return "problem:" + safe(code);
    }

    private String workerSectionKey(String code) {
        return "worker:" + safe(code);
    }

    private String overdueKey(String status) {
        return "overdue:" + safe(status);
    }

    private boolean isOpenActionItem(ManagerDailyControlItem item) {
        return item != null
                && item.getGroup() == ManagerDailyControlGroup.ACTION
                && item.getStatus() == ManagerDailyControlItemStatus.OPEN;
    }

    private boolean isHandledActionItem(ManagerDailyControlItem item) {
        return item != null
                && item.getGroup() == ManagerDailyControlGroup.ACTION
                && item.getStatus() != ManagerDailyControlItemStatus.OPEN
                && item.getStatus() != ManagerDailyControlItemStatus.RESOLVED;
    }

    private boolean isOpenCriticalActionItem(ManagerDailyControlItem item) {
        return isOpenActionItem(item) && item.getSeverity() == ManagerDailyControlSeverity.CRITICAL;
    }

    private boolean isHandledCriticalActionItem(ManagerDailyControlItem item) {
        return isHandledActionItem(item) && item.getSeverity() == ManagerDailyControlSeverity.CRITICAL;
    }

    private ManagerDailyControlStatus recalculateControlStatus(ManagerDailyControl control) {
        return recalculateControlStatus(dailyControlItemRepository.findByControl(control));
    }

    private ManagerDailyControlStatus recalculateControlStatus(List<ManagerDailyControlItem> items) {
        boolean hasOpenCritical = items.stream().anyMatch(this::isOpenCriticalActionItem);
        if (hasOpenCritical) {
            return ManagerDailyControlStatus.RED;
        }
        boolean hasOpenWarning = items.stream().anyMatch(item -> isOpenActionItem(item) && item.getSeverity() == ManagerDailyControlSeverity.WARNING);
        boolean hasHandledCritical = items.stream().anyMatch(this::isHandledCriticalActionItem);
        if (hasOpenWarning || hasHandledCritical) {
            return ManagerDailyControlStatus.YELLOW;
        }
        return ManagerDailyControlStatus.GREEN;
    }

    private ManagerDailyControlActionType parseActionType(String value) {
        if (value == null || value.isBlank()) {
            return ManagerDailyControlActionType.ACKNOWLEDGED;
        }
        try {
            return ManagerDailyControlActionType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректное действие контроля");
        }
    }

    private ManagerDailyControlItemStatus itemStatusForAction(ManagerDailyControlActionType actionType) {
        return switch (actionType) {
            case ACKNOWLEDGED -> ManagerDailyControlItemStatus.ACKNOWLEDGED;
            case ACTION_TAKEN -> ManagerDailyControlItemStatus.ACTION_TAKEN;
            case DEFERRED -> ManagerDailyControlItemStatus.DEFERRED;
            case RESOLVED -> ManagerDailyControlItemStatus.RESOLVED;
        };
    }

    private void requireConcreteActionAllowed(
            ManagerDailyControlConcreteItem concreteItem,
            ManagerDailyControlActionType actionType
    ) {
        if (actionType != ManagerDailyControlActionType.ACKNOWLEDGED) {
            return;
        }
        if (concreteItem != null && ENTITY_CLIENT_CHAT_UNANSWERED.equals(concreteItem.getEntityType())) {
            return;
        }
        ManagerDailyControlItem parentItem = concreteItem == null ? null : concreteItem.getParentItem();
        if (parentItem != null
                && parentItem.getGroup() == ManagerDailyControlGroup.ACTION
                && parentItem.getSeverity() == ManagerDailyControlSeverity.CRITICAL) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Для красной карточки нужно выполнить действие, отложить или закрыть проблему"
            );
        }
    }

    private void rejectAggregateActionForConcreteItem(ManagerDailyControlItem item) {
        if (requiresConcreteCardAction(item)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Красный пункт нельзя закрыть целиком. Обработайте конкретные карточки внутри пункта."
            );
        }
    }

    private boolean requiresConcreteCardAction(ManagerDailyControlItem item) {
        return item != null
                && item.getGroup() == ManagerDailyControlGroup.ACTION
                && item.getSeverity() == ManagerDailyControlSeverity.CRITICAL
                && item.getCount() > 0;
    }

    private ManagerDailyControlSeverity parseSeverity(String value) {
        if (value == null || value.isBlank()) {
            return ManagerDailyControlSeverity.INFO;
        }
        try {
            return ManagerDailyControlSeverity.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ManagerDailyControlSeverity.INFO;
        }
    }

    private ManagerDailyControlGroup parseGroup(String value) {
        if (value == null || value.isBlank()) {
            return ManagerDailyControlGroup.WORKLOAD;
        }
        try {
            return ManagerDailyControlGroup.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ManagerDailyControlGroup.WORKLOAD;
        }
    }

    private void requireControlAccess(ManagerDailyControl control, Principal principal, Authentication authentication) {
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return;
        }
        if (!managerPermissionService.hasRole(authentication, "OWNER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав");
        }
        Set<Long> managerIds = userService.findManagersByUserName(principal.getName()).stream()
                .map(Manager::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Long controlManagerId = control.getManager() == null ? null : control.getManager().getId();
        if (controlManagerId == null || !managerIds.contains(controlManagerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер недоступен");
        }
    }

    private Long actorUserId(Principal principal) {
        if (principal == null || principal.getName() == null) {
            return null;
        }
        return userService.findByUserName(principal.getName())
                .map(User::getId)
                .orElse(null);
    }

    private void saveEvent(
            ManagerDailyControl control,
            ManagerDailyControlItem item,
            Long actorUserId,
            ManagerDailyControlEventType eventType,
            ManagerDailyControlActionType actionType,
            String comment
    ) {
        ManagerDailyControlEvent event = new ManagerDailyControlEvent();
        event.setControl(control);
        event.setItem(item);
        event.setActorUserId(actorUserId);
        event.setEventType(eventType);
        event.setActionType(actionType);
        event.setComment(limit(comment, 1000));
        dailyControlEventRepository.save(event);
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String compact(String value, int maxLength) {
        String trimmed = safe(value).replaceAll("\\s+", " ");
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    private WorkerSectionCounts workerSectionCounts(Manager manager, LocalDate today) {
        List<Long> workerIds = workerIds(manager);
        Map<Long, Integer> publishByWorker = workerIds.isEmpty()
                ? Map.of()
                : safeMapLong(reviewService.countOrdersByWorkerIdsAndStatusPublish(
                        workerIds,
                        managerControlPublicationOverdueDate(today)
                ));
        Map<Long, Integer> nagulByWorker = workerIds.isEmpty()
                ? Map.of()
                : safeMapLong(reviewService.countOrdersByWorkerIdsAndStatusVigul(workerIds, today.plusDays(60)));
        Map<String, Long> staleOrderCounts = workerIds.isEmpty()
                ? Map.of()
                : safeStatusCountMap(orderRepository.countManagerControlWorkerStaleOrdersByStatus(
                        workerIds,
                        Set.of("Новый", "Коррекция"),
                        managerControlWorkerOrderOverdueDate(today)
                ));
        long nagulOverdueTotal = workerIds.isEmpty()
                ? 0L
                : sumRowCounts(reviewRepository.countManagerControlNagulReviewsByWorkerIds(
                        workerIds,
                        managerControlPublicationOverdueDate(today)
                ));

        long newCount = workerOrderCount(workerIds, "Новый");
        long correctCount = workerOrderCount(workerIds, "Коррекция");
        long nagulCount = sumValues(nagulByWorker);
        Map<String, Long> snoozedWorkerTasks = snoozedWorkerTaskCountsByType(manager, today);
        LocalDate workerTaskOverdueDate = managerControlWorkerTaskOverdueDate(today);
        long newOverdueBaseCount = workerIds.isEmpty()
                ? 0L
                : workerStaleOrdersForControl(workerIds, "Новый", today).size();
        long newOverdueCount = Math.max(0L, newOverdueBaseCount
                - snoozedWorkerTasks.getOrDefault(ENTITY_WORKER_ORDER_NEW, 0L));
        long correctOverdueCount = Math.max(0L, staleOrderCounts.getOrDefault("Коррекция", 0L)
                - snoozedWorkerTasks.getOrDefault(ENTITY_WORKER_ORDER_CORRECT, 0L));
        long nagulOverdueCount = Math.max(0L, nagulOverdueTotal
                - snoozedWorkerTasks.getOrDefault(ENTITY_NAGUL_REVIEW, 0L));
        long recoveryCount = Math.max(0L,
                reviewRecoveryTaskService.countDueTasksToManager(manager, workerTaskOverdueDate)
                        - snoozedWorkerTasks.getOrDefault("RECOVERY_TASK", 0L));
        long publishCount = Math.max(0L, sumValues(publishByWorker)
                - snoozedWorkerTasks.getOrDefault(ENTITY_PUBLISH_REVIEW, 0L));
        long badCount = Math.max(0L,
                badReviewTaskService.countDueTasksToManager(manager, workerTaskOverdueDate)
                        - snoozedWorkerTasks.getOrDefault("BAD_REVIEW_TASK", 0L));
        List<ManagerControlSectionResponse> sections = List.of(
                section("new_overdue", "Новые без изменений", newOverdueCount, "CRITICAL", "ACTION", workerUrl("new")),
                section("correct_overdue", "Коррекция без изменений", correctOverdueCount, "CRITICAL", "ACTION", workerUrl("correct")),
                section("nagul_overdue", "Просроченный выгул", nagulOverdueCount, "CRITICAL", "ACTION", workerUrl("nagul")),
                section("new", "Новые", newCount, "INFO", "WORKLOAD", workerUrl("new")),
                section("correct", "Коррекция", correctCount, "INFO", "WORKLOAD", workerUrl("correct")),
                section("nagul", "Выгул", nagulCount, "INFO", "WORKLOAD", workerUrl("nagul")),
                section("recovery", "Восстановление", recoveryCount, "CRITICAL", "ACTION", workerUrl("recovery")),
                section("publish", "Публикация", publishCount, "CRITICAL", "ACTION", workerUrl("publish")),
                section("bad", "Плохие", badCount, "CRITICAL", "ACTION", workerUrl("bad"))
        );

        return new WorkerSectionCounts(
                sections,
                sections.stream().mapToLong(ManagerControlSectionResponse::count).sum(),
                newOverdueCount + correctOverdueCount + nagulOverdueCount + recoveryCount + publishCount + badCount,
                newCount + correctCount + nagulCount
        );
    }

    private LocalDate managerControlWorkerTaskOverdueDate(LocalDate today) {
        return (today == null ? LocalDate.now() : today).minusDays(1);
    }

    private LocalDate managerControlWorkerOrderOverdueDate(LocalDate today) {
        return (today == null ? LocalDate.now() : today).minusDays(WORKER_ORDER_UNCHANGED_DAYS);
    }

    private LocalDate managerControlPublicationOverdueDate(LocalDate today) {
        return (today == null ? LocalDate.now() : today).minusDays(1);
    }

    private Map<String, Long> snoozedWorkerTaskCountsByType(Manager manager, LocalDate today) {
        return dailyControlRepository.findByControlDateAndManager(today, manager)
                .map(control -> dailyControlConcreteItemRepository
                        .findByControlAndFollowUpAtAfter(control, LocalDateTime.now()).stream()
                        .filter(this::isSpecialistActionConcrete)
                        .filter(item -> item.getStatus() != ManagerDailyControlItemStatus.OPEN)
                        .collect(Collectors.groupingBy(ManagerDailyControlConcreteItem::getEntityType, Collectors.counting())))
                .orElse(Map.of());
    }

    private List<ManagerControlOverdueStatusResponse> overdueStatuses(Manager manager, LocalDate today) {
        LocalDate cutoff = today.minusDays(OVERDUE_NOTIFICATION_DAYS + 1L);
        Map<String, Long> snoozedByStatus = snoozedOrderCountsByStatus(manager, today);
        return orderRepository.summarizeManagerControlOverdueOrdersByManager(
                        manager,
                        cutoff,
                        OVERDUE_IGNORED_STATUSES,
                        COMMON_INVOICE_CONTROL_STATUSES,
                        PAYMENT_AUTOMATION_STATUSES,
                        PAYMENT_AUTOMATION_SCENARIOS,
                        REVIEW_CHECK_AUTOMATION_STATUSES,
                        REVIEW_CHECK_SCENARIOS,
                        DELIVERY_RETRY_AUTOMATION_STATUSES,
                        DELIVERY_RETRY_SCENARIOS,
                        CLIENT_TEXT_AUTOMATION_STATUSES,
                        CLIENT_TEXT_SCENARIOS,
                        ScheduledMessageStateStatus.ACTIVE,
                        ScheduledMessageStateStatus.DONE
                ).stream()
                .map(row -> {
                    String status = rowString(row, 0, "Без статуса");
                    long adjustedCount = Math.max(0, rowLong(row, 1) - snoozedByStatus.getOrDefault(status, 0L));
                    return new ManagerControlOverdueStatusResponse(
                            status,
                            adjustedCount,
                            daysSince(rowDate(row, 2), today),
                            ordersUrl(manager, status)
                    );
                })
                .filter(status -> status.count() > 0)
                .sorted(Comparator
                        .comparingInt((ManagerControlOverdueStatusResponse status) -> orderStatusDisplayRank(status.status()))
                        .thenComparing(ManagerControlOverdueStatusResponse::status, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private Map<String, Long> snoozedOrderCountsByStatus(Manager manager, LocalDate today) {
        return dailyControlRepository.findByControlDateAndManager(today, manager)
                .map(control -> dailyControlConcreteItemRepository
                        .findByControlAndEntityTypeAndFollowUpAtAfter(control, "ORDER", LocalDateTime.now()).stream()
                        .filter(item -> !safe(item.getStatusLabel()).isBlank())
                        .collect(Collectors.groupingBy(ManagerDailyControlConcreteItem::getStatusLabel, Collectors.counting())))
                .orElse(Map.of());
    }

    private Set<Long> snoozedOrderIds(Manager manager, LocalDate today) {
        return dailyControlRepository.findByControlDateAndManager(today, manager)
                .map(control -> dailyControlConcreteItemRepository
                        .findByControlAndEntityTypeAndFollowUpAtAfter(control, "ORDER", LocalDateTime.now()).stream()
                        .map(ManagerDailyControlConcreteItem::getEntityId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    private int orderStatusDisplayRank(String status) {
        int index = ORDER_STATUS_DISPLAY_ORDER.indexOf(status);
        return index >= 0 ? index : ORDER_STATUS_DISPLAY_ORDER.size();
    }

    private long openRiskCount(Manager manager) {
        List<Long> userIds = workerUserIds(manager);
        if (userIds.isEmpty()) {
            return 0;
        }
        return riskIncidentRepository.countByWorkerUserIdInAndStatus(userIds, WorkerRiskIncidentStatus.OPEN);
    }

    private long workerOrderCount(List<Long> workerIds, String status) {
        if (workerIds.isEmpty()) {
            return 0;
        }
        return sumValues(orderService.countOrdersByWorkerIdsAndStatus(workerIds, status));
    }

    private List<Long> workerIds(Manager manager) {
        User user = manager.getUser();
        if (user == null || user.getWorkers() == null) {
            return List.of();
        }
        return user.getWorkers().stream()
                .filter(Objects::nonNull)
                .map(Worker::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<Long> workerUserIds(Manager manager) {
        User user = manager.getUser();
        if (user == null || user.getWorkers() == null) {
            return List.of();
        }
        return user.getWorkers().stream()
                .filter(Objects::nonNull)
                .map(Worker::getUser)
                .filter(Objects::nonNull)
                .map(User::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private ManagerControlSectionResponse section(
            String code,
            String label,
            long count,
            String severity,
            String group,
            String targetUrl
    ) {
        return new ManagerControlSectionResponse(code, label, Math.max(0, count), severity, group, targetUrl);
    }

    private void addProblem(
            List<ManagerControlProblemResponse> problems,
            String code,
            String label,
            long count,
            String severity,
            String group,
            String icon,
            String targetUrl
    ) {
        if (count <= 0) {
            return;
        }
        problems.add(new ManagerControlProblemResponse(code, label, count, severity, group, icon, targetUrl));
    }

    private long sum(Map<String, Integer> counts, List<String> statuses) {
        return statuses.stream()
                .mapToLong(status -> counts.getOrDefault(status, 0))
                .sum();
    }

    private long sumValues(Map<?, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return 0;
        }
        return counts.values().stream().filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
    }

    private Map<String, Integer> safeMap(Map<String, Integer> source) {
        return source == null ? Map.of() : source;
    }

    private Map<Long, Integer> safeMapLong(Map<Long, Integer> source) {
        return source == null ? Map.of() : source;
    }

    private Map<String, Long> safeStatusCountMap(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        return rows.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        row -> rowString(row, 0, "Без статуса"),
                        row -> rowLong(row, 1),
                        Long::sum
                ));
    }

    private long sumRowCounts(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return rows.stream().mapToLong(row -> rowLong(row, 1)).sum();
    }

    private long rowLong(Object[] row, int index) {
        if (row == null || index < 0 || index >= row.length) {
            return 0;
        }
        Object value = row[index];
        return value instanceof Number number ? number.longValue() : 0;
    }

    private String rowString(Object[] row, int index, String fallback) {
        if (row == null || index < 0 || index >= row.length || row[index] == null) {
            return fallback;
        }
        String value = String.valueOf(row[index]).trim();
        return value.isBlank() ? fallback : value;
    }

    private LocalDate rowDate(Object[] row, int index) {
        if (row == null || index < 0 || index >= row.length || row[index] == null) {
            return null;
        }
        Object value = row[index];
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        return null;
    }

    private long daysSince(LocalDate date, LocalDate today) {
        return date == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(date, today));
    }

    private String ordersUrl(Manager manager, String status) {
        StringBuilder url = new StringBuilder("/orders?managerId=")
                .append(manager.getId())
                .append("&control=manager-overdue")
                .append("&sortDirection=desc");
        if (status != null && !status.isBlank()) {
            url.append("&status=").append(encode(status));
        }
        return url.toString();
    }

    private String workerUrl(String section) {
        if (section == null || section.isBlank()) {
            return "/worker";
        }
        return "/worker?section=" + encode(section);
    }

    private String firstWorkerSectionUrl(List<ManagerControlSectionResponse> sections, String group, String fallbackSection) {
        return sections.stream()
                .filter(section -> group.equals(section.group()))
                .filter(section -> section.count() > 0)
                .filter(section -> !"risk".equals(section.code()))
                .map(ManagerControlSectionResponse::code)
                .findFirst()
                .map(this::workerUrl)
                .orElseGet(() -> workerUrl(fallbackSection));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String normalizedChatUrl(String value) {
        String url = safe(value);
        if (url.isBlank()) {
            return null;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "https://" + url;
    }

    private String managerName(Manager manager) {
        if (manager == null) {
            return "Менеджер";
        }
        User user = manager.getUser();
        String fio = safe(user == null ? null : user.getFio());
        if (!fio.isBlank()) {
            return fio;
        }
        String username = safe(user == null ? null : user.getUsername());
        return username.isBlank() ? "Менеджер #" + manager.getId() : username;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private int statusRank(String status) {
        return switch (status) {
            case "RED" -> 0;
            case "YELLOW" -> 1;
            default -> 2;
        };
    }

    private record WorkerSectionCounts(
            List<ManagerControlSectionResponse> sections,
            long total,
            long actionTotal,
            long workloadTotal
    ) {
    }

    private record WorkerClientTextDecision(
            boolean include,
            String reason
    ) {
        private static WorkerClientTextDecision includeDefault() {
            return new WorkerClientTextDecision(true, null);
        }

        private static WorkerClientTextDecision suppress() {
            return new WorkerClientTextDecision(false, null);
        }
    }

    private record WorkerOrderControlEntry(
            Order order,
            WorkerClientTextDecision clientTextDecision
    ) {
    }

    private record DailyControlSyncResult(
            ManagerDailyControl control,
            List<ManagerDailyControlItem> items,
            Map<String, ManagerDailyControlItem> itemsByKey
    ) {
    }

    private record ControlItemInput(
            String itemKey,
            ManagerDailyControlItemType itemType,
            Long entityId,
            Long workerId,
            String sectionCode,
            String reasonCode,
            String label,
            String targetUrl,
            long count,
            ManagerDailyControlSeverity severity,
            ManagerDailyControlGroup group
    ) {
    }
}
