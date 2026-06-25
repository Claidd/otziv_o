package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.service.ClientMessageOrderStatusService;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.manager.services.ManagerPermissionService;
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
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ManagerControlService {

    private static final int DETAIL_EXAMPLE_LIMIT = 5;
    private static final int MANUAL_FOLLOW_UP_DAYS = 2;
    private static final int WORKER_TASK_FOLLOW_UP_HOURS = 3;
    private static final int OVERDUE_NOTIFICATION_DAYS = 4;
    private static final int COMMON_INVOICE_STALE_DAYS = 3;
    private static final LocalTime MORNING_STAGE_START = LocalTime.of(5, 0);
    private static final LocalTime DAY_STAGE_START = LocalTime.of(14, 0);
    private static final LocalTime FINAL_STAGE_START = LocalTime.of(20, 0);
    private static final String SOURCE_CONTROL_OWNER = "MANAGER_CONTROL_OWNER";
    private static final String SOURCE_WORKER_TASK_REQUEST = "MANAGER_CONTROL_WORKER_TASK_REQUEST";
    private static final String ENTITY_PUBLISH_REVIEW = "PUBLISH_REVIEW";
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
            "Не оплачено",
            "Требует внимания",
            "Бан"
    );
    private static final Set<String> PAYMENT_AUTOMATION_STATUSES = Set.of(
            "Опубликовано",
            "Выставлен счет",
            "Напоминание",
            "Не оплачено"
    );
    private static final Set<String> MANUAL_CONTACT_ORDER_STATUSES = Set.of(
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
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final CommonInvoiceRepository commonInvoiceRepository;
    private final WorkerRiskIncidentRepository riskIncidentRepository;
    private final ManagerDailyControlRepository dailyControlRepository;
    private final ManagerDailyControlItemRepository dailyControlItemRepository;
    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;
    private final ManagerDailyControlEventRepository dailyControlEventRepository;

    @Transactional
    public ManagerControlSummaryResponse today(Principal principal, Authentication authentication) {
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
                && !now.toLocalTime().isBefore(DAY_STAGE_START)
                && control.getMorningCompletedAt() == null
                && control.getMorningNotificationSentAt() == null) {
            control.setMorningNotificationSentAt(now);
            String text = overdueStageText(control, "утренний обход", "14:00", openAction);
            saveEvent(control, null, null, ManagerDailyControlEventType.TEST_NOTIFICATION, null, text);
            notifyOwners(control, "Просрочен утренний контроль", text);
        }
        if (!previousDayOnly
                && !now.toLocalTime().isBefore(FINAL_STAGE_START)
                && control.getDayCheckedAt() == null
                && control.getDayNotificationSentAt() == null) {
            control.setDayNotificationSentAt(now);
            String text = overdueStageText(control, "дневной контроль", "20:00", openAction);
            saveEvent(control, null, null, ManagerDailyControlEventType.TEST_NOTIFICATION, null, text);
            notifyOwners(control, "Просрочен дневной контроль", text);
        }
        if ((previousDayOnly || control.getControlDate().isBefore(now.toLocalDate()))
                && !now.toLocalTime().isBefore(MORNING_STAGE_START)
                && control.getFinalCheckedAt() == null
                && control.getEveningNotificationSentAt() == null) {
            control.setEveningNotificationSentAt(now);
            String text = overdueStageText(control, "финальная проверка", "05:00", openAction);
            saveEvent(control, null, null, ManagerDailyControlEventType.TEST_NOTIFICATION, null, text);
            notifyOwners(control, "Просрочена финальная проверка", text);
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
        if (manualWorkerNotification && isWorkerTaskConcrete(concreteItem) && !safe(comment).toLowerCase(Locale.ROOT).contains("вручн")) {
            comment = manualWorkerNotificationComment(concreteItem);
        }
        requireCommentIfNeeded(concreteItem.getParentItem(), actionType, comment);
        LocalDateTime now = LocalDateTime.now();
        concreteItem.setStatus(status);
        concreteItem.setActionType(actionType);
        concreteItem.setComment(comment);
        concreteItem.setResolvedAt(status == ManagerDailyControlItemStatus.RESOLVED ? now : null);
        boolean movedToReminder = false;
        if ("ORDER".equals(concreteItem.getEntityType()) && status != ManagerDailyControlItemStatus.RESOLVED) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(now.plusDays(MANUAL_FOLLOW_UP_DAYS));
            if (actionType == ManagerDailyControlActionType.ACTION_TAKEN) {
                movedToReminder = movePaymentOrderToReminderAfterManualSend(concreteItem);
            }
        } else if (isPublishReviewConcrete(concreteItem) && status != ManagerDailyControlItemStatus.RESOLVED) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(workerTaskFollowUpAt(now));
        } else if (isWorkerTaskConcrete(concreteItem)
                && status != ManagerDailyControlItemStatus.RESOLVED
                && actionType != ManagerDailyControlActionType.ACKNOWLEDGED) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(workerTaskFollowUpAt(now));
            if (actionType == ManagerDailyControlActionType.ACTION_TAKEN) {
                if (manualWorkerNotification) {
                    clearWorkerTelegramState(concreteItem);
                } else {
                    notifyWorkerAboutTaskRequest(concreteItem, control);
                }
            }
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
                rejectStageCompletionIfProblemsOpen(control, "Утренний обход");
                rejectIfOutsideStageWindow("Утренний обход", now.toLocalTime());
                if (control.getMorningStartedAt() == null) {
                    control.setMorningStartedAt(now);
                }
                control.setMorningCompletedAt(now);
            }
            case "DAY_CHECK" -> {
                rejectStageCompletionIfProblemsOpen(control, "Дневной контроль");
                rejectIfPreviousStageMissing(control.getMorningCompletedAt(), "Сначала закройте утренний обход");
                rejectIfOutsideStageWindow("Дневной контроль", now.toLocalTime());
                control.setDayCheckedAt(now);
            }
            case "FINAL_CHECK" -> {
                rejectStageCompletionIfProblemsOpen(control, "Финальная проверка");
                rejectIfPreviousStageMissing(control.getDayCheckedAt(), "Сначала отметьте дневной контроль");
                rejectIfOutsideStageWindow("Финальная проверка", now.toLocalTime());
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
        if (control.getMorningStartedAt() == null) {
            blockers.add("Не отмечен утренний обход");
        }
        if (control.getMorningCompletedAt() == null) {
            blockers.add("Не закрыт утренний обход");
        }
        if (control.getDayCheckedAt() == null) {
            blockers.add("Не отмечен дневной контроль");
        }
        if (control.getFinalCheckedAt() == null) {
            blockers.add("Не отмечена финальная проверка");
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
            case "Утренний обход" -> !time.isBefore(MORNING_STAGE_START) && time.isBefore(DAY_STAGE_START);
            case "Дневной контроль" -> !time.isBefore(DAY_STAGE_START) && time.isBefore(FINAL_STAGE_START);
            case "Финальная проверка" -> !time.isBefore(FINAL_STAGE_START) || time.isBefore(MORNING_STAGE_START);
            default -> true;
        };
        if (!allowed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    stageLabel + " можно завершить только в свое окно: утро 05:00-14:00, день 14:00-20:00, финал 20:00-04:59"
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
        return item == null
                || item.getItemType() != ManagerDailyControlItemType.WORKER_SECTION
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
        stageScore += control.getDayCheckedAt() == null ? 8 : 0;
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

        List<ManagerControlProblemResponse> problems = new ArrayList<>();
        addProblem(problems, "OVERDUE_ORDERS", "Просроченные заказы", overdueOrders, "CRITICAL", "ACTION", "schedule", ordersUrl(manager, null));
        addProblem(problems, "OPEN_RISKS", "Открытые риски специалистов", openRisks, "CRITICAL", "ACTION", "warning", "/worker/risk");
        addProblem(problems, "REQUIRES_ATTENTION", "Требует внимания", requiresAttention, "CRITICAL", "ACTION", "error", ordersUrl(manager, "Требует внимания"));
        addProblem(problems, "COMMON_INVOICES", "Общие счета", commonInvoiceActionCount, "CRITICAL", "ACTION", "receipt_long", "/admin/common-billing");
        addProblem(problems, "ORDERS_WORKLOAD", "Рабочие заказы", orderAttention, "INFO", "WORKLOAD", "inventory_2", ordersUrl(manager, null));
        addProblem(problems, "WORKER_WORKLOAD", "Нагрузка специалистов", workerWorkloadCount, "INFO", "WORKLOAD", "engineering", firstWorkerSectionUrl(workerCounts.sections(), "WORKLOAD", "new"));

        List<ManagerControlSectionResponse> sections = workerCounts.sections();
        long criticalCount = overdueOrders + openRisks + requiresAttention + commonInvoiceActionCount + workerActionCount;
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
        if ("OPEN_RISKS".equals(item.getReasonCode()) || "risk".equals(item.getSectionCode())) {
            return riskExamples(manager, limit);
        }
        if ("WORKER_ACTIONS".equals(item.getReasonCode())) {
            return workerActionExamples(manager, today, limit);
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
            synced.add(concreteItemResponse(dailyControlConcreteItemRepository.save(concreteItem), example.contactText()));
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
                contactText
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
            case "Выставлен счет" -> "Счет выставлен " + age
                    + ", оплаты нет. " + controlReason + " Отправьте напоминание клиенту; после отправки заказ уйдет в «Напоминание».";
            case "Напоминание" -> "Заказ в напоминании " + age
                    + ", клиент не оплатил. " + controlReason + " Повторите напоминание или укажите, почему откладываем.";
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
                return "Почему в контроле: автоответчик не обработал заказ — " + error + ".";
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

    private boolean isSpecialistActionConcrete(ManagerDailyControlConcreteItem item) {
        return isWorkerTaskConcrete(item) || isPublishReviewConcrete(item);
    }

    private LocalDateTime workerTaskFollowUpAt(LocalDateTime now) {
        LocalDateTime base = now == null ? LocalDateTime.now() : now;
        return base.plusHours(WORKER_TASK_FOLLOW_UP_HOURS);
    }

    private String manualWorkerNotificationComment(ManagerDailyControlConcreteItem concreteItem) {
        String type = "RECOVERY_TASK".equals(safe(concreteItem == null ? null : concreteItem.getEntityType()))
                ? "восстановлению"
                : "плохому отзыву";
        return "Специалисту отправлен запрос вручную выполнить задачу по " + type + ". Повторный контроль через "
                + WORKER_TASK_FOLLOW_UP_HOURS + " ч.";
    }

    private void clearWorkerTelegramState(ManagerDailyControlConcreteItem concreteItem) {
        concreteItem.setWorkerNotificationAttemptedAt(null);
        concreteItem.setWorkerNotificationSentAt(null);
        concreteItem.setWorkerNotificationAcceptedAt(null);
        concreteItem.setWorkerNotificationAcceptedByUserId(null);
        concreteItem.setWorkerNotificationFailureReason(null);
    }

    private void notifyWorkerAboutTaskRequest(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control) {
        User workerUser = workerUserForTask(concreteItem);
        if (workerUser == null || workerUser.getId() == null || concreteItem.getId() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        concreteItem.setWorkerNotificationAttemptedAt(now);
        concreteItem.setWorkerNotificationSentAt(null);
        concreteItem.setWorkerNotificationAcceptedAt(null);
        concreteItem.setWorkerNotificationAcceptedByUserId(null);
        concreteItem.setWorkerNotificationFailureReason(null);
        String managerName = control == null ? "" : managerName(control.getManager());
        String title = "Проверьте задачу";
        String text = List.of(
                        "Менеджер запросил выполнение задачи.",
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
        if (workerUser.getTelegramChatId() == null) {
            concreteItem.setWorkerNotificationFailureReason("Telegram работника не привязан");
            return;
        }
        boolean sent = telegramService.sendMessageWithInlineKeyboard(
                workerUser.getTelegramChatId(),
                text,
                null,
                List.of(List.of(ManagerControlWorkerTaskTelegramCallbackService.acceptButton(concreteItem.getId())))
        );
        if (sent) {
            concreteItem.setWorkerNotificationSentAt(now);
        } else {
            concreteItem.setWorkerNotificationFailureReason("Telegram не отправил сообщение");
        }
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
                default -> null;
            };
        } catch (RuntimeException ignored) {
            return null;
        }
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

    private List<ManagerControlConcreteItemResponse> publishReviewExamples(Manager manager, LocalDate today, int limit) {
        List<Long> workerIds = workerIds(manager);
        if (workerIds.isEmpty()) {
            return List.of();
        }
        return reviewRepository.findManagerControlPublishReviewsByWorkerIds(
                        workerIds,
                        today == null ? LocalDate.now() : today,
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
                limit(safe(incident.getMessage()), 180),
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
                null
        );
    }

    private long commonInvoiceActionCount(Manager manager) {
        return commonInvoiceRepository.countManagerControlInvoices(
                manager,
                COMMON_INVOICE_CRITICAL_STATUSES,
                COMMON_INVOICE_STALE_STATUSES,
                LocalDateTime.now().minusDays(COMMON_INVOICE_STALE_DAYS)
        );
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
            return "Ошибка общего счета: " + limit(lastError, 220);
        }
        String notificationError = safe(invoice.getPaymentSuccessNotificationError());
        if (!notificationError.isBlank()) {
            return "Ошибка уведомления об оплате: " + limit(notificationError, 220);
        }
        CommonInvoiceStatus status = invoice.getStatus();
        if (status == CommonInvoiceStatus.NEEDS_ATTENTION) {
            return "Счет требует ручного разбора";
        }
        if (status == CommonInvoiceStatus.UNPAID) {
            return "Счет переведен в не оплачено";
        }
        if (status == CommonInvoiceStatus.BAN) {
            return "Счет в бане";
        }
        long ageDays = invoice.getUpdatedAt() == null ? 0 : daysSince(invoice.getUpdatedAt().toLocalDate(), today);
        return "Завис в статусе " + commonInvoiceStatusLabel(status) + " " + ageDays + " дн.";
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
        if (isOpenCriticalActionItem(item)) {
            return 0;
        }
        if (isOpenActionItem(item)) {
            return 1;
        }
        if (isHandledCriticalActionItem(item)) {
            return 2;
        }
        if (isHandledActionItem(item)) {
            return 3;
        }
        if (item.getGroup() == ManagerDailyControlGroup.ACTION) {
            return 4;
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

    private WorkerSectionCounts workerSectionCounts(Manager manager, LocalDate today) {
        List<Long> workerIds = workerIds(manager);
        Map<Long, Integer> publishByWorker = workerIds.isEmpty()
                ? Map.of()
                : safeMapLong(reviewService.countOrdersByWorkerIdsAndStatusPublish(workerIds, today));
        Map<Long, Integer> nagulByWorker = workerIds.isEmpty()
                ? Map.of()
                : safeMapLong(reviewService.countOrdersByWorkerIdsAndStatusVigul(workerIds, today.plusDays(60)));

        long newCount = workerOrderCount(workerIds, "Новый");
        long correctCount = workerOrderCount(workerIds, "Коррекция");
        long nagulCount = sumValues(nagulByWorker);
        Map<String, Long> snoozedWorkerTasks = snoozedWorkerTaskCountsByType(manager, today);
        LocalDate workerTaskOverdueDate = managerControlWorkerTaskOverdueDate(today);
        long recoveryCount = Math.max(0L,
                reviewRecoveryTaskService.countDueTasksToManager(manager, workerTaskOverdueDate)
                        - snoozedWorkerTasks.getOrDefault("RECOVERY_TASK", 0L));
        long publishCount = Math.max(0L, sumValues(publishByWorker)
                - snoozedWorkerTasks.getOrDefault(ENTITY_PUBLISH_REVIEW, 0L));
        long badCount = Math.max(0L,
                badReviewTaskService.countDueTasksToManager(manager, workerTaskOverdueDate)
                        - snoozedWorkerTasks.getOrDefault("BAD_REVIEW_TASK", 0L));
        List<ManagerControlSectionResponse> sections = List.of(
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
                recoveryCount + publishCount + badCount,
                newCount + correctCount + nagulCount
        );
    }

    private LocalDate managerControlWorkerTaskOverdueDate(LocalDate today) {
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

    private String managerName(Manager manager) {
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
