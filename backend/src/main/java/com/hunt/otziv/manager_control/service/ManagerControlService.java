package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.service.CompanyChatBindingPolicy;
import com.hunt.otziv.c_companies.service.SharedChatLinkSyncService;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.client_chat_control.dto.ClientChatReconciliationResult;
import com.hunt.otziv.client_chat_control.dto.ClientChatUnansweredExample;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageReconciliationService;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_chat_control.service.ClientChatReplySuggestionService;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.service.ClientMessageOrderStatusService;
import com.hunt.otziv.client_messages.service.ClientMessageStateSafety;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.common_billing.service.CommonPaymentInitFailureClassifier;
import com.hunt.otziv.common_billing.service.CommonInvoicePublicationBlockerService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.manager_control.dto.ManagerControlClientReplyRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlClientReplySuggestionResponse;
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
import com.hunt.otziv.manager_control.dto.ManagerControlWorkerExplanationStatsResponse;
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
import com.hunt.otziv.manager_performance.dto.ManagerPerformanceScoreResponse;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.maxbot.service.MaxGroupLinkService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.OrderPaymentIntegrityService;
import com.hunt.otziv.payments.service.StandaloneBankPaymentPolicy;
import com.hunt.otziv.payments.service.BadReviewPaymentInstructionOrchestrator;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.t_telegrambot.dto.TelegramChatMigrationResult;
import com.hunt.otziv.t_telegrambot.service.TelegramGroupLinkService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import com.hunt.otziv.whatsapp.service.WhatsAppGroupLinkSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
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
import java.time.Duration;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerControlService {

    private static final int DETAIL_EXAMPLE_LIMIT = 5;
    private static final String CONTROL_CARD_TARGET_SETTING = "manager.sla.target.control-card-minutes";
    private static final String CONTROL_CARD_HARD_SETTING = "manager.sla.hard.control-card-minutes";
    private static final int CONTROL_CARD_TARGET_MINUTES = 30;
    private static final int CONTROL_CARD_HARD_MINUTES = 60;
    private static final int MANUAL_FOLLOW_UP_DAYS = 2;
    private static final int WORKER_TASK_FOLLOW_UP_HOURS = 3;
    private static final int OVERDUE_NOTIFICATION_DAYS = 4;
    private static final int WORKER_ORDER_UNCHANGED_DAYS = 2;
    private static final int COMMON_INVOICE_STALE_DAYS = 3;
    private static final Duration CLIENT_MESSAGE_PREPARED_STALE_AFTER = Duration.ofMinutes(15);
    private static final String CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX = "client_message_delivery_prepared:";
    private static final String CLIENT_MESSAGE_DELIVERY_UNKNOWN_PREFIX = "client_message_delivery_unknown:";
    private static final int COMMON_INVOICE_PUBLICATION_BLOCKER_HOURS =
            CommonInvoicePublicationBlockerService.ATTENTION_AFTER_HOURS;
    private static final LocalTime MORNING_STAGE_START = LocalTime.of(5, 0);
    private static final LocalTime START_DAY_DEADLINE = LocalTime.of(14, 0);
    private static final LocalTime FINAL_STAGE_START = LocalTime.of(20, 0);
    private static final String SOURCE_CONTROL_OWNER = "MANAGER_CONTROL_OWNER";
    private static final String SOURCE_WORKER_TASK_REQUEST = "MANAGER_CONTROL_WORKER_TASK_REQUEST";
    private static final String OWNER_CONTROL_ALL_MANAGERS = "ALL_MANAGERS";
    private static final String ENTITY_PUBLISH_REVIEW = "PUBLISH_REVIEW";
    private static final String ENTITY_PUBLICATION_DATE_REVIEW = "PUBLICATION_DATE_REVIEW";
    private static final String ENTITY_NAGUL_REVIEW = "NAGUL_REVIEW";
    private static final String ENTITY_WORKER_ORDER_NEW = "WORKER_ORDER_NEW";
    private static final String ENTITY_WORKER_ORDER_CORRECT = "WORKER_ORDER_CORRECT";
    private static final String ENTITY_TELEGRAM_CHAT = "TELEGRAM_CHAT";
    private static final String ENTITY_CLIENT_CHAT_UNANSWERED = "CLIENT_CHAT_UNANSWERED";
    private static final String ENTITY_CLIENT_CHAT_AUDIT = "CLIENT_CHAT_AUDIT";
    private static final String ENTITY_ORDER_PAYMENT_INTEGRITY = OrderPaymentIntegrityService.ENTITY_TYPE;
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
            "Ожидает общего счета",
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
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );
    private final ManagerRepository managerRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ManagerAccessService managerAccessService;
    private final ManagerPermissionService managerPermissionService;
    private final PersonalReminderService personalReminderService;
    private final TelegramService telegramService;
    private final NotificationMediaDeliveryService notificationMediaDeliveryService;
    private final OrderService orderService;
    private final ClientMessageOrderStatusService clientMessageOrderStatusService;
    private final ScheduledClientMessageService scheduledClientMessageService;
    private final ScheduledClientMessageStateRepository scheduledClientMessageStateRepository;
    private final AppSettingService appSettingService;
    private final ClientChatMessageSender clientChatMessageSender;
    private final ClientChatMessageTrackerService clientChatMessageTrackerService;
    private final ClientChatMessageReconciliationService clientChatMessageReconciliationService;
    private final ClientChatReplySuggestionService clientChatReplySuggestionService;
    private final ClientChatUnansweredItemRepository clientChatUnansweredItemRepository;
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final CompanyRepository companyRepository;
    private final PaymentLinkRepository paymentLinkRepository;
    private final OrderPaymentIntegrityService orderPaymentIntegrityService;
    private final BadReviewPaymentInstructionOrchestrator paymentInstructionOrchestrator;
    private final ManagerControlTransactionRunner managerControlTransactionRunner;
    private final CommonInvoiceRepository commonInvoiceRepository;
    private final CommonInvoiceOrderRepository commonInvoiceOrderRepository;
    private final CommonInvoicePublicationBlockerService commonInvoicePublicationBlockerService;
    private final ManagerAutomationFailureService managerAutomationFailureService;
    private final CommonBillingService commonBillingService;
    private final ManagerControlInvoiceOperationExecutor invoiceOperationExecutor;
    private final OrderPublicationApprovalService publicationApprovalService;
    private final WorkerRiskIncidentRepository riskIncidentRepository;
    private final WhatsAppGroupLinkSyncService whatsAppGroupLinkSyncService;
    private final SharedChatLinkSyncService sharedChatLinkSyncService;
    private final TelegramGroupLinkService telegramGroupLinkService;
    private final MaxGroupLinkService maxGroupLinkService;
    private final ManagerDailyControlRepository dailyControlRepository;
    private final ManagerDailyControlItemRepository dailyControlItemRepository;
    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;
    private final ManagerDailyControlEventRepository dailyControlEventRepository;
    private final ManagerActionBalanceService managerActionBalanceService;
    private final ManagerOperationalMetricsService managerOperationalMetricsService;
    private final ManagerPerformanceService managerPerformanceService;
    private final GamificationEventService gamificationEventService;
    private final LeadsRepository leadsRepository;
    //ok
    @Transactional(readOnly = true)
    public ManagerControlSummaryResponse today(Principal principal, Authentication authentication) {
        return today(principal, authentication, false);
    }

    @Transactional
    public ManagerControlSummaryResponse syncToday(Principal principal, Authentication authentication) {
        reconcileClientMessagesForControl();
        invalidateManagerPerformance();
        LocalDate today = LocalDate.now();
        for (Manager manager : visibleManagers(principal, authentication)) {
            managerControl(manager, today, null, true, false);
            syncManagerActionConcreteItems(manager, today);
        }
        return today(principal, authentication, false);
    }

    @Transactional
    public void synchronizeDailySnapshot(LocalDate date) {
        LocalDate snapshotDate = date == null ? LocalDate.now() : date;
        reconcileClientMessagesForControl();
        for (Manager manager : managerRepository.findAllWithUserAndImage()) {
            managerControl(manager, snapshotDate, null, true, false);
            syncManagerActionConcreteItems(manager, snapshotDate);
        }
    }

    private ManagerControlSummaryResponse today(Principal principal, Authentication authentication, boolean persist) {
        LocalDate today = LocalDate.now();
        List<ManagerControlManagerResponse> managers = visibleManagers(principal, authentication).stream()
                .map(manager -> managerControl(manager, today, null, persist, true))
                .sorted(Comparator
                        .comparingInt((ManagerControlManagerResponse manager) -> statusRank(manager.status()))
                        .thenComparing(ManagerControlManagerResponse::totalAttentionCount, Comparator.reverseOrder())
                        .thenComparing(ManagerControlManagerResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER")) {
            Map<Long, ManagerPerformanceScoreResponse> performanceByManagerId = managerPerformanceService.score(today).stream()
                    .filter(score -> score.managerId() != null)
                    .collect(Collectors.toMap(
                            ManagerPerformanceScoreResponse::managerId,
                            score -> score,
                            (left, right) -> left
                    ));
            managers = managers.stream()
                    .map(manager -> withManagerPerformance(manager, performanceByManagerId.get(manager.managerId())))
                    .toList();
        }

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
                managerPermissionService.hasRole(authentication, "MANAGER")
                        && !managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER"),
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

    private ManagerControlManagerResponse withManagerPerformance(
            ManagerControlManagerResponse manager,
            ManagerPerformanceScoreResponse managerPerformance
    ) {
        return new ManagerControlManagerResponse(
                manager.managerId(),
                manager.userId(),
                manager.username(),
                manager.name(),
                manager.active(),
                manager.dailyControlId(),
                manager.dailyControlStatus(),
                manager.startedAt(),
                manager.closedAt(),
                manager.morningStartedAt(),
                manager.morningCompletedAt(),
                manager.dayCheckedAt(),
                manager.finalCheckedAt(),
                manager.qualityScore(),
                manager.qualityGrade(),
                manager.riskScore(),
                manager.fastClickRisk(),
                manager.canCloseDay(),
                manager.openItemCount(),
                manager.handledItemCount(),
                manager.actionTotalCount(),
                manager.actionCompletedCount(),
                manager.actionProgressPercent(),
                manager.actionAutoClosedCount(),
                manager.actionRemainingCount(),
                manager.actionResolvedCount(),
                manager.actionTakenCount(),
                manager.actionDeferredCount(),
                manager.actionAcknowledgedCount(),
                manager.actionOverdueRemainingCount(),
                manager.actionRiskRemainingCount(),
                manager.actionUnansweredRemainingCount(),
                manager.actionOtherRemainingCount(),
                manager.leadActionCount(),
                manager.status(),
                manager.criticalCount(),
                manager.warningCount(),
                manager.workloadCount(),
                manager.totalAttentionCount(),
                manager.overdueOrderCount(),
                manager.openRiskCount(),
                manager.orderAttentionCount(),
                manager.workerSectionCount(),
                manager.problems(),
                manager.workerSections(),
                manager.overdueStatuses(),
                manager.workerExplanationStats(),
                manager.activeWorkSeconds(),
                manager.averageDailyWorkSeconds(),
                manager.averageReactionSeconds(),
                manager.reactionCount(),
                managerPerformance
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
            managerControl(manager, today, null, true, false);
            dailyControlRepository.findByControlDateAndManager(today, manager)
                    .ifPresent(control -> {
                        sendOverdueStageNotifications(control, now, false);
                        autoCloseControlIfReady(control, now);
                    });
        }
        if (!now.toLocalTime().isBefore(MORNING_STAGE_START)) {
            LocalDate previousDay = today.minusDays(1);
            for (Manager manager : managers) {
                dailyControlRepository.findByControlDateAndManager(previousDay, manager)
                        .ifPresent(control -> {
                            sendOverdueStageNotifications(control, now, true);
                            autoCloseControlIfReady(control, now);
                        });
            }
        }
    }

    private void sendOverdueStageNotifications(ManagerDailyControl control, LocalDateTime now, boolean previousDayOnly) {
        List<ManagerDailyControlItem> items = activeControlItems(dailyControlItemRepository.findByControl(control));
        long openAction = items.stream().filter(this::isOpenActionItem).count();
        boolean changed = false;
        if ((previousDayOnly || control.getControlDate().isBefore(now.toLocalDate()))
                && !now.toLocalTime().isBefore(MORNING_STAGE_START)
                && control.getFinalCheckedAt() == null
                && control.getEveningNotificationSentAt() == null) {
            control.setEveningNotificationSentAt(now);
            String text = overdueStageText(control, "конец дня", "05:00", openAction);
            saveEvent(control, null, null, ManagerDailyControlEventType.TEST_NOTIFICATION, null, text);
            notifyOwners(control, "Просрочен конец дня", text);
            changed = true;
        }
        if (changed) {
            dailyControlRepository.save(control);
        }
    }

    private boolean autoCloseControlIfReady(ManagerDailyControl control, LocalDateTime now) {
        if (control == null || control.getId() == null || control.getClosedAt() != null) {
            return false;
        }
        if (now == null || !isAutoCloseWindowForControl(control, now)) {
            return false;
        }
        List<ManagerDailyControlItem> items = dailyControlItemRepository.findByControl(control);
        if (!closeBlockers(control, items).isEmpty()) {
            return false;
        }
        closeControl(control, items, now, null, "Контроль закрыт автоматически: блокеров нет в вечернем окне");
        return true;
    }

    private boolean isAutoCloseWindowForControl(ManagerDailyControl control, LocalDateTime now) {
        LocalDate controlDate = control.getControlDate();
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        if (controlDate.equals(today) && !time.isBefore(FINAL_STAGE_START)) {
            return true;
        }
        return controlDate.equals(today.minusDays(1)) && time.isBefore(MORNING_STAGE_START);
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

    private void notifyOwnersAboutDeferredConcreteItem(
            ManagerDailyControl control,
            ManagerDailyControlConcreteItem concreteItem,
            Principal principal
    ) {
        if (control == null || concreteItem == null) {
            return;
        }
        String title = "Карточка контроля отложена";
        String text = String.join("\n",
                title,
                "Менеджер контроля: " + managerName(control.getManager()),
                "Отложил: " + currentUserDisplayName(principal),
                "Карточка: " + safe(concreteItem.getTitle()),
                "Статус: " + safe(concreteItem.getStatusLabel()),
                "Проблема: " + safe(concreteItem.getReason()),
                "Комментарий: " + safe(concreteItem.getComment()),
                "Ссылка: " + deferredConcreteItemUrl(control, concreteItem)
        );

        List<User> recipients = new ArrayList<>();
        recipients.addAll(userRepository.findAllOwners("ROLE_OWNER"));
        recipients.addAll(userRepository.findAllOwners("ROLE_ADMIN"));
        recipients.stream()
                .filter(Objects::nonNull)
                .filter(user -> user.getId() != null && user.getTelegramChatId() != null)
                .collect(Collectors.toMap(User::getId, Function.identity(), (left, right) -> left))
                .values()
                .forEach(user -> telegramService.sendMessage(user.getTelegramChatId(), text));
    }

    private String deferredConcreteItemUrl(ManagerDailyControl control, ManagerDailyControlConcreteItem concreteItem) {
        String targetUrl = safe(concreteItem == null ? null : concreteItem.getTargetUrl());
        if (!targetUrl.isBlank()) {
            return absoluteAppUrl(targetUrl);
        }
        Long managerId = control == null || control.getManager() == null ? null : control.getManager().getId();
        return absoluteAppUrl(managerId == null ? "/admin/manager-control" : "/admin/manager-control/" + managerId);
    }

    private String currentUserDisplayName(Principal principal) {
        User user = currentUser(principal);
        String fio = safe(user == null ? null : user.getFio());
        if (!fio.isBlank()) {
            return fio;
        }
        String username = safe(user == null ? null : user.getUsername());
        return username.isBlank() ? "Неизвестный пользователь" : username;
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
        acceptControlIfCurrentManager(control, principal, "Контроль принят первым действием");
        recordItemEpisode(item, status, false);
        item.setStatus(status);
        item.setActionType(actionType);
        item.setComment(comment);
        item.setResolvedAt(status == ManagerDailyControlItemStatus.RESOLVED ? LocalDateTime.now() : null);
        item.setAutomaticResolution(false);
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
        if (status == ManagerDailyControlItemStatus.RESOLVED) {
            recordGamificationControlAction(
                    control,
                    "item:" + item.getId(),
                    item.getReasonCode(),
                    item.getCreatedAt(),
                    item.getResolvedAt(),
                    item.getLabel()
            );
        }
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
        if ("COMMON_INVOICE".equals(concreteItem.getEntityType())
                && (actionType == ManagerDailyControlActionType.ACTION_TAKEN
                || actionType == ManagerDailyControlActionType.RESOLVED)) {
            requireCommonInvoiceProblemResolved(concreteItem);
            actionType = ManagerDailyControlActionType.RESOLVED;
        }
        if (actionType == ManagerDailyControlActionType.RESOLVED
                && (ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE.equals(concreteItem.getEntityType())
                || ManagerAutomationFailureService.ENTITY_COMMON_INVOICE_AUTOMATION.equals(concreteItem.getEntityType()))) {
            requireAutomationFailureResolved(concreteItem);
        }
        ManagerDailyControlItemStatus status = itemStatusForAction(actionType);
        String comment = limit(request == null ? null : request.comment(), 1000);
        boolean manualWorkerNotification = Boolean.TRUE.equals(request == null ? null : request.manualWorkerNotification());
        boolean specialistActionConcrete = isSpecialistActionConcrete(concreteItem);
        boolean clientChatUnansweredConcrete = ENTITY_CLIENT_CHAT_UNANSWERED.equals(concreteItem.getEntityType());
        boolean clientChatAuditConcrete = ENTITY_CLIENT_CHAT_AUDIT.equals(concreteItem.getEntityType());
        boolean keepClientChatUnansweredOpen = clientChatUnansweredConcrete
                && actionType == ManagerDailyControlActionType.DEFERRED;
        if (keepClientChatUnansweredOpen) {
            status = ManagerDailyControlItemStatus.OPEN;
        }
        if (manualWorkerNotification && specialistActionConcrete && safe(comment).isBlank()) {
            comment = manualWorkerNotificationComment(concreteItem);
        }
        requireCommentIfNeeded(concreteItem.getParentItem(), actionType, comment);
        LocalDateTime now = LocalDateTime.now();
        acceptControlIfCurrentManager(control, principal, "Контроль принят первым действием по карточке");
        concreteItem.setComment(comment);
        if (specialistActionConcrete
                && status != ManagerDailyControlItemStatus.RESOLVED
                && actionType == ManagerDailyControlActionType.ACTION_TAKEN
                && manualWorkerNotification
                && !notifyWorkerAboutTaskRequest(concreteItem, control)) {
            String failureReason = safe(concreteItem.getWorkerNotificationFailureReason());
            String failureAction = requiresWorkerExplanation(concreteItem)
                    ? "Запрос специалисту не доставлен"
                    : "Напоминание специалисту не доставлено";
            concreteItem.setComment(failureReason.isBlank()
                    ? failureAction
                    : failureAction + ": " + failureReason);
            concreteItem.setStatus(ManagerDailyControlItemStatus.OPEN);
            concreteItem.setActionType(null);
            concreteItem.setResolvedAt(null);
            concreteItem.setAutomaticResolution(false);
            concreteItem.setFollowUpAt(null);
            concreteItem.setLastManualTouchAt(now);
            ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);
            control.setLastActivityAt(now);
            dailyControlRepository.save(control);
            invalidateManagerPerformance();
            return concreteItemResponse(savedConcreteItem);
        }
        if (specialistActionConcrete
                && status != ManagerDailyControlItemStatus.RESOLVED
                && actionType == ManagerDailyControlActionType.ACTION_TAKEN
                && !manualWorkerNotification
                && requiresWorkerExplanation(concreteItem)
                && concreteItem.getWorkerExplanationAt() == null
                && !canOverrideWorkerExplanation(authentication)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Сначала запросите пояснение специалиста или зафиксируйте эскалацию с комментарием"
            );
        }
        if (specialistActionConcrete
                && status == ManagerDailyControlItemStatus.RESOLVED
                && requiresWorkerExplanation(concreteItem)
                && concreteItem.getWorkerExplanationAt() == null
                && !canOverrideWorkerExplanation(authentication)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Закрыть карточку можно после ответа специалиста или администратором/владельцем"
            );
        }
        recordConcreteEpisode(concreteItem, status, false);
        concreteItem.setStatus(status);
        concreteItem.setActionType(actionType);
        concreteItem.setResolvedAt(status == ManagerDailyControlItemStatus.RESOLVED ? now : null);
        concreteItem.setAutomaticResolution(false);
        boolean movedToReminder = false;
        if ("ORDER".equals(concreteItem.getEntityType()) && status != ManagerDailyControlItemStatus.RESOLVED) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(now.plusDays(MANUAL_FOLLOW_UP_DAYS));
            if (actionType == ManagerDailyControlActionType.ACTION_TAKEN) {
                movedToReminder = movePaymentOrderToReminderAfterManualSend(concreteItem);
            }
        } else if (clientChatAuditConcrete) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(null);
            clientChatMessageTrackerService.markAuditReviewed(
                    concreteItem.getEntityId(),
                    actorUserId(principal),
                    comment
            );
            concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
            concreteItem.setActionType(ManagerDailyControlActionType.RESOLVED);
            concreteItem.setResolvedAt(now);
            status = ManagerDailyControlItemStatus.RESOLVED;
            actionType = ManagerDailyControlActionType.RESOLVED;
        } else if (clientChatUnansweredConcrete) {
            concreteItem.setLastManualTouchAt(now);
            clientChatMessageTrackerService.markFromManagerControl(
                    concreteItem.getEntityId(),
                    actionType,
                    comment,
                    actorUserId(principal)
            );
            if (actionType == ManagerDailyControlActionType.ACTION_TAKEN
                    || actionType == ManagerDailyControlActionType.ACKNOWLEDGED
                    || actionType == ManagerDailyControlActionType.RESOLVED) {
                concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
                concreteItem.setResolvedAt(now);
                concreteItem.setFollowUpAt(null);
                status = ManagerDailyControlItemStatus.RESOLVED;
            } else if (actionType == ManagerDailyControlActionType.DEFERRED) {
                concreteItem.setStatus(ManagerDailyControlItemStatus.OPEN);
                concreteItem.setResolvedAt(null);
                concreteItem.setFollowUpAt(null);
                status = ManagerDailyControlItemStatus.OPEN;
            }
        } else if (specialistActionConcrete
                && status != ManagerDailyControlItemStatus.RESOLVED
                && actionType != ManagerDailyControlActionType.ACKNOWLEDGED) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(actionType == ManagerDailyControlActionType.DEFERRED || requiresWorkerExplanation(concreteItem)
                    ? workerTaskFollowUpAt(now)
                    : nextDayFollowUpAt(now));
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

        if (status == ManagerDailyControlItemStatus.RESOLVED && !clientChatUnansweredConcrete) {
            recordGamificationControlAction(
                    control,
                    "concrete:" + savedConcreteItem.getId(),
                    savedConcreteItem.getParentItem() == null ? null : savedConcreteItem.getParentItem().getReasonCode(),
                    savedConcreteItem.getCreatedAt(),
                    savedConcreteItem.getResolvedAt(),
                    savedConcreteItem.getTitle()
            );
        }

        if (actionType == ManagerDailyControlActionType.DEFERRED) {
            notifyOwnersAboutDeferredConcreteItem(control, savedConcreteItem, principal);
        }

        return concreteItemResponse(savedConcreteItem);
    }

    private void recordGamificationControlAction(
            ManagerDailyControl control,
            String uniqueKey,
            String reasonCode,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            String label
    ) {
        Manager manager = control == null ? null : control.getManager();
        int target = controlCardTargetMinutes();
        int hard = controlCardHardMinutes(target);
        gamificationEventService.recordManagerControlAction(
                manager,
                uniqueKey,
                startedAt,
                completedAt,
                target,
                hard,
                "reason=" + safe(reasonCode) + ";label=" + safe(label)
        );
    }

    public ManagerControlConcreteItemResponse sendClientMessage(Long concreteItemId, Principal principal, Authentication authentication) {
        boolean stalePreparationReconciled = managerControlTransactionRunner.required(
                () -> reconcileStaleClientMessagePreparation(concreteItemId, principal, authentication)
        );
        if (stalePreparationReconciled) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Предыдущая отправка не завершилась. Карточка переведена на ручную сверку; проверьте чат клиента"
            );
        }
        PreparedClientMessage prepared = managerControlTransactionRunner.required(
                () -> prepareClientMessage(concreteItemId, principal, authentication)
        );
        long startedAt = System.currentTimeMillis();
        ClientMessageSendResult result;
        try {
            result = clientChatMessageSender.send(
                    prepared.company(),
                    prepared.managerClientId(),
                    prepared.groupId(),
                    prepared.message(),
                    telegramCopyButton(prepared.paymentInstruction())
            );
        } catch (Exception e) {
            finishClientMessageFailure(prepared, readableException(e), true);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сообщение клиенту не отправлено: " + readableException(e), e);
        }
        if (!result.sent()) {
            finishClientMessageFailure(prepared, clientMessageError(result), false);
            if (prepared.paymentInstruction() != null) {
                paymentInstructionOrchestrator.releaseKnownUnsent(
                        prepared.paymentInstruction(),
                        authentication
                );
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сообщение клиенту не отправлено: " + clientMessageError(result)
            );
        }
        try {
            return managerControlTransactionRunner.required(
                    () -> finishClientMessageSuccess(prepared, result, startedAt, principal, authentication)
            );
        } catch (RuntimeException finalizeFailure) {
            finishClientMessageFailure(
                    prepared,
                    "сообщение доставлено, но заказ изменился во время отправки; нужна ручная сверка: "
                            + readableException(finalizeFailure),
                    true
            );
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сообщение доставлено, но заказ изменился. Проверьте чат и карточку вручную",
                    finalizeFailure
            );
        }
    }

    private boolean reconcileStaleClientMessagePreparation(
            Long concreteItemId,
            Principal principal,
            Authentication authentication
    ) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findByIdForUpdate(concreteItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        requireControlAccess(concreteItem.getControl(), principal, authentication);
        if (!safe(concreteItem.getComment()).startsWith(CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX)) {
            return false;
        }

        LocalDateTime preparedAt = concreteItem.getLastManualTouchAt();
        LocalDateTime now = LocalDateTime.now();
        if (preparedAt != null && preparedAt.isAfter(now.minus(CLIENT_MESSAGE_PREPARED_STALE_AFTER))) {
            return false;
        }

        concreteItem.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        concreteItem.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        concreteItem.setLastManualTouchAt(now);
        concreteItem.setComment(limit(
                CLIENT_MESSAGE_DELIVERY_UNKNOWN_PREFIX
                        + " подготовка отправки прервалась; исход неизвестен, проверьте чат клиента вручную",
                1000
        ));
        dailyControlConcreteItemRepository.save(concreteItem);
        return true;
    }

    private void requireCurrentOrderAccess(Long orderId, Authentication authentication) {
        try {
            managerAccessService.requireOrderAccess(orderId, authentication);
        } catch (ResponseStatusException denied) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Заказ больше не доступен менеджеру этой карточки",
                    denied
            );
        }
    }

    private PreparedClientMessage prepareClientMessage(
            Long concreteItemId,
            Principal principal,
            Authentication authentication
    ) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findByIdForUpdate(concreteItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        ManagerDailyControl control = concreteItem.getControl();
        requireControlAccess(control, principal, authentication);
        if (concreteItem.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Карточка уже закрыта");
        }
        if (safe(concreteItem.getComment()).startsWith("client_message_delivery_")) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Предыдущая отправка еще не завершена. Проверьте чат клиента перед повтором"
            );
        }
        String entityType = safe(concreteItem.getEntityType());
        if (!"ORDER".equals(entityType) && !ENTITY_WORKER_ORDER_NEW.equals(entityType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Автоотправка клиенту доступна только для заказов");
        }
        Order order = orderRepository.findByIdForCounterUpdate(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ карточки контроля не найден"));
        requireCurrentOrderAccess(order.getId(), authentication);
        BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction paymentInstruction =
                isPaymentControlOrder(order)
                        ? paymentInstructionOrchestrator.prepareAuthorized(order.getId(), authentication)
                        : null;
        String message = paymentInstruction != null
                ? paymentInstruction.copyText()
                : clientControlMessage(concreteItem, order);
        if (message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для карточки не удалось собрать текст клиенту");
        }
        String deliveryToken = UUID.randomUUID().toString();
        PreparedClientMessage prepared = new PreparedClientMessage(
                concreteItem.getId(),
                order.getId(),
                order.getManager() == null ? null : order.getManager().getId(),
                orderStatusTitle(order),
                order.getCompany(),
                order.getManager() == null ? null : order.getManager().getClientId(),
                order.getCompany() == null ? null : order.getCompany().getGroupId(),
                message,
                paymentInstruction,
                deliveryToken,
                concreteItem.getStatus(),
                concreteItem.getActionType(),
                concreteItem.getComment(),
                concreteItem.getLastManualTouchAt(),
                concreteItem.getFollowUpAt(),
                concreteItem.getResolvedAt(),
                concreteItem.isAutomaticResolution()
        );
        concreteItem.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        concreteItem.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        concreteItem.setLastManualTouchAt(LocalDateTime.now());
        concreteItem.setComment(limit(CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX + deliveryToken, 1000));
        dailyControlConcreteItemRepository.save(concreteItem);
        return prepared;
    }

    private ManagerControlConcreteItemResponse finishClientMessageSuccess(
            PreparedClientMessage prepared,
            ClientMessageSendResult result,
            long startedAt,
            Principal principal,
            Authentication authentication
    ) {
        ManagerDailyControlConcreteItem concreteItem = lockedPreparedClientMessage(prepared);
        ManagerDailyControl control = concreteItem.getControl();
        requireControlAccess(control, principal, authentication);
        Order order = orderRepository.findByIdForCounterUpdate(prepared.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ карточки контроля не найден"));
        requireCurrentOrderAccess(order.getId(), authentication);
        Long currentManagerId = order.getManager() == null ? null : order.getManager().getId();
        if (!Objects.equals(prepared.orderManagerId(), currentManagerId)
                || !Objects.equals(prepared.orderStatus(), orderStatusTitle(order))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Менеджер или статус заказа изменился во время отправки"
            );
        }
        LocalDateTime now = LocalDateTime.now();
        recordConcreteEpisode(concreteItem, ManagerDailyControlItemStatus.RESOLVED, false);
        concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        concreteItem.setActionType(ManagerDailyControlActionType.RESOLVED);
        concreteItem.setLastManualTouchAt(now);
        concreteItem.setFollowUpAt(null);
        concreteItem.setResolvedAt(now);
        concreteItem.setAutomaticResolution(false);
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
                ManagerDailyControlActionType.RESOLVED,
                "Клиенту отправлено сообщение по карточке: " + concreteItem.getTitle()
                        + " через " + safe(result.channel())
                        + " за " + (System.currentTimeMillis() - startedAt) + " мс"
                        + statusNote
        );

        return concreteItemResponse(savedConcreteItem, prepared.message());
    }

    private void finishClientMessageFailure(
            PreparedClientMessage prepared,
            String error,
            boolean deliveryOutcomeUnknown
    ) {
        try {
            managerControlTransactionRunner.required(() -> {
                ManagerDailyControlConcreteItem item = lockedPreparedClientMessage(prepared);
                if (deliveryOutcomeUnknown) {
                    item.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
                    item.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
                    item.setComment(limit(
                            CLIENT_MESSAGE_DELIVERY_UNKNOWN_PREFIX + " исход отправки не подтвержден; "
                                    + "проверьте чат клиента перед повтором: " + safe(error),
                            1000
                    ));
                } else {
                    item.setStatus(prepared.previousStatus());
                    item.setActionType(prepared.previousActionType());
                    item.setComment(prepared.previousComment());
                    item.setLastManualTouchAt(prepared.previousLastManualTouchAt());
                    item.setFollowUpAt(prepared.previousFollowUpAt());
                    item.setResolvedAt(prepared.previousResolvedAt());
                    item.setAutomaticResolution(prepared.previousAutomaticResolution());
                }
                dailyControlConcreteItemRepository.save(item);
                return null;
            });
        } catch (RuntimeException finalizeFailure) {
            log.error("Не удалось зафиксировать результат отправки карточки {}", prepared.concreteItemId(), finalizeFailure);
        }
    }

    private ManagerDailyControlConcreteItem lockedPreparedClientMessage(PreparedClientMessage prepared) {
        ManagerDailyControlConcreteItem item = dailyControlConcreteItemRepository.findByIdForUpdate(prepared.concreteItemId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        String expected = CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX + prepared.deliveryToken();
        if (!expected.equals(safe(item.getComment()))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Состояние отправки карточки изменилось. Проверьте чат клиента"
            );
        }
        return item;
    }

    private record PreparedClientMessage(
            Long concreteItemId,
            Long orderId,
            Long orderManagerId,
            String orderStatus,
            Company company,
            String managerClientId,
            String groupId,
            String message,
            BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction paymentInstruction,
            String deliveryToken,
            ManagerDailyControlItemStatus previousStatus,
            ManagerDailyControlActionType previousActionType,
            String previousComment,
            LocalDateTime previousLastManualTouchAt,
            LocalDateTime previousFollowUpAt,
            LocalDateTime previousResolvedAt,
            boolean previousAutomaticResolution
    ) {
    }

    private TelegramTransferCopyButton telegramCopyButton(
            BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction paymentInstruction
    ) {
        return paymentInstruction == null
                ? null
                : TelegramTransferCopyButton.fromFrozenTransferNumber(
                        paymentInstruction.telegramCopyTransferNumber()
                ).orElse(null);
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
        boolean unansweredConcrete = ENTITY_CLIENT_CHAT_UNANSWERED.equals(safe(concreteItem.getEntityType()));
        boolean auditConcrete = ENTITY_CLIENT_CHAT_AUDIT.equals(safe(concreteItem.getEntityType()));
        if (!unansweredConcrete && !auditConcrete) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Ответ из карточки доступен только для клиентских сообщений"
            );
        }
        if (concreteItem.getEntityId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Карточка не связана с сообщением клиента");
        }

        ClientChatUnansweredItem unansweredItem = clientChatUnansweredItemRepository.findById(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Неотвеченное сообщение не найдено"));
        if (unansweredConcrete && unansweredItem.getStatus() != ClientChatUnansweredStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Это сообщение уже закрыто");
        }
        if (auditConcrete && !unansweredItem.isAuditRequired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Эта проверка уже закрыта");
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
        ManagerDailyControlItemStatus resultStatus = auditConcrete
                ? ManagerDailyControlItemStatus.RESOLVED
                : ManagerDailyControlItemStatus.ACTION_TAKEN;
        ManagerDailyControlActionType resultAction = auditConcrete
                ? ManagerDailyControlActionType.RESOLVED
                : ManagerDailyControlActionType.ACTION_TAKEN;
        recordConcreteEpisode(concreteItem, resultStatus, false);
        concreteItem.setStatus(resultStatus);
        concreteItem.setActionType(resultAction);
        concreteItem.setLastManualTouchAt(now);
        concreteItem.setResolvedAt(now);
        concreteItem.setAutomaticResolution(false);
        concreteItem.setFollowUpAt(null);
        concreteItem.setComment(limit("Ответ отправлен через " + safe(result.channel()) + ": " + message, 1000));
        ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);

        if (auditConcrete) {
            clientChatMessageTrackerService.markAuditReplySent(
                    unansweredItem.getId(),
                    actorUserId(principal),
                    message,
                    result.channel()
            );
        } else {
            clientChatMessageTrackerService.markConfirmedReply(
                    unansweredItem.getId(),
                    "Ответ отправлен из контроля менеджера через " + safe(result.channel()),
                    actorUserId(principal),
                    message
            );
        }
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
                resultAction,
                "Ответ клиенту отправлен из карточки: " + concreteItem.getTitle()
                        + " через " + safe(result.channel())
        );

        return concreteItemResponse(savedConcreteItem, message);
    }

    @Transactional(readOnly = true)
    public ManagerControlClientReplySuggestionResponse suggestClientReply(
            Long concreteItemId,
            Principal principal,
            Authentication authentication
    ) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findById(concreteItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        requireControlAccess(concreteItem.getControl(), principal, authentication);
        if ((!ENTITY_CLIENT_CHAT_UNANSWERED.equals(safe(concreteItem.getEntityType()))
                && !ENTITY_CLIENT_CHAT_AUDIT.equals(safe(concreteItem.getEntityType())))
                || concreteItem.getEntityId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Подсказка доступна только для клиентского сообщения"
            );
        }
        ClientChatUnansweredItem item = clientChatUnansweredItemRepository.findById(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Неотвеченное сообщение не найдено"));
        ClientChatReplySuggestionService.Suggestion suggestion =
                clientChatReplySuggestionService.suggest(item.getLastMessageText());
        return new ManagerControlClientReplySuggestionResponse(
                suggestion.message(),
                suggestion.reasonCode()
        );
    }

    @Transactional
    public ManagerControlConcreteItemResponse markClientMessageMisclassified(
            Long concreteItemId,
            ManagerControlItemActionRequest request,
            Principal principal,
            Authentication authentication
    ) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findById(concreteItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        ManagerDailyControl control = concreteItem.getControl();
        requireControlAccess(control, principal, authentication);
        if (!ENTITY_CLIENT_CHAT_UNANSWERED.equals(safe(concreteItem.getEntityType()))
                || concreteItem.getEntityId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Исправление отправителя доступно только для клиентских сообщений"
            );
        }

        String comment = limit(request == null ? null : request.comment(), 1000);
        LocalDateTime now = LocalDateTime.now();
        clientChatMessageTrackerService.markMisclassified(
                concreteItem.getEntityId(),
                actorUserId(principal),
                comment
        );
        recordConcreteEpisode(concreteItem, ManagerDailyControlItemStatus.RESOLVED, false);
        concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        concreteItem.setActionType(ManagerDailyControlActionType.RESOLVED);
        concreteItem.setComment(hasText(comment) ? comment : "Отправитель подтверждён как сотрудник");
        concreteItem.setResolvedAt(now);
        concreteItem.setLastManualTouchAt(now);
        concreteItem.setFollowUpAt(null);
        concreteItem.setAutomaticResolution(false);
        ManagerDailyControlConcreteItem saved = dailyControlConcreteItemRepository.save(concreteItem);
        updateParentItemFromConcreteItems(saved.getParentItem());

        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        control.setStatus(recalculateControlStatus(control));
        dailyControlRepository.save(control);
        saveEvent(
                control,
                saved.getParentItem(),
                actorUserId(principal),
                ManagerDailyControlEventType.ITEM_RESOLVED,
                ManagerDailyControlActionType.RESOLVED,
                "Исправлена роль отправителя клиентского сообщения: " + saved.getTitle()
        );
        return concreteItemResponse(saved);
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
        if (ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE.equals(entityType)
                || ManagerAutomationFailureService.ENTITY_COMMON_INVOICE_AUTOMATION.equals(entityType)) {
            return repairAutomationFailureConcreteItem(concreteItem, control, principal);
        }
        if ("COMMON_INVOICE".equals(entityType)) {
            return repairCommonInvoiceConcreteItem(concreteItem, control, principal);
        }
        if (ENTITY_PUBLICATION_DATE_REVIEW.equals(entityType)) {
            return repairPublicationDateConcreteItem(concreteItem, control, principal);
        }
        if (ENTITY_TELEGRAM_CHAT.equals(entityType)) {
            return repairTelegramChatConcreteItem(concreteItem, control, principal);
        }
        if ("COMPANY_CHAT_BINDING".equals(entityType)) {
            Company company = companyRepository.findById(concreteItem.getEntityId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания карточки контроля не найдена"));
            return repairCompanyChatBindingConcreteItem(concreteItem, control, company, principal);
        }
        if (ENTITY_ORDER_PAYMENT_INTEGRITY.equals(entityType)) {
            OrderPaymentIntegrityService.RepairResult result =
                    orderPaymentIntegrityService.repair(concreteItem.getEntityId());
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    "Заказ возвращен в «Оплачено», лишних ссылок закрыто: " + result.expiredLinks()
                            + ", платежных очередей закрыто: " + result.closedMessageStates()
                            + ". Следующий заказ не изменялся.",
                    principal,
                    "Устранен повторный платежный цикл"
            );
        }
        if (!ENTITY_WORKER_ORDER_NEW.equals(entityType)
                && !ENTITY_WORKER_ORDER_CORRECT.equals(entityType)
                && !"ORDER".equals(entityType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Автопочинка доступна только для заказов с клиентской автоматизацией");
        }
        Order order = orderRepository.findById(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ карточки контроля не найден"));
        if (isChatBindingIssueConcrete(concreteItem)) {
            return repairChatBindingIssueConcreteItem(concreteItem, control, order, principal);
        }
        boolean clientTextReminderRepair = order.isWaitingForClient()
                && "Новый".equals(orderStatusTitle(order))
                && (ENTITY_WORKER_ORDER_NEW.equals(entityType) || "ORDER".equals(entityType));
        if (!clientTextReminderRepair
                && !order.isWaitingForClient()
                && ENTITY_WORKER_ORDER_NEW.equals(entityType)) {
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    "Заказ уже не отмечен как «ждет клиента»",
                    principal,
                    "Статус ожидания клиента уже снят"
            );
        }

        if (!clientTextReminderRepair) {
            return repairOrderAutomationConcreteItem(concreteItem, control, order, principal);
        }

        LocalDate today = LocalDate.now();
        long waitingDays = daysSince(clientTextWaitingControlDate(order), today);
        if (waitingDays > ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS) {
            order.setWaitingForClient(false);
            order.setWaitingForClientChangedAt(null);
            orderRepository.save(order);
            scheduledClientMessageService.synchronizeClientTextReminderForOrder(order);
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

    private ManagerControlConcreteItemResponse repairAutomationFailureConcreteItem(
            ManagerDailyControlConcreteItem concreteItem,
            ManagerDailyControl control,
            Principal principal
    ) {
        Manager manager = control.getManager();
        Optional<ManagerAutomationFailureService.AutomationFailureIssue> currentIssue =
                managerAutomationFailureService.findIssue(
                        manager,
                        concreteItem.getEntityType(),
                        concreteItem.getEntityId()
                );
        if (currentIssue.isEmpty()) {
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    "Ошибка автоматизации уже устранена, карточка перепроверена",
                    principal,
                    "Перепроверена устраненная ошибка автоматизации"
            );
        }

        ManagerAutomationFailureService.AutomationFailureIssue issue = currentIssue.get();
        ensureAutomationChatReady(issue);

        Optional<ScheduledClientMessageService.RecoveredBadReviewDeliveryResult> recovered =
                recoverUncertainBadReviewDelivery(issue, manager);
        if (recovered.isPresent()) {
            ScheduledClientMessageService.RecoveredBadReviewDeliveryResult result = recovered.get();
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    result.message(),
                    principal,
                    result.retryScheduled()
                            ? "Подтверждена старая отправка счета и запланирована безопасная новая"
                            : "Подтверждена и закрыта зависшая отправка счета"
            );
        }

        ScheduledClientMessageService.ManualRetryResult retry =
                scheduledClientMessageService.retryNow(issue.stateId());
        Optional<ManagerAutomationFailureService.AutomationFailureIssue> remaining =
                managerAutomationFailureService.findIssue(
                        manager,
                        concreteItem.getEntityType(),
                        concreteItem.getEntityId()
                );
        if (remaining.isPresent()) {
            String retryError = safe(retry.errorMessage());
            if (retryError.isBlank()) {
                retryError = safe(retry.errorCode());
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Повторный запуск выполнен, но источник ошибки еще активен"
                            + (retryError.isBlank() ? "" : ": " + limit(retryError, 220))
            );
        }

        return resolveRepairedConcreteItem(
                concreteItem,
                control,
                "Задача автоматизации перезапущена и прошла повторную проверку",
                principal,
                "Повторно запущена и восстановлена клиентская автоматизация"
        );
    }

    private Optional<ScheduledClientMessageService.RecoveredBadReviewDeliveryResult> recoverUncertainBadReviewDelivery(
            ManagerAutomationFailureService.AutomationFailureIssue issue,
            Manager manager
    ) {
        if (issue == null || issue.stateId() == null) {
            return Optional.empty();
        }
        ScheduledClientMessageState state = scheduledClientMessageStateRepository.findById(issue.stateId()).orElse(null);
        if (state == null
                || state.getScenario() != ClientMessageScenario.BAD_REVIEW_INVOICE
                || !ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN.equals(state.getLastErrorCode())) {
            return Optional.empty();
        }
        boolean chatVerified = verifyUncertainDeliveryInWhatsAppChat(manager, state);
        return scheduledClientMessageService.recoverUncertainBadReviewInvoiceDelivery(state.getId(), chatVerified);
    }

    private boolean verifyUncertainDeliveryInWhatsAppChat(Manager manager, ScheduledClientMessageState state) {
        Company company = automationFailureCompany(state);
        if (company == null || safe(company.getGroupId()).isBlank() || safe(state.getDeliveryMessage()).isBlank()) {
            return false;
        }
        String chat = safe(company.getUrlChat()).toLowerCase(Locale.ROOT);
        if (!isWhatsAppChat(chat)) {
            return false;
        }
        LocalDateTime from = state.getDeliveryPreparedAt() != null
                ? state.getDeliveryPreparedAt()
                : state.getLastAttemptAt();
        try {
            return clientChatMessageReconciliationService.reconcileWhatsAppGroupContainsOutgoingText(
                    manager,
                    company.getGroupId(),
                    from,
                    state.getDeliveryMessage()
            );
        } catch (RuntimeException e) {
            log.warn(
                    "Не удалось сверить зависшую отправку счета по истории WhatsApp stateId={} companyId={}",
                    state.getId(),
                    company.getId(),
                    e
            );
            return false;
        }
    }
    private void ensureAutomationChatReady(
            ManagerAutomationFailureService.AutomationFailureIssue issue
    ) {
        if (issue == null || issue.stateId() == null) {
            return;
        }

        ScheduledClientMessageState state = scheduledClientMessageStateRepository
                .findById(issue.stateId())
                .orElse(null);
        Company company = automationFailureCompany(state);
        String errorCode = safe(state == null ? null : state.getLastErrorCode())
                .trim()
                .toLowerCase(Locale.ROOT);
        String chat = safe(company == null ? null : company.getUrlChat()).trim();
        String normalizedChat = chat.toLowerCase(Locale.ROOT);
        boolean supportedChat = isWhatsAppChat(normalizedChat)
                || isTelegramChat(normalizedChat)
                || isMaxChat(normalizedChat);
        if (company != null && "chat_platform_unknown".equals(errorCode) && !supportedChat) {
            String companyName = safe(company.getTitle()).trim();
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У компании"
                            + (companyName.isBlank() ? "" : " «" + companyName + "»")
                            + (chat.isBlank()
                            ? " не указана ссылка на клиентский чат. "
                            : " указана неподдерживаемая ссылка на чат: " + chat + ". ")
                            + "Автоматическая отправка работает только с WhatsApp, Telegram и MAX. "
                            + "Замените ссылку на поддерживаемый чат либо обработайте предложение вручную; "
                            + "после изменения ссылки нажмите «Починить» ещё раз."
            );
        }
        if (company == null
                || !isTelegramChat(normalizedChat)
                || company.getTelegramGroupChatId() != null) {
            return;
        }

        company = companyRepository.findById(company.getId()).orElse(company);
        if (company.getTelegramGroupChatId() != null) {
            return;
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                manualChatBindingRepairInstruction(company)
        );
    }

    private Company automationFailureCompany(ScheduledClientMessageState state) {
        if (state == null) {
            return null;
        }
        if (state.getCompanyId() != null) {
            Company company = companyRepository.findById(state.getCompanyId()).orElse(null);
            if (company != null) {
                return company;
            }
        }
        if (state.getOrderId() == null) {
            return null;
        }
        return orderRepository.findByIdForOrderDto(state.getOrderId())
                .map(Order::getCompany)
                .orElse(null);
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
        CommonInvoiceRepairOutcome outcome = invoiceOperationExecutor.execute(
                () -> repairCommonInvoiceOutsideControlTransaction(invoiceId)
        );
        return resolveRepairedConcreteItem(
                concreteItem,
                control,
                outcome.comment(),
                principal,
                outcome.eventDescription()
        );
    }

    private CommonInvoiceRepairOutcome repairCommonInvoiceOutsideControlTransaction(Long invoiceId) {
        CommonInvoice invoice = commonInvoiceRepository.findByIdWithAccount(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (invoice.getStatus() == CommonInvoiceStatus.COLLECTING
                && commonInvoicePublicationBlockerService.hasOverdueBlockers(
                commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId),
                LocalDateTime.now()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Это не техническая ошибка счета: один или несколько заказов отстают от публикации более 48 часов. "
                            + "Откройте карточки блокеров и проверьте клиента/автоответчик. Состав общего счета автоматически не меняется."
            );
        }
        if (invoice.getStatus() == CommonInvoiceStatus.COLLECTING
                || invoice.getStatus() == CommonInvoiceStatus.READY) {
            CommonInvoiceDetailsResponse details = commonBillingService.invoice(invoiceId);
            boolean sent = false;
            if (details != null
                    && details.summary() != null
                    && CommonInvoiceStatus.COLLECTING.name().equals(details.summary().status())
                    && details.orders() != null
                    && details.orders().stream().anyMatch(order ->
                    !order.ready() && Set.of("В проверку", "На проверке").contains(safe(order.orderStatus())))) {
                details = commonBillingService.approveReviewOrders(invoiceId);
                details = commonBillingService.invoice(invoiceId);
            }
            if (details != null
                    && details.summary() != null
                    && CommonInvoiceStatus.READY.name().equals(details.summary().status())) {
                details = commonBillingService.sendInvoice(invoiceId, true);
                sent = true;
            }
            String status = details == null || details.summary() == null
                    ? ""
                    : safe(details.summary().status());
            String lastError = details == null || details.summary() == null
                    ? ""
                    : safe(details.summary().lastError());
            if (CommonInvoiceStatus.COLLECTING.name().equals(status)) {
                int ready = details.summary().readyOrders();
                int total = details.summary().totalOrders();
                if (total == 0) {
                    CommonInvoiceDetailsResponse disabled = commonBillingService.disableEmptyInvoice(invoiceId);
                    String disabledStatus = disabled == null || disabled.summary() == null
                            ? ""
                            : safe(disabled.summary().status());
                    if (!CommonInvoiceStatus.DISABLED.name().equals(disabledStatus)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Пустой общий счет не перешел в безопасное отключенное состояние"
                        );
                    }
                    return new CommonInvoiceRepairOutcome(
                            "Пустой технический счет отключен: заказов и платежных признаков нет",
                            "Отключен пустой технический хвост общего счета"
                    );
                }
                return new CommonInvoiceRepairOutcome(
                        "Счет исправен и остается в сборе: " + Math.max(0, total - ready)
                                + " из " + total + " заказов еще в работе. Карточка убрана из замечаний.",
                        "Исключен исправный общий счет с незавершенными заказами"
                );
            }
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Счет обработан, но автоматическая отправка не прошла: " + limit(lastError, 220)
                );
            }
            if (!sent) {
                if (Set.of(
                        CommonInvoiceStatus.INVOICED.name(),
                        CommonInvoiceStatus.REMINDER.name(),
                        CommonInvoiceStatus.PARTIALLY_PAID.name()
                ).contains(status)) {
                    return new CommonInvoiceRepairOutcome(
                            "Общий счет уже был отправлен клиенту; карточка контроля перепроверена",
                            "Перепроверен уже отправленный общий счет"
                    );
                }
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Счет пересчитан, но не перешел в состояние, из которого его можно отправить"
                );
            }
            return new CommonInvoiceRepairOutcome(
                    "Позиции общего счета пересчитаны, готовые заказы одобрены, счет отправлен клиенту",
                    "Пересчитан и отправлен зависший общий счет"
            );
        }
        if (invoice.getStatus() == CommonInvoiceStatus.INVOICED
                || invoice.getStatus() == CommonInvoiceStatus.REMINDER
                || invoice.getStatus() == CommonInvoiceStatus.PARTIALLY_PAID) {
            CommonInvoiceDetailsResponse details = commonBillingService.sendManualReminder(invoiceId);
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Напоминание по общему счету не отправлено: " + limit(lastError, 220)
                );
            }
            return new CommonInvoiceRepairOutcome(
                    "Клиенту отправлено напоминание по зависшему общему счету",
                    "Повторно отправлено напоминание по общему счету"
            );
        }
        if (commonInvoiceStandaloneRouteRepairable(invoice)) {
            CommonInvoiceDetailsResponse details = commonBillingService.repairStandalonePaymentRouteConflict(invoiceId);
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (lastError.toLowerCase(Locale.ROOT).startsWith("standalone_payment_route_conflict")) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Автопочинка остановлена: отдельный платеж начат или его состояние неоднозначно. "
                                + "Проверьте T-Bank и ручные поступления."
                );
            }
            return new CommonInvoiceRepairOutcome(
                    "Одиночные платежи сверены, неинициализированные ссылки закрыты, общий счет пересчитан и отправлен заново",
                    "Восстановлен единый платежный маршрут общего счета"
            );
        }
        if (commonInvoiceUnsentTlsInitRepairable(invoice)) {
            CommonInvoiceDetailsResponse details = commonBillingService.recoverUnsentPaymentInitTlsFailure(invoiceId);
            String recoveredStatus = details == null || details.summary() == null
                    ? ""
                    : safe(details.summary().status());
            String recoveredError = details == null || details.summary() == null
                    ? ""
                    : safe(details.summary().lastError());
            if (!recoveredError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "TLS-сбой снят, но счет остался с ошибкой: " + limit(recoveredError, 180)
                );
            }
            if (CommonInvoiceStatus.COLLECTING.name().equals(recoveredStatus)) {
                return new CommonInvoiceRepairOutcome(
                        "TLS-сбой T-Bank снят; общий счет безопасно возвращен в сбор",
                        "Безопасно снят TLS-сбой создания платежной ссылки"
                );
            }
            boolean shouldSend = CommonInvoiceStatus.READY.name().equals(recoveredStatus)
                    || CommonInvoiceStatus.PARTIALLY_PAID.name().equals(recoveredStatus);
            if (shouldSend) {
                details = commonBillingService.sendInvoice(invoiceId, true);
            }
            String status = details == null || details.summary() == null ? "" : safe(details.summary().status());
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "TLS-сбой снят, но повторная отправка счета не прошла: " + limit(lastError, 180)
                );
            }
            if (!shouldSend) {
                if (Set.of(
                        CommonInvoiceStatus.PAID.name(),
                        CommonInvoiceStatus.ARCHIVED.name(),
                        CommonInvoiceStatus.DISABLED.name()
                ).contains(recoveredStatus)) {
                    return new CommonInvoiceRepairOutcome(
                            "TLS-сбой T-Bank снят; повторная отправка не требуется, счет перешел в статус «"
                                    + commonInvoiceStatusLabel(CommonInvoiceStatus.valueOf(recoveredStatus)) + "»",
                            "Безопасно снят TLS-сбой без повторной отправки закрытого счета"
                    );
                }
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "TLS-сбой снят, но счет не перешел в состояние для безопасной повторной отправки"
                );
            }
            if (details == null || details.summary() == null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "TLS-сбой снят, но результат повторной отправки счета не подтвержден"
                );
            }
            return new CommonInvoiceRepairOutcome(
                    "TLS-сбой T-Bank снят; новая платежная ссылка создана и счет повторно отправлен клиенту",
                    "Безопасно повторено создание платежной ссылки после сбоя TLS до отправки запроса"
            );
        }
        if (commonInvoiceMessageSendRepairable(invoice)) {
            CommonInvoiceDetailsResponse details = commonBillingService.sendInvoice(invoiceId, true);
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Повторная отправка общего счета не прошла: " + limit(lastError, 180)
                );
            }
            return new CommonInvoiceRepairOutcome(
                    "Общий счет повторно отправлен клиенту",
                    "Повторно отправлен общий счет после ошибки клиентского чата"
            );
        }
        if (commonInvoicePaymentNotificationRepairable(invoice)) {
            commonBillingService.resolvePaymentSuccessNotification(invoiceId);
            return new CommonInvoiceRepairOutcome(
                    "Ошибка уведомления об оплате закрыта",
                    "Закрыта ошибка уведомления об оплате общего счета"
            );
        }
        if (commonInvoiceReviewApprovalRepairable(invoice)) {
            CommonInvoiceDetailsResponse details = commonBillingService.retryAttention(invoiceId);
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Повторное одобрение не прошло: " + limit(lastError, 180)
                );
            }
            return new CommonInvoiceRepairOutcome(
                    "Даты назначены, заказы общего счета переведены в публикацию",
                    "Повторно выполнено массовое одобрение с назначением дат"
            );
        }
        if (commonInvoiceNextOrderRepairable(invoice)) {
            CommonInvoiceDetailsResponse details = commonBillingService.retryAttention(invoiceId);
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Повторное создание следующих заказов не прошло: " + limit(lastError, 180)
                );
            }
            return new CommonInvoiceRepairOutcome(
                    "Повторное создание следующих заказов запущено",
                    "Повторно обработан общий счет после ошибки создания следующих заказов"
            );
        }
        if (commonInvoiceWhatsappGroupTailRepairable(invoice)) {
            commonBillingService.resolveWhatsappGroupTail(invoiceId);
            return new CommonInvoiceRepairOutcome(
                    "Старый хвост WhatsApp groupId скрыт из контроля",
                    "Закрыта устаревшая ошибка WhatsApp groupId общего счета"
            );
        }
        if (commonInvoiceTechnicalTailRepairable(invoice)) {
            commonBillingService.resolveTechnicalTail(invoiceId);
            return new CommonInvoiceRepairOutcome(
                    "Технический хвост общего счета скрыт из контроля",
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

    private ManagerControlConcreteItemResponse repairChatBindingIssueConcreteItem(
            ManagerDailyControlConcreteItem concreteItem,
            ManagerDailyControl control,
            Order order,
            Principal principal
    ) {
        Company company = order == null ? null : order.getCompany();
        if (company == null || company.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У заказа нет компании для привязки группы");
        }
        return repairCompanyChatBindingConcreteItem(concreteItem, control, company, principal);
    }

    private ManagerControlConcreteItemResponse repairCompanyChatBindingConcreteItem(
            ManagerDailyControlConcreteItem concreteItem,
            ManagerDailyControl control,
            Company company,
            Principal principal
    ) {
        if (company == null || company.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У карточки нет компании для привязки группы");
        }
        if (!CompanyChatBindingPolicy.isRequired(company)) {
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    "Компания в статусе «Бан»: привязка группы не требуется, карточка скрыта из контроля",
                    principal,
                    "Закрыта лишняя карточка привязки соцсети"
            );
        }
        String before = clientTextChatBindingProblem(company);
        if (before.isBlank()) {
            return resolveRepairedConcreteItem(
                    concreteItem,
                    control,
                    "Группа уже привязана, карточка скрыта из контроля",
                    principal,
                    "Проверена привязка соцсети"
            );
        }

        String chat = safe(company.getUrlChat()).toLowerCase(Locale.ROOT);
        if (isWhatsAppChat(chat)) {
            WhatsAppGroupLinkSyncService.WhatsAppGroupRepairResult repairResult =
                    whatsAppGroupLinkSyncService.repairCompanyLink(company);
            company = companyRepository.findById(company.getId()).orElse(company);
            String after = clientTextChatBindingProblem(company);
            if (after.isBlank()) {
                return resolveRepairedConcreteItem(
                        concreteItem,
                        control,
                        "WhatsApp-группа найдена и привязана к компании",
                        principal,
                        "Проверена синхронизация WhatsApp-групп"
                );
            }
            String repairMessage = repairResult == null ? "" : safe(repairResult.message());
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    repairMessage.isBlank()
                            ? manualChatBindingRepairInstruction(company)
                            : repairMessage
            );
        }

        if (isTelegramChat(chat) || isMaxChat(chat)) {
            sharedChatLinkSyncService.syncSharedChatIds();
            company = companyRepository.findById(company.getId()).orElse(company);
            String after = clientTextChatBindingProblem(company);
            if (after.isBlank()) {
                return resolveRepairedConcreteItem(
                        concreteItem,
                        control,
                        "Группа уже была привязана по такой же ссылке, карточка скрыта из контроля",
                        principal,
                        "Проверена привязка соцсети"
                );
            }
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                manualChatBindingRepairInstruction(company)
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
        recordConcreteEpisode(concreteItem, ManagerDailyControlItemStatus.RESOLVED, false);
        concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        concreteItem.setActionType(ManagerDailyControlActionType.RESOLVED);
        concreteItem.setComment(limit(comment, 1000));
        concreteItem.setResolvedAt(now);
        concreteItem.setAutomaticResolution(false);
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

    private boolean isPaymentControlOrder(Order order) {
        return MANUAL_CONTACT_ORDER_STATUSES.contains(orderStatusTitle(order))
                && !"Новый".equals(orderStatusTitle(order))
                && !"На проверке".equals(orderStatusTitle(order));
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
        return managerDetails(control.getManager(), false);
    }

    @Transactional
    public ManagerControlCloseResponse closeDay(Long controlId, ManagerControlCloseRequest request, Principal principal, Authentication authentication) {
        ManagerDailyControl control = controlForAction(controlId, principal, authentication);
        List<ManagerDailyControlItem> items = dailyControlItemRepository.findByControl(control);
        acceptControlIfCurrentManager(control, principal, "Контроль принят перед закрытием");
        List<String> blockers = closeBlockers(control, items);
        updateQuality(control, items);
        if (!blockers.isEmpty()) {
            dailyControlRepository.save(control);
            saveEvent(control, null, actorUserId(principal), ManagerDailyControlEventType.CLOSE_ATTEMPT_BLOCKED, null,
                    String.join("; ", blockers));
            return closeResponse(control, false, blockers);
        }
        closeControl(
                control,
                items,
                LocalDateTime.now(),
                actorUserId(principal),
                safe(request == null ? null : request.comment())
        );
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
        return managerDetails(manager, false);
    }

    public ClientChatReconciliationResult reconcileClientMessages(
            Long managerId,
            Principal principal,
            Authentication authentication
    ) {
        if (managerId == null || managerId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный менеджер");
        }
        Manager manager = visibleManagers(principal, authentication).stream()
                .filter(item -> managerId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер недоступен"));
        return clientChatMessageReconciliationService.reconcileOpenWhatsAppMessages(manager);
    }

    private ManagerControlManagerDetailResponse managerDetails(Manager manager, boolean syncConcrete) {
        LocalDate today = LocalDate.now();
        ManagerDailyControl control = dailyControlRepository.findByControlDateAndManager(today, manager)
                .orElseGet(() -> transientControl(manager, today));
        List<ManagerDailyControlItem> items = control.getId() == null
                ? List.of()
                : dailyControlItemRepository.findByControl(control).stream()
                .filter(this::isActiveControlItem)
                .sorted(Comparator
                        .comparingInt(this::detailItemRank)
                        .thenComparing(ManagerDailyControlItem::getLabel, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ManagerDailyControlItem::getId))
                .toList();
        User user = manager.getUser();
        List<String> blockers = control.getId() == null ? List.of("Контроль еще не синхронизирован") : closeBlockers(control, items);

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
                control.getId() == null ? List.of() : workerExplanationStats(control),
                items.stream().map(item -> detailItem(manager, item, today, syncConcrete)).toList(),
                control.getId() == null ? List.of() : events(control)
        );
    }

    private ManagerDailyControl transientControl(Manager manager, LocalDate today) {
        ManagerDailyControl control = new ManagerDailyControl();
        control.setControlDate(today);
        control.setManager(manager);
        control.setManagerUserId(manager == null || manager.getUser() == null ? null : manager.getUser().getId());
        control.setStatus(ManagerDailyControlStatus.IN_PROGRESS);
        control.setQualityScore(100);
        control.setQualityGrade("A");
        return control;
    }

    @Transactional
    public ManagerControlManagerDetailResponse syncManagerDetails(Long managerId, Principal principal, Authentication authentication) {
        reconcileClientMessagesForControl();
        Manager manager = visibleManagers(principal, authentication).stream()
                .filter(item -> managerId != null && managerId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер недоступен"));
        managerControl(manager, LocalDate.now(), null, true, false);
        invalidateManagerPerformance();
        return managerDetails(manager, true);
    }

    @Transactional
    public ManagerControlManagerDetailResponse acceptControl(Long controlId, Principal principal, Authentication authentication) {
        ManagerDailyControl control = controlForAction(controlId, principal, authentication);
        acceptControlIfCurrentManager(control, principal, "Контроль принят явным действием");
        return managerDetails(control.getManager(), false);
    }

    private List<Manager> visibleManagers(Principal principal, Authentication authentication) {
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            List<Manager> managers = managerRepository.findAllWithUserAndImage();
            return managers.isEmpty() ? List.of() : managerRepository.findAllManagersWorkers(managers);
        }

        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            User owner = currentUser(principal);
            if (OWNER_CONTROL_ALL_MANAGERS.equalsIgnoreCase(safe(owner == null ? null : owner.getOwnerControlViewMode()))) {
                List<Manager> managers = managerRepository.findAllWithUserAndImage();
                return managers.isEmpty() ? List.of() : managerRepository.findAllManagersWorkers(managers);
            }
            List<Manager> managers = userService.findManagersByUserName(principal.getName()).stream().toList();
            return managers.isEmpty() ? List.of() : managerRepository.findAllManagersWorkers(managers);
        }

        if (managerPermissionService.hasRole(authentication, "MANAGER")) {
            User user = currentUser(principal);
            if (user == null || user.getId() == null) {
                return List.of();
            }
            return managerRepository.findByUserId(user.getId())
                    .map(List::of)
                    .orElseGet(List::of);
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
        if (hasActionItems(items) && control.getMorningCompletedAt() == null) {
            blockers.add("Контроль не принят в работу");
        }
        blockers.addAll(problemBlockers(items));
        return blockers;
    }

    private void closeControl(
            ManagerDailyControl control,
            List<ManagerDailyControlItem> items,
            LocalDateTime now,
            Long actorUserId,
            String comment
    ) {
        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        if (control.getFinalCheckedAt() == null) {
            control.setFinalCheckedAt(now);
        }
        control.setClosedAt(now);
        control.setClosedByUserId(actorUserId);
        control.setLastActivityAt(now);
        control.setStatus(recalculateControlStatus(items));
        updateQuality(control, items);
        dailyControlRepository.save(control);
        saveEvent(control, null, actorUserId, ManagerDailyControlEventType.CONTROL_CLOSED, null, comment);
    }

    private boolean reopenClosedControlIfNeeded(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        if (control == null || control.getClosedAt() == null) {
            return false;
        }
        if (items == null || items.stream().noneMatch(this::isOpenActionItem)) {
            return false;
        }
        control.setClosedAt(null);
        control.setClosedByUserId(null);
        control.setFinalCheckedAt(null);
        control.setLastActivityAt(LocalDateTime.now());
        saveEvent(control, null, null, ManagerDailyControlEventType.CONTROL_REOPENED, null, "Контроль снова открыт: появились открытые пункты");
        return true;
    }

    private boolean hasActionItems(List<ManagerDailyControlItem> items) {
        return items != null && items.stream()
                .anyMatch(item -> item.getGroup() == ManagerDailyControlGroup.ACTION && item.getCount() > 0);
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

    private boolean updateQuality(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        if (control == null || items == null) {
            return false;
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
        stageScore += control.getMorningCompletedAt() == null && hasActionItems(items) ? 10 : 0;
        stageScore += control.getClosedAt() == null && hasActionItems(items) ? 8 : 0;
        int quality = Math.max(0, 100 - riskScore - stageScore);
        String qualityGrade = quality >= 90 ? "A" : quality >= 75 ? "B" : quality >= 55 ? "C" : "D";
        boolean changed = control.getRiskScore() != riskScore
                || control.isFastClickRisk() != fastClickRisk
                || control.getQualityScore() != quality
                || !Objects.equals(control.getQualityGrade(), qualityGrade);
        if (!changed) {
            return false;
        }
        control.setRiskScore(riskScore);
        control.setFastClickRisk(fastClickRisk);
        control.setQualityScore(quality);
        control.setQualityGrade(qualityGrade);
        return true;
    }

    private boolean hasFastClickRisk(ManagerDailyControl control) {
        List<ManagerDailyControlEvent> actions = dailyControlEventRepository.findByControlOrderByCreatedAtDesc(control).stream()
                .filter(event -> isManagerClientMessageResolutionEvent(control, event))
                .sorted(Comparator.comparing(ManagerDailyControlEvent::getCreatedAt))
                .toList();
        if (actions.size() < 3) {
            return false;
        }
        int warningCount = Math.max(3, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_WARNING_COUNT,
                3
        ));
        int warningSeconds = Math.max(3, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_WARNING_SECONDS,
                10
        ));
        int criticalCount = Math.max(warningCount, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_CRITICAL_COUNT,
                10
        ));
        int criticalSeconds = Math.max(warningSeconds, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_CRITICAL_SECONDS,
                60
        ));
        return hasActionBurst(actions, warningCount, warningSeconds)
                || hasActionBurst(actions, criticalCount, criticalSeconds);
    }

    private boolean isManagerClientMessageResolutionEvent(
            ManagerDailyControl control,
            ManagerDailyControlEvent event
    ) {
        if (control == null || event == null || event.getCreatedAt() == null
                || event.getActorUserId() == null || event.getItem() == null
                || event.getActionType() == null
                || event.getActionType() == ManagerDailyControlActionType.DEFERRED
                || (event.getEventType() != ManagerDailyControlEventType.ITEM_ACTION
                && event.getEventType() != ManagerDailyControlEventType.ITEM_RESOLVED)) {
            return false;
        }
        Long managerUserId = control.getManagerUserId();
        if (managerUserId == null && control.getManager() != null
                && control.getManager().getUser() != null) {
            managerUserId = control.getManager().getUser().getId();
        }
        if (!Objects.equals(event.getActorUserId(), managerUserId)) {
            return false;
        }
        String reasonCode = safe(event.getItem().getReasonCode());
        return "UNANSWERED_CLIENT_MESSAGES".equals(reasonCode)
                || "SUSPICIOUS_CLIENT_CLOSURES".equals(reasonCode);
    }

    private boolean hasActionBurst(
            List<ManagerDailyControlEvent> actions,
            int count,
            int seconds
    ) {
        if (actions == null || actions.size() < count) {
            return false;
        }
        for (int index = count - 1; index < actions.size(); index++) {
            LocalDateTime first = actions.get(index - count + 1).getCreatedAt();
            LocalDateTime last = actions.get(index).getCreatedAt();
            if (first != null && last != null && ChronoUnit.SECONDS.between(first, last) <= seconds) {
                return true;
            }
        }
        return false;
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

    private ManagerControlManagerResponse managerControl(
            Manager manager,
            LocalDate today,
            ManagerPerformanceScoreResponse managerPerformance,
            boolean persist,
            boolean includeOperationalMetrics
    ) {
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
        List<ManagerAutomationFailureService.AutomationFailureIssue> automationFailures =
                managerAutomationFailureService.issues(manager, 10_000);
        Set<Long> automationInvoiceIds = automationFailures.stream()
                .map(ManagerAutomationFailureService.AutomationFailureIssue::commonInvoiceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        long automationFailureCount = automationFailures.size();
        long commonInvoiceActionCount = commonInvoiceActionCount(manager, automationInvoiceIds);
        long publicationDateIssueCount = reviewRepository.countPublicationDateIssuesByManager(manager);
        long chatBindingIssueCount = companyRepository.countChatBindingIssuesByManager(manager);
        long paymentIntegrityIssueCount = orderRepository.countPaymentIntegrityIssuesByManager(
                manager,
                PAYMENT_AUTOMATION_STATUSES
        );
        long telegramChatIssueCount = telegramChatIssueCompanies(manager, 10_000).size();
        long unansweredClientMessages = clientChatMessageTrackerService.countDue(manager);
        long suspiciousClientClosures = clientChatMessageTrackerService.countAuditRequired(manager);
        long leadActionCount = leadsRepository.countByLidStatusAndManager("Новый", manager)
                + leadsRepository.countByLidStatusAndManager("В работу", manager)
                + leadsRepository.countByLidStatusAndManagerAndDateNewTryLessThanEqual("Напоминание", manager, today);
        long leadsInWork = leadsRepository.countByLidStatusAndManager("В работе", manager);

        List<ManagerControlProblemResponse> problems = new ArrayList<>();
        addProblem(problems, "OVERDUE_ORDERS", "Просроченные заказы", overdueOrders, "CRITICAL", "ACTION", "schedule", ordersUrl(manager, null));
        addProblem(problems, "OPEN_RISKS", "Риски", openRisks, "CRITICAL", "ACTION", "warning", "/worker/risk");
        addProblem(problems, "REQUIRES_ATTENTION", "Требует внимания", requiresAttention, "CRITICAL", "ACTION", "error", ordersUrl(manager, "Требует внимания"));
        addProblem(problems, "AUTOMATION_FAILURES", "Ошибки счетов и сообщений", automationFailureCount, "CRITICAL", "ACTION", "sync_problem", "/admin/manager-control/" + manager.getId());
        addProblem(problems, "COMMON_INVOICES", "Общие счета", commonInvoiceActionCount, "CRITICAL", "ACTION", "receipt_long", "/admin/common-billing");
        addProblem(problems, "PAYMENT_INTEGRITY", "Повторная оплата", paymentIntegrityIssueCount, "CRITICAL", "ACTION", "payments", ordersUrl(manager, null));
        addProblem(problems, "PUBLICATION_DATE_ISSUES", "Публикация без даты", publicationDateIssueCount, "CRITICAL", "ACTION", "event_busy", ordersUrl(manager, "Публикация"));
        addProblem(problems, "CHAT_BINDING_ISSUES", "Привязка соцсетей", chatBindingIssueCount, "CRITICAL", "ACTION", "link_off", ordersUrl(manager, null));
        addProblem(problems, "TELEGRAM_CHAT_MIGRATION", "Telegram-группы", telegramChatIssueCount, "CRITICAL", "ACTION", "send", ordersUrl(manager, null));
        addProblem(problems, "UNANSWERED_CLIENT_MESSAGES", "Неотвеченные сообщения", unansweredClientMessages, "CRITICAL", "ACTION", "mark_chat_unread", "/admin/manager-control/" + manager.getId());
        addProblem(problems, "SUSPICIOUS_CLIENT_CLOSURES", "Ответ требует проверки", suspiciousClientClosures, "CRITICAL", "ACTION", "fact_check", "/admin/manager-control/" + manager.getId());
        addProblem(problems, "LEADS", "Лиды требуют действия", leadActionCount, "WARNING", "ACTION", "person_search", "/leads");
        addProblem(problems, "ORDERS_WORKLOAD", "Рабочие заказы", orderAttention, "INFO", "WORKLOAD", "inventory_2", ordersUrl(manager, null));
        addProblem(problems, "LEADS_WORKLOAD", "Лиды в работе", leadsInWork, "INFO", "WORKLOAD", "groups", "/leads");
        addProblem(problems, "WORKER_WORKLOAD", "Нагрузка специалистов", workerWorkloadCount, "INFO", "WORKLOAD", "engineering", firstWorkerSectionUrl(workerCounts.sections(), "WORKLOAD", "new"));

        List<ManagerControlSectionResponse> sections = workerCounts.sections();
        long criticalCount = overdueOrders + openRisks + requiresAttention + automationFailureCount + commonInvoiceActionCount + paymentIntegrityIssueCount + publicationDateIssueCount + chatBindingIssueCount
                + telegramChatIssueCount + unansweredClientMessages + suspiciousClientClosures + workerActionCount;
        long warningCount = leadActionCount;
        long workloadCount = orderAttention + workerWorkloadCount + leadsInWork;
        DailyControlSyncResult controlSync = persist
                ? syncDailyControl(manager, today, problems, sections, overdueStatuses)
                : readDailyControl(manager, today);
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
        String status = openCriticalCount > 0 || (controlSync.items().isEmpty() && criticalCount > 0)
                ? "RED"
                : handledCriticalCount > 0 || warningCount > 0 ? "YELLOW" : "GREEN";
        if (persist && updateQuality(controlSync.control(), controlSync.items())) {
            dailyControlRepository.save(controlSync.control());
        }
        List<String> blockers = controlSync.control().getId() == null
                ? List.of("Контроль еще не синхронизирован")
                : closeBlockers(controlSync.control(), controlSync.items());
        List<ManagerControlWorkerExplanationStatsResponse> workerExplanationStats = controlSync.control().getId() == null
                ? List.of()
                : workerExplanationStats(controlSync.control());
        List<ManagerDailyControlConcreteItem> balanceConcreteItems = controlSync.control().getId() == null
                ? List.of()
                : dailyControlConcreteItemRepository.findByControl(controlSync.control());
        var actionBalance = managerActionBalanceService.calculate(controlSync.items(), balanceConcreteItems);
        long actionCompletedCount = actionBalance.handledByManager();
        long actionTotalCount = actionBalance.total();
        long actionFinishedCount = actionCompletedCount + actionBalance.autoClosed();
        int actionProgressPercent = actionTotalCount <= 0
                ? 100
                : (int) Math.max(0, Math.min(100, Math.round(actionFinishedCount * 100D / actionTotalCount)));
        ManagerOperationalMetricsService.Metrics operational = includeOperationalMetrics
                ? managerOperationalMetricsService.calculate(manager, today, LocalDateTime.now())
                : null;
        if (operational == null) {
            operational = new ManagerOperationalMetricsService.Metrics(0, 0, 0, 0);
        }

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
                actionTotalCount,
                actionCompletedCount,
                actionProgressPercent,
                actionBalance.autoClosed(),
                actionBalance.remaining(),
                actionBalance.resolved(),
                actionBalance.actionTaken(),
                actionBalance.deferred(),
                actionBalance.acknowledged(),
                actionBalance.overdueRemaining(),
                actionBalance.riskRemaining(),
                actionBalance.unansweredRemaining(),
                actionBalance.otherRemaining(),
                leadActionCount,
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
                overdueStatuses,
                workerExplanationStats,
                operational.activeWorkSeconds(),
                operational.averageDailyWorkSeconds(),
                operational.averageReactionSeconds(),
                operational.reactionCount(),
                managerPerformance
        );
    }

    private void syncManagerActionConcreteItems(Manager manager, LocalDate today) {
        ManagerDailyControl control = dailyControlRepository.findByControlDateAndManager(today, manager).orElse(null);
        if (control == null) {
            return;
        }
        List<ManagerDailyControlItem> items = dailyControlItemRepository.findByControl(control);
        for (ManagerDailyControlItem item : items) {
            if (item == null
                    || item.getGroup() != ManagerDailyControlGroup.ACTION) {
                continue;
            }
            if (item.getCount() <= 0) {
                syncConcreteExamples(item, List.of());
                continue;
            }
            syncConcreteExamples(item, detailExamples(manager, item, today));
            reopenParentItemIfConcreteOpen(item);
        }
    }

    private List<ManagerControlWorkerExplanationStatsResponse> workerExplanationStats(ManagerDailyControl control) {
        if (control == null || control.getId() == null) {
            return List.of();
        }
        Map<Long, WorkerExplanationAccumulator> stats = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        dailyControlConcreteItemRepository.findByControl(control).stream()
                .filter(this::isWorkerExplanationTrackedConcrete)
                .filter(this::hasWorkerExplanationRequest)
                .forEach(item -> {
                    User worker = workerUserForStats(item);
                    if (worker == null || worker.getId() == null) {
                        return;
                    }
                    WorkerExplanationAccumulator accumulator = stats.computeIfAbsent(
                            worker.getId(),
                            userId -> new WorkerExplanationAccumulator(worker.getId(), userDisplayName(worker))
                    );
                    accumulator.add(
                            now,
                            workerExplanationStartedAt(item),
                            workerAcceptedExplanationAtForStats(item)
                    );
                });
        return stats.values().stream()
                .map(WorkerExplanationAccumulator::response)
                .sorted(Comparator
                        .comparingLong(ManagerControlWorkerExplanationStatsResponse::overdueCount).reversed()
                        .thenComparing(ManagerControlWorkerExplanationStatsResponse::unansweredCount, Comparator.reverseOrder())
                        .thenComparing(ManagerControlWorkerExplanationStatsResponse::workerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean isWorkerExplanationTrackedConcrete(ManagerDailyControlConcreteItem item) {
        return isSpecialistActionConcrete(item);
    }

    private boolean hasWorkerExplanationRequest(ManagerDailyControlConcreteItem item) {
        if (item == null) {
            return false;
        }
        if (item.getWorkerExplanationRequestedAt() != null
                || item.getWorkerNotificationAttemptedAt() != null
                || item.getWorkerNotificationSentAt() != null
                || item.getWorkerExplanationPromptedAt() != null
                || item.getWorkerExplanationAt() != null) {
            return true;
        }
        if (!isWorkerRiskConcrete(item) || item.getEntityId() == null) {
            return false;
        }
        WorkerRiskIncident incident = riskIncidentRepository.findById(item.getEntityId()).orElse(null);
        return incident != null
                && (incident.getResolutionAction() == WorkerRiskResolutionAction.EXPLANATION_REQUESTED
                || incident.getExplanationRequestedAt() != null
                || incident.getExplanationPromptedAt() != null
                || incident.getWorkerExplanationAt() != null);
    }

    private User workerUserForStats(ManagerDailyControlConcreteItem item) {
        if (item == null) {
            return null;
        }
        if (item.getWorkerNotificationUserId() != null) {
            return userRepository.findById(item.getWorkerNotificationUserId()).orElse(null);
        }
        return workerUserForTask(item);
    }

    private LocalDateTime workerExplanationStartedAt(ManagerDailyControlConcreteItem item) {
        if (item == null) {
            return null;
        }
        if (item.getWorkerNotificationSentAt() != null) {
            return item.getWorkerNotificationSentAt();
        }
        if (isWorkerRiskConcrete(item) && item.getEntityId() != null) {
            WorkerRiskIncident incident = riskIncidentRepository.findById(item.getEntityId()).orElse(null);
            if (incident != null
                    && (incident.getResolutionAction() == WorkerRiskResolutionAction.EXPLANATION_REQUESTED
                    || incident.getWorkerExplanationAt() != null
                    || incident.getExplanationPromptedAt() != null)) {
                return firstNonNullTime(
                        incident.getExplanationRequestedAt(),
                        incident.getExplanationPromptedAt(),
                        incident.getCreatedAt(),
                        item.getWorkerNotificationAttemptedAt()
                );
            }
        }
        return item.getWorkerNotificationAttemptedAt();
    }

    private LocalDateTime workerAcceptedExplanationAtForStats(ManagerDailyControlConcreteItem item) {
        if (item == null) {
            return null;
        }
        if (!isWorkerRiskConcrete(item) && item.getWorkerExplanationAt() != null) {
            return item.getWorkerExplanationAt();
        }
        if (!isWorkerRiskConcrete(item) || item.getEntityId() == null) {
            return null;
        }
        return riskIncidentRepository.findById(item.getEntityId())
                .map(WorkerRiskIncident::getExplanationAcceptedAt)
                .orElse(null);
    }

    private static class WorkerExplanationAccumulator {
        private final Long workerUserId;
        private final String workerName;
        private long requestCount;
        private long unansweredCount;
        private long overdueCount;
        private long answeredCount;
        private long responseMinutesTotal;

        private WorkerExplanationAccumulator(Long workerUserId, String workerName) {
            this.workerUserId = workerUserId;
            this.workerName = workerName;
        }

        private void add(
                LocalDateTime now,
                LocalDateTime startedAt,
                LocalDateTime explanationAt
        ) {
            requestCount++;
            if (explanationAt == null) {
                unansweredCount++;
                if (startedAt != null && Duration.between(startedAt, now).toHours() >= WORKER_TASK_FOLLOW_UP_HOURS) {
                    overdueCount++;
                }
                return;
            }
            if (startedAt != null) {
                answeredCount++;
                responseMinutesTotal += Math.max(0, Duration.between(startedAt, explanationAt).toMinutes());
            }
        }

        private ManagerControlWorkerExplanationStatsResponse response() {
            double averageResponseMinutes = answeredCount == 0
                    ? 0
                    : Math.round((responseMinutesTotal / (double) answeredCount) * 10.0) / 10.0;
            return new ManagerControlWorkerExplanationStatsResponse(
                    workerUserId,
                    workerName,
                    requestCount,
                    unansweredCount,
                    overdueCount,
                    averageResponseMinutes
            );
        }
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
        if (control.getId() != null) {
            control = dailyControlRepository.findByIdForUpdate(control.getId()).orElse(control);
        }
        Map<String, ManagerDailyControlItem> existing = dailyControlItemRepository.findByControl(control).stream()
                .collect(Collectors.toMap(ManagerDailyControlItem::getItemKey, Function.identity(), (left, right) -> left));
        Set<String> activeKeys = new HashSet<>();
        List<ManagerDailyControlItem> currentItems = new ArrayList<>();

        for (ControlItemInput input : controlItemInputs(problems, sections)) {
            activeKeys.add(input.itemKey());
            ManagerDailyControlItem item = existing.get(input.itemKey());
            boolean created = false;
            if (item == null) {
                item = new ManagerDailyControlItem();
                item.setControl(control);
                item.setItemKey(input.itemKey());
                item.setStatus(ManagerDailyControlItemStatus.OPEN);
                item.setAutomaticResolution(false);
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
            boolean changed = applyControlItemSnapshot(item, input);
            if (shouldReopen || shouldReopenFollowUp || shouldReopenConcrete) {
                item.setStatus(ManagerDailyControlItemStatus.OPEN);
                item.setAutomaticResolution(false);
                item.setActionType(null);
                item.setComment(null);
                item.setResolvedAt(null);
                changed = true;
            }
            ManagerDailyControlItem saved = created || changed ? dailyControlItemRepository.save(item) : item;
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
                recordItemEpisode(item, ManagerDailyControlItemStatus.RESOLVED, true);
                item.setStatus(ManagerDailyControlItemStatus.RESOLVED);
                item.setResolvedAt(LocalDateTime.now());
                item.setAutomaticResolution(true);
                ManagerDailyControlItem resolvedItem = dailyControlItemRepository.save(item);
                resolveOpenConcreteItemsForResolvedParent(resolvedItem);
                currentItems.add(resolvedItem);
                saveEvent(control, item, null, ManagerDailyControlEventType.ITEM_RESOLVED, ManagerDailyControlActionType.RESOLVED, "Автоматически закрыто: пункт больше не требует внимания");
            } else if (!currentItems.contains(item)) {
                currentItems.add(item);
            }
        }

        boolean reopened = reopenClosedControlIfNeeded(control, currentItems);
        ManagerDailyControlStatus nextStatus = recalculateControlStatus(currentItems);
        if (control.getStatus() != nextStatus) {
            control.setStatus(nextStatus);
            saveEvent(control, null, null, ManagerDailyControlEventType.CONTROL_STATUS_CHANGED, null, nextStatus.name());
            dailyControlRepository.save(control);
        } else if (reopened) {
            dailyControlRepository.save(control);
        }
        if (autoCloseControlIfReady(control, LocalDateTime.now())) {
            currentItems = activeControlItems(dailyControlItemRepository.findByControl(control));
        }

        Map<String, ManagerDailyControlItem> itemsByKey = currentItems.stream()
                .collect(Collectors.toMap(ManagerDailyControlItem::getItemKey, Function.identity(), (left, right) -> left));
        return new DailyControlSyncResult(control, currentItems, itemsByKey);
    }

    private boolean applyControlItemSnapshot(ManagerDailyControlItem item, ControlItemInput input) {
        boolean changed = false;
        if (item.getItemType() != input.itemType()) {
            item.setItemType(input.itemType());
            changed = true;
        }
        if (!Objects.equals(item.getEntityId(), input.entityId())) {
            item.setEntityId(input.entityId());
            changed = true;
        }
        if (!Objects.equals(item.getWorkerId(), input.workerId())) {
            item.setWorkerId(input.workerId());
            changed = true;
        }
        if (!Objects.equals(item.getSectionCode(), input.sectionCode())) {
            item.setSectionCode(input.sectionCode());
            changed = true;
        }
        if (!Objects.equals(item.getReasonCode(), input.reasonCode())) {
            item.setReasonCode(input.reasonCode());
            changed = true;
        }
        if (!Objects.equals(item.getLabel(), input.label())) {
            item.setLabel(input.label());
            changed = true;
        }
        if (!Objects.equals(item.getTargetUrl(), input.targetUrl())) {
            item.setTargetUrl(input.targetUrl());
            changed = true;
        }
        if (item.getCount() != input.count()) {
            item.setCount(input.count());
            changed = true;
        }
        if (item.getSeverity() != input.severity()) {
            item.setSeverity(input.severity());
            changed = true;
        }
        if (item.getGroup() != input.group()) {
            item.setGroup(input.group());
            changed = true;
        }
        return changed;
    }

    private DailyControlSyncResult readDailyControl(Manager manager, LocalDate today) {
        ManagerDailyControl control = dailyControlRepository.findByControlDateAndManager(today, manager)
                .orElseGet(() -> transientControl(manager, today));
        if (control.getId() == null) {
            return new DailyControlSyncResult(control, List.of(), Map.of());
        }
        List<ManagerDailyControlItem> items = activeControlItems(dailyControlItemRepository.findByControl(control));
        Map<String, ManagerDailyControlItem> itemsByKey = items.stream()
                .collect(Collectors.toMap(ManagerDailyControlItem::getItemKey, Function.identity(), (left, right) -> left));
        return new DailyControlSyncResult(control, items, itemsByKey);
    }

    private List<ControlItemInput> controlItemInputs(
            List<ManagerControlProblemResponse> problems,
            List<ManagerControlSectionResponse> sections
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
        SlaWindow sla = slaWindow(response.code(), item.getCreatedAt(), item.getResolvedAt());
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
                item.getComment(),
                sla.firstObservedAt(),
                sla.targetDeadlineAt(),
                sla.hardDeadlineAt(),
                sla.state()
        );
    }

    private SlaWindow slaWindow(String code, LocalDateTime firstObservedAt, LocalDateTime completedAt) {
        if (!appSettingService.getBoolean("manager.sla.enabled", false)) {
            return new SlaWindow(null, null, null, null);
        }
        LocalDateTime started = firstObservedAt == null ? LocalDateTime.now() : firstObservedAt;
        int targetMinutes = controlCardTargetMinutes();
        int hardMinutes = controlCardHardMinutes(targetMinutes);
        LocalDateTime target = started.plusMinutes(targetMinutes);
        LocalDateTime hard = started.plusMinutes(hardMinutes);
        LocalDateTime reference = completedAt == null ? LocalDateTime.now() : completedAt;
        String state = reference.isAfter(hard) ? "OVERDUE" : reference.isAfter(target) ? "LATE" : "TARGET";
        if (completedAt != null) state = "COMPLETED_" + state;
        return new SlaWindow(started, target, hard, state);
    }

    private int controlCardTargetMinutes() {
        return Math.max(1, appSettingService.getInt(CONTROL_CARD_TARGET_SETTING, CONTROL_CARD_TARGET_MINUTES));
    }

    private int controlCardHardMinutes(int targetMinutes) {
        return Math.max(targetMinutes, appSettingService.getInt(CONTROL_CARD_HARD_SETTING, CONTROL_CARD_HARD_MINUTES));
    }

    private ManagerControlSectionResponse decorate(ManagerControlSectionResponse response, ManagerDailyControlItem item) {
        if (item == null) {
            return response;
        }
        SlaWindow sla = slaWindow(response.code(), item.getCreatedAt(), item.getResolvedAt());
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
                item.getComment(),
                sla.firstObservedAt(),
                sla.targetDeadlineAt(),
                sla.hardDeadlineAt(),
                sla.state()
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

    private ManagerControlItemDetailResponse detailItem(
            Manager manager,
            ManagerDailyControlItem item,
            LocalDate today,
            boolean syncConcrete
    ) {
        List<ManagerControlConcreteItemResponse> freshExamples = detailExamples(manager, item, today);
        List<ManagerControlConcreteItemResponse> examples = syncConcrete
                ? syncConcreteExamples(item, freshExamples)
                : readConcreteExamples(item, freshExamples);
        examples = examples.stream()
                .map(example -> decorateConcreteSla(item, example))
                .toList();
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
        if ("AUTOMATION_FAILURES".equals(item.getReasonCode())) {
            return automationFailureExamples(manager, limit);
        }
        if ("COMMON_INVOICES".equals(item.getReasonCode())) {
            return commonInvoiceExamples(manager, today, limit);
        }
        if ("PAYMENT_INTEGRITY".equals(item.getReasonCode())) {
            return paymentIntegrityIssueExamples(manager, today, limit);
        }
        if ("PUBLICATION_DATE_ISSUES".equals(item.getReasonCode())) {
            return publicationDateIssueExamples(manager, limit);
        }
        if ("CHAT_BINDING_ISSUES".equals(item.getReasonCode())) {
            return chatBindingIssueExamples(manager, today, limit);
        }
        if ("TELEGRAM_CHAT_MIGRATION".equals(item.getReasonCode())) {
            return telegramChatIssueExamples(manager, limit);
        }
        if ("UNANSWERED_CLIENT_MESSAGES".equals(item.getReasonCode())) {
            return unansweredClientMessageExamples(manager, limit);
        }
        if ("SUSPICIOUS_CLIENT_CLOSURES".equals(item.getReasonCode())) {
            return suspiciousClientClosureExamples(manager, limit);
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

    private List<ManagerControlConcreteItemResponse> readConcreteExamples(
            ManagerDailyControlItem parentItem,
            List<ManagerControlConcreteItemResponse> freshExamples
    ) {
        if (parentItem == null || parentItem.getId() == null) {
            return List.of();
        }
        List<ManagerDailyControlConcreteItem> storedExamples = dailyControlConcreteItemRepository.findByParentItem(parentItem);
        if (storedExamples.isEmpty()) {
            return freshExamples;
        }
        Map<String, ManagerControlConcreteItemResponse> freshByKey = freshExamples.stream()
                .collect(Collectors.toMap(this::concreteEntityKey, Function.identity(), (left, right) -> left));
        Map<String, ManagerDailyControlConcreteItem> storedByKey = storedExamples.stream()
                .collect(Collectors.toMap(ManagerDailyControlConcreteItem::getEntityKey, Function.identity(), (left, right) -> left));
        Set<String> freshKeys = freshByKey.keySet();
        resolveStaleConcreteItems(parentItem, storedByKey, freshKeys);
        boolean reopenedUnanswered = false;
        for (ManagerDailyControlConcreteItem stored : storedExamples) {
            if (freshKeys.contains(stored.getEntityKey()) && reopenActiveClientChatUnanswered(stored)) {
                dailyControlConcreteItemRepository.save(stored);
                reopenedUnanswered = true;
            }
        }
        if (reopenedUnanswered) {
            reopenParentItemIfConcreteOpen(parentItem);
        }
        List<ManagerControlConcreteItemResponse> visibleStored = storedExamples.stream()
                .filter(item -> item.getStatus() != ManagerDailyControlItemStatus.RESOLVED)
                .filter(item -> !isClosedClientChatUnansweredConcrete(item))
                .filter(item -> !isConcreteSnoozed(item))
                .sorted(Comparator
                        .comparing(ManagerDailyControlConcreteItem::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ManagerDailyControlConcreteItem::getId, Comparator.nullsLast(Long::compareTo)))
                .map(item -> {
                    if (reopenQueuedChatBindingRepair(item)) {
                        dailyControlConcreteItemRepository.save(item);
                    }
                    ManagerControlConcreteItemResponse fresh = freshByKey.get(item.getEntityKey());
                    return concreteItemResponse(
                            item,
                            fresh == null ? null : fresh.contactText(),
                            fresh == null ? null : fresh.specialistName()
                    );
                })
                .toList();
        if (freshExamples.isEmpty()) {
            return visibleStored;
        }
        List<ManagerControlConcreteItemResponse> merged = new ArrayList<>(visibleStored);
        freshExamples.stream()
                .filter(example -> !storedByKey.containsKey(concreteEntityKey(example)))
                .forEach(merged::add);
        return merged;
    }

    private boolean isClosedClientChatUnansweredConcrete(ManagerDailyControlConcreteItem item) {
        return item != null
                && ENTITY_CLIENT_CHAT_UNANSWERED.equals(item.getEntityType())
                && item.getStatus() != ManagerDailyControlItemStatus.OPEN
                && item.getStatus() != ManagerDailyControlItemStatus.DEFERRED;
    }

    private List<ManagerControlConcreteItemResponse> syncConcreteExamples(
            ManagerDailyControlItem parentItem,
            List<ManagerControlConcreteItemResponse> examples
    ) {
        if (parentItem == null) {
            return List.of();
        }
        Map<String, ManagerControlConcreteItemResponse> uniqueExamples = examples.stream()
                .collect(Collectors.toMap(
                        this::concreteEntityKey,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, ManagerDailyControlConcreteItem> existing = dailyControlConcreteItemRepository
                .findByParentItemForUpdate(parentItem).stream()
                .collect(Collectors.toMap(ManagerDailyControlConcreteItem::getEntityKey, Function.identity(), (left, right) -> left));
        Set<String> freshKeys = uniqueExamples.keySet();
        resolveStaleConcreteItems(parentItem, existing, freshKeys);
        if (uniqueExamples.isEmpty()) {
            return List.of();
        }
        List<ManagerControlConcreteItemResponse> synced = new ArrayList<>();
        boolean reopenedUnanswered = false;
        for (Map.Entry<String, ManagerControlConcreteItemResponse> entry : uniqueExamples.entrySet()) {
            String key = entry.getKey();
            ManagerControlConcreteItemResponse example = entry.getValue();
            ManagerDailyControlConcreteItem concreteItem = existing.get(key);
            boolean created = false;
            if (concreteItem == null) {
                concreteItem = new ManagerDailyControlConcreteItem();
                concreteItem.setControl(parentItem.getControl());
                concreteItem.setParentItem(parentItem);
                concreteItem.setEntityKey(key);
                concreteItem.setStatus(ManagerDailyControlItemStatus.OPEN);
                created = true;
            }
            boolean changed = applyConcreteItemSnapshot(concreteItem, example);
            if (reopenActiveClientChatUnanswered(concreteItem)) {
                changed = true;
                reopenedUnanswered = true;
            }
            if (reopenQueuedChatBindingRepair(concreteItem)) {
                changed = true;
            }
            if (reopenConcreteItemIfFollowUpDue(concreteItem)) {
                changed = true;
            }
            if (reopenResolvedConcreteItemIfExpired(concreteItem)) {
                changed = true;
            }
            if (reopenActiveAutomationConcreteItem(concreteItem)) {
                changed = true;
            }
            if (reopenResolvedConcreteItemStillActive(parentItem, concreteItem)) {
                changed = true;
            }
            if (isResolvedConcreteItemHiddenForToday(concreteItem)) {
                if (created || changed) {
                    dailyControlConcreteItemRepository.save(concreteItem);
                }
                continue;
            }
            if (isConcreteSnoozed(concreteItem)) {
                if (created || changed) {
                    dailyControlConcreteItemRepository.save(concreteItem);
                }
                continue;
            }
            ManagerDailyControlConcreteItem saved = created || changed
                    ? dailyControlConcreteItemRepository.save(concreteItem)
                    : concreteItem;
            existing.put(key, saved);
            synced.add(concreteItemResponse(
                    saved,
                    example.contactText(),
                    example.specialistName()
            ));
        }
        if (reopenedUnanswered) {
            reopenParentItemIfConcreteOpen(parentItem);
        }
        return synced;
    }

    private boolean reopenActiveClientChatUnanswered(ManagerDailyControlConcreteItem item) {
        if (item == null
                || !ENTITY_CLIENT_CHAT_UNANSWERED.equals(item.getEntityType())
                || item.getStatus() == ManagerDailyControlItemStatus.OPEN) {
            return false;
        }
        item.setStatus(ManagerDailyControlItemStatus.OPEN);
        item.setActionType(null);
        item.setResolvedAt(null);
        item.setAutomaticResolution(false);
        item.setFollowUpAt(null);
        return true;
    }

    private void resolveStaleConcreteItems(
            ManagerDailyControlItem parentItem,
            Map<String, ManagerDailyControlConcreteItem> existing,
            Set<String> freshKeys
    ) {
        if (parentItem == null
                || existing == null
                || existing.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ManagerDailyControlConcreteItem item : existing.values()) {
            if (item == null
                    || item.getStatus() == ManagerDailyControlItemStatus.RESOLVED
                    || freshKeys.contains(item.getEntityKey())) {
                continue;
            }
            recordConcreteEpisode(item, ManagerDailyControlItemStatus.RESOLVED, true);
            item.setStatus(ManagerDailyControlItemStatus.RESOLVED);
            item.setActionType(ManagerDailyControlActionType.RESOLVED);
            item.setComment("Проблема больше не актуальна и закрыта автоматически");
            item.setResolvedAt(now);
            item.setAutomaticResolution(true);
            item.setFollowUpAt(null);
            item.setLastManualTouchAt(null);
            dailyControlConcreteItemRepository.save(item);
        }
    }

    private void resolveOpenConcreteItemsForResolvedParent(ManagerDailyControlItem parentItem) {
        if (parentItem == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ManagerDailyControlConcreteItem item : dailyControlConcreteItemRepository.findByParentItem(parentItem)) {
            if (item == null || item.getStatus() != ManagerDailyControlItemStatus.OPEN) {
                continue;
            }
            recordConcreteEpisode(item, ManagerDailyControlItemStatus.RESOLVED, true);
            item.setStatus(ManagerDailyControlItemStatus.RESOLVED);
            item.setActionType(ManagerDailyControlActionType.RESOLVED);
            item.setComment("Родительский пункт больше не требует внимания");
            item.setResolvedAt(now);
            item.setAutomaticResolution(true);
            item.setFollowUpAt(null);
            item.setLastManualTouchAt(null);
            dailyControlConcreteItemRepository.save(item);
        }
    }

    private boolean applyConcreteItemSnapshot(
            ManagerDailyControlConcreteItem item,
            ManagerControlConcreteItemResponse example
    ) {
        boolean changed = false;
        LocalDateTime firstObservedAt = example.firstObservedAt();
        if (shouldAlignFirstObservedAt(item.getCreatedAt(), firstObservedAt)) {
            item.setCreatedAt(firstObservedAt);
            changed = true;
        }
        String entityType = limit(safe(example.type()).isBlank() ? "UNKNOWN" : example.type(), 40);
        String title = limit(safe(example.title()).isBlank() ? "Карточка контроля" : example.title(), 220);
        String subtitle = limit(example.subtitle(), 500);
        String statusLabel = limit(example.status(), 120);
        String reason = limit(example.reason(), 500);
        String targetUrl = limit(example.targetUrl(), 500);
        String orderDetailsId = limit(example.orderDetailsId(), 36);
        String chatUrl = limit(example.chatUrl(), 500);
        if (!Objects.equals(item.getEntityType(), entityType)) {
            item.setEntityType(entityType);
            changed = true;
        }
        if (!Objects.equals(item.getEntityId(), example.entityId())) {
            item.setEntityId(example.entityId());
            changed = true;
        }
        if (!Objects.equals(item.getTitle(), title)) {
            item.setTitle(title);
            changed = true;
        }
        if (!Objects.equals(item.getSubtitle(), subtitle)) {
            item.setSubtitle(subtitle);
            changed = true;
        }
        if (!Objects.equals(item.getStatusLabel(), statusLabel)) {
            item.setStatusLabel(statusLabel);
            changed = true;
        }
        if (!Objects.equals(item.getAgeDays(), example.ageDays())) {
            item.setAgeDays(example.ageDays());
            changed = true;
        }
        if (!Objects.equals(item.getReason(), reason)) {
            item.setReason(reason);
            changed = true;
        }
        if (!Objects.equals(item.getTargetUrl(), targetUrl)) {
            item.setTargetUrl(targetUrl);
            changed = true;
        }
        if (!Objects.equals(item.getOrderDetailsId(), orderDetailsId)) {
            item.setOrderDetailsId(orderDetailsId);
            changed = true;
        }
        if (!Objects.equals(item.getChatUrl(), chatUrl)) {
            item.setChatUrl(chatUrl);
            changed = true;
        }
        if ("RISK".equals(entityType)) {
            String workerExplanation = limit(example.workerExplanation(), 1000);
            if (!Objects.equals(item.getWorkerExplanation(), workerExplanation)) {
                item.setWorkerExplanation(workerExplanation);
                changed = true;
            }
            if (!Objects.equals(item.getWorkerExplanationAt(), example.workerExplanationAt())) {
                item.setWorkerExplanationAt(example.workerExplanationAt());
                changed = true;
            }
        }
        return changed;
    }

    private boolean shouldAlignFirstObservedAt(LocalDateTime storedAt, LocalDateTime sourceAt) {
        if (sourceAt == null) {
            return false;
        }
        return storedAt == null || Math.abs(Duration.between(storedAt, sourceAt).getSeconds()) > 60;
    }

    private boolean reopenQueuedChatBindingRepair(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null
                || concreteItem.getStatus() == ManagerDailyControlItemStatus.OPEN
                || concreteItem.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        ManagerDailyControlItem parent = concreteItem.getParentItem();
        if (parent == null || !"CHAT_BINDING_ISSUES".equals(parent.getReasonCode())) {
            return false;
        }
        String comment = safe(concreteItem.getComment()).toLowerCase(Locale.ROOT);
        if (!comment.contains("фоновая синхронизация whatsapp-групп")
                && !comment.contains("повторная проверка через")) {
            return false;
        }
        reopenConcreteItem(concreteItem);
        return true;
    }

    private boolean isResolvedConcreteItemHiddenForToday(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getStatus() != ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        return true;
    }

    private boolean reopenResolvedConcreteItemIfExpired(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getStatus() != ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        LocalDateTime resolvedAt = concreteItem.getResolvedAt();
        if (resolvedAt == null || !resolvedAt.toLocalDate().isBefore(LocalDate.now())) {
            return false;
        }
        reopenConcreteItem(concreteItem);
        return true;
    }

    private boolean reopenActiveAutomationConcreteItem(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null
                || concreteItem.getStatus() != ManagerDailyControlItemStatus.RESOLVED
                || concreteItem.getParentItem() == null
                || !"AUTOMATION_FAILURES".equals(concreteItem.getParentItem().getReasonCode())) {
            return false;
        }
        reopenConcreteItem(concreteItem);
        return true;
    }

    private boolean reopenResolvedConcreteItemStillActive(
            ManagerDailyControlItem parentItem,
            ManagerDailyControlConcreteItem concreteItem
    ) {
        if (parentItem == null
                || concreteItem == null
                || parentItem.getStatus() != ManagerDailyControlItemStatus.OPEN
                || parentItem.getGroup() != ManagerDailyControlGroup.ACTION
                || concreteItem.getStatus() != ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        reopenConcreteItem(concreteItem);
        return true;
    }

    private void reopenConcreteItem(ManagerDailyControlConcreteItem concreteItem) {
        concreteItem.setStatus(ManagerDailyControlItemStatus.OPEN);
        concreteItem.setActionType(null);
        concreteItem.setComment(null);
        concreteItem.setResolvedAt(null);
        concreteItem.setAutomaticResolution(false);
        concreteItem.setFollowUpAt(null);
        concreteItem.setLastManualTouchAt(null);
        clearWorkerTelegramState(concreteItem);
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
        if (isWorkerRiskConcrete(item)) {
            return riskConcreteItemResponse(item, contactText, specialistNameOverride);
        }
        String specialistName = safe(specialistNameOverride).isBlank()
                ? specialistNameForConcreteItem(item)
                : specialistNameOverride;
        String targetUrl = item.getTargetUrl();
        if (isChatBindingIssueConcrete(item)) {
            targetUrl = companyBoardUrlByKeyword(item.getTitle(), targetUrl);
        }
        ManagerControlConcreteItemResponse response = new ManagerControlConcreteItemResponse(
                item.getId(),
                item.getEntityType(),
                item.getEntityId(),
                item.getTitle(),
                item.getSubtitle(),
                item.getStatusLabel(),
                item.getAgeDays(),
                item.getReason(),
                targetUrl,
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
                null,
                item.getWorkerExplanation(),
                item.getWorkerExplanationAt(),
                null,
                null,
                null,
                null,
                specialistName,
                null,
                null,
                null,
                null
        );
        return decorateConcreteSla(item.getParentItem(), response.withSla(item.getCreatedAt(), null, null, null));
    }

    private String companyBoardUrlByKeyword(String keyword, String fallbackUrl) {
        String normalizedKeyword = safe(keyword);
        if (normalizedKeyword.isBlank() || normalizedKeyword.matches("\\d+")) {
            return fallbackUrl;
        }
        List<String> params = new ArrayList<>();
        params.add("section=companies");
        params.add("status=" + encode("Все"));
        params.add("pageNumber=0");
        params.add("pageSize=10");
        params.add("sortDirection=desc");
        params.add("keyword=" + encode(normalizedKeyword));
        return "/companies?" + String.join("&", params);
    }

    private ManagerControlConcreteItemResponse riskConcreteItemResponse(
            ManagerDailyControlConcreteItem item,
            String contactText,
            String specialistNameOverride
    ) {
        WorkerRiskIncident incident = item.getEntityId() == null
                ? null
                : riskIncidentRepository.findById(item.getEntityId()).orElse(null);
        String specialistName = safe(specialistNameOverride).isBlank()
                ? firstNonBlank(
                incident == null ? null : incident.getWorkerName(),
                incident == null ? null : incident.getWorkerUsername(),
                specialistNameForConcreteItem(item)
        )
                : specialistNameOverride;
        String riskResolutionAction = incident == null || incident.getResolutionAction() == null
                ? null
                : incident.getResolutionAction().name();
        String workerExplanation = firstNonBlank(
                incident == null ? null : incident.getWorkerExplanation(),
                item.getWorkerExplanation()
        );
        LocalDateTime workerExplanationAt = incident == null || incident.getWorkerExplanationAt() == null
                ? item.getWorkerExplanationAt()
                : incident.getWorkerExplanationAt();
        boolean riskExplanationRequested = incident != null
                && (incident.getResolutionAction() == WorkerRiskResolutionAction.EXPLANATION_REQUESTED
                || incident.getExplanationRequestedAt() != null
                || incident.getExplanationPromptedAt() != null
                || incident.getWorkerExplanationAt() != null);
        LocalDateTime workerNotificationAttemptedAt = firstNonNullTime(
                item.getWorkerNotificationAttemptedAt(),
                riskExplanationRequested ? incident.getExplanationRequestedAt() : null,
                riskExplanationRequested ? incident.getCreatedAt() : null
        );
        LocalDateTime workerNotificationSentAt = firstNonNullTime(
                item.getWorkerNotificationSentAt(),
                riskExplanationRequested ? incident.getExplanationRequestedAt() : null,
                riskExplanationRequested ? incident.getCreatedAt() : null
        );
        LocalDateTime workerNotificationAcceptedAt = firstNonNullTime(
                item.getWorkerNotificationAcceptedAt(),
                incident == null ? null : incident.getExplanationAcceptedAt()
        );
        Long workerNotificationAcceptedByUserId = firstNonNullLong(
                item.getWorkerNotificationAcceptedByUserId(),
                workerNotificationAcceptedAt == null || incident == null ? null : incident.getWorkerUserId()
        );
        String workerNotificationFailureReason = workerNotificationSentAt != null
                || workerNotificationAcceptedAt != null
                || workerExplanationAt != null
                ? null
                : item.getWorkerNotificationFailureReason();
        ManagerControlConcreteItemResponse response = new ManagerControlConcreteItemResponse(
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
                workerNotificationAttemptedAt,
                workerNotificationSentAt,
                workerNotificationAcceptedAt,
                workerNotificationAcceptedByUserId,
                workerNotificationFailureReason,
                contactText,
                riskResolutionAction,
                workerExplanation,
                workerExplanationAt,
                incident == null ? null : incident.getPenaltyPoints(),
                incident == null || incident.getRollbackStatus() == null ? null : incident.getRollbackStatus().name(),
                incident == null ? null : incident.getRollbackMessage(),
                incident == null ? null : canRollbackRiskIncident(incident),
                specialistName,
                null,
                null,
                null,
                null
        );
        return decorateConcreteSla(item.getParentItem(), response.withSla(item.getCreatedAt(), null, null, null));
    }

    private ManagerControlConcreteItemResponse decorateConcreteSla(
            ManagerDailyControlItem parentItem,
            ManagerControlConcreteItemResponse response
    ) {
        if (response == null) {
            return null;
        }
        LocalDateTime firstObservedAt = firstNonNullTime(
                response.firstObservedAt(),
                parentItem == null ? null : parentItem.getCreatedAt(),
                LocalDateTime.now()
        );
        String code = firstNonBlank(
                parentItem == null ? null : parentItem.getReasonCode(),
                parentItem == null ? null : parentItem.getSectionCode(),
                response.type()
        );
        SlaWindow sla = slaWindow(code, firstObservedAt, response.resolvedAt());
        return response.withSla(
                sla.firstObservedAt(),
                sla.targetDeadlineAt(),
                sla.hardDeadlineAt(),
                sla.state()
        );
    }

    private boolean isConcreteSnoozed(ManagerDailyControlConcreteItem item) {
        return item != null
                && !ENTITY_CLIENT_CHAT_UNANSWERED.equals(item.getEntityType())
                && item.getFollowUpAt() != null
                && item.getFollowUpAt().isAfter(LocalDateTime.now())
                && item.getStatus() != ManagerDailyControlItemStatus.OPEN;
    }

    private boolean reopenConcreteItemIfFollowUpDue(ManagerDailyControlConcreteItem item) {
        if (item == null
                || item.getFollowUpAt() == null
                || item.getFollowUpAt().isAfter(LocalDateTime.now())
                || item.getStatus() == ManagerDailyControlItemStatus.OPEN
                || item.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        reopenConcreteItem(item);
        return true;
    }

    private void recordItemEpisode(
            ManagerDailyControlItem item,
            ManagerDailyControlItemStatus outcome,
            boolean automatic
    ) {
        if (item == null || item.getStatus() != ManagerDailyControlItemStatus.OPEN || outcome == null) {
            return;
        }
        long count = Math.max(1, item.getCount());
        if (automatic) {
            item.setAutoClosedEpisodeCount(item.getAutoClosedEpisodeCount() + count);
            return;
        }
        switch (outcome) {
            case RESOLVED -> item.setResolvedEpisodeCount(item.getResolvedEpisodeCount() + count);
            case ACTION_TAKEN -> item.setActionTakenEpisodeCount(item.getActionTakenEpisodeCount() + count);
            case DEFERRED -> item.setDeferredEpisodeCount(item.getDeferredEpisodeCount() + count);
            case ACKNOWLEDGED -> item.setAcknowledgedEpisodeCount(item.getAcknowledgedEpisodeCount() + count);
            case OPEN -> { }
        }
    }

    private void recordConcreteEpisode(
            ManagerDailyControlConcreteItem item,
            ManagerDailyControlItemStatus outcome,
            boolean automatic
    ) {
        if (item == null || item.getStatus() != ManagerDailyControlItemStatus.OPEN || outcome == null) {
            return;
        }
        if (automatic) {
            item.setAutoClosedEpisodeCount(item.getAutoClosedEpisodeCount() + 1);
            return;
        }
        switch (outcome) {
            case RESOLVED -> item.setResolvedEpisodeCount(item.getResolvedEpisodeCount() + 1);
            case ACTION_TAKEN -> item.setActionTakenEpisodeCount(item.getActionTakenEpisodeCount() + 1);
            case DEFERRED -> item.setDeferredEpisodeCount(item.getDeferredEpisodeCount() + 1);
            case ACKNOWLEDGED -> item.setAcknowledgedEpisodeCount(item.getAcknowledgedEpisodeCount() + 1);
            case OPEN -> { }
        }
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
            reopenParentItemIfConcreteOpen(parentItem);
            return;
        }
        boolean allResolved = concreteItems.stream()
                .allMatch(item -> item.getStatus() == ManagerDailyControlItemStatus.RESOLVED);
        if (allResolved) {
            parentItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
            parentItem.setActionType(ManagerDailyControlActionType.RESOLVED);
            parentItem.setComment("Все конкретные карточки внутри пункта закрыты");
            parentItem.setResolvedAt(LocalDateTime.now());
            parentItem.setAutomaticResolution(false);
        } else {
            parentItem.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
            parentItem.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
            parentItem.setComment("Все конкретные карточки внутри пункта обработаны");
            parentItem.setResolvedAt(null);
            parentItem.setAutomaticResolution(false);
        }
        dailyControlItemRepository.save(parentItem);
    }

    private void reopenParentItemIfConcreteOpen(ManagerDailyControlItem parentItem) {
        if (parentItem == null
                || parentItem.getStatus() == ManagerDailyControlItemStatus.OPEN
                || parentItem.getGroup() != ManagerDailyControlGroup.ACTION) {
            return;
        }
        boolean hasOpenConcrete = dailyControlConcreteItemRepository.findByParentItem(parentItem).stream()
                .anyMatch(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN);
        if (!hasOpenConcrete) {
            return;
        }
        parentItem.setStatus(ManagerDailyControlItemStatus.OPEN);
        parentItem.setActionType(null);
        parentItem.setComment(null);
        parentItem.setResolvedAt(null);
        parentItem.setAutomaticResolution(false);
        dailyControlItemRepository.save(parentItem);
    }

    private List<ManagerControlConcreteItemResponse> overdueOrderExamples(Manager manager, String status, LocalDate today, int limit) {
        Set<Long> snoozedOrderIds = snoozedOrderIds(manager, today);
        List<String> statuses = safe(status).isBlank() || "Все".equalsIgnoreCase(status)
                ? overdueStatuses(manager, today).stream()
                        .map(ManagerControlOverdueStatusResponse::status)
                        .toList()
                : List.of(status);
        Map<Long, OrderDTOList> uniqueOrders = new LinkedHashMap<>();
        for (String currentStatus : statuses) {
            if (uniqueOrders.size() >= limit) {
                break;
            }
            int remaining = Math.max(1, limit - uniqueOrders.size());
            LocalDate cutoff = managerControlOrderCutoff(currentStatus, today);
            orderService.getManagerControlOverdueOrdersByManager(
                            manager,
                            "",
                            currentStatus,
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
                            remaining,
                            "desc"
                    ).getContent().stream()
                    .filter(order -> order.getId() != null)
                    .forEach(order -> uniqueOrders.putIfAbsent(order.getId(), order));
        }
        List<OrderDTOList> orders = new ArrayList<>(uniqueOrders.values());
        clientMessageOrderStatusService.enrichOrderList(orders);
        return orders.stream()
                .filter(order -> order.getId() == null || !snoozedOrderIds.contains(order.getId()))
                .filter(order -> !hasHealthyActiveClientMessageQueue(order))
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
                .filter(order -> !hasHealthyActiveClientMessageQueue(order))
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
        ).withSla(orderControlStartedAt(order), null, null, null);
    }

    private List<ManagerControlConcreteItemResponse> paymentIntegrityIssueExamples(
            Manager manager,
            LocalDate today,
            int limit
    ) {
        return orderRepository.findPaymentIntegrityIssuesByManager(
                        manager,
                        PAYMENT_AUTOMATION_STATUSES,
                        PageRequest.of(0, Math.max(1, limit))
                )
                .stream()
                .map(order -> {
                    String status = order.getStatus() == null ? "" : safe(order.getStatus().getTitle());
                    String paidAt = order.getPayDay() == null ? "ранее" : order.getPayDay().toString();
                    String reason = "Проблема: заказ №" + order.getId() + " полностью оплачен " + paidAt
                            + ", но повторно находится в статусе «" + status + "». "
                            + "Есть риск повторного счета клиенту. Решение: нажмите «Починить» — "
                            + "система остановит платежные очереди, закроет только лишние неоплаченные ссылки "
                            + "и восстановит статус «Оплачено». Следующий заказ не изменяется.";
                    return new ManagerControlConcreteItemResponse(
                            null,
                            ENTITY_ORDER_PAYMENT_INTEGRITY,
                            order.getId(),
                            orderTitle(order, "Заказ #" + order.getId()),
                            "Оплачен " + paidAt + " · заказ №" + order.getId(),
                            status,
                            order.getPayDay() == null ? null : daysSince(order.getPayDay(), today),
                            reason,
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
                    ).withSla(order.getStatusChangedAt(), null, null, null);
                })
                .toList();
    }

    private LocalDateTime orderControlStartedAt(OrderDTOList order) {
        if (order == null) {
            return null;
        }
        LocalDateTime statusChangedAt = order.getStatusChangedAt();
        if (statusChangedAt == null && order.getChanged() != null) {
            statusChangedAt = order.getChanged().atStartOfDay();
        }
        return statusChangedAt == null
                ? null
                : statusChangedAt.plusDays(managerControlOrderThresholdDays(order.getStatus()));
    }

    private String orderManagerReason(OrderDTOList order, LocalDate today) {
        String status = safe(order == null ? null : order.getStatus());
        long days = order == null || order.getChanged() == null ? 0 : daysSince(order.getChanged(), today);
        String age = days > 0 ? days + " дн" : "сегодня";
        String controlReason = orderControlReason(order);
        return switch (status) {
            case "На проверке" -> "Клиент не проверил шаблоны " + age
                    + ". " + controlReason + " Если доступна кнопка «Починить», сначала нажмите ее: система попробует восстановить автоответчик. "
                    + "Если починка недоступна или не помогла, скопируйте текст, откройте чат, отправьте ссылку на проверку и нажмите «Отправлено».";
            case "Опубликовано" -> "Заказ опубликован " + age
                    + ", нужна ручная проверка оплаты/счета. " + controlReason + " Отправьте клиенту сообщение или закройте причину.";
            case "Ожидает общего счета" -> "Заказ ожидает общего счета " + age
                    + ". " + controlReason + " Проверьте, что заказ попал в общий счет или почему счет не сформирован.";
            case "Выставлен счет" -> "Оплаты нет. " + controlReason
                    + " Отправьте напоминание клиенту; после отправки заказ уйдет в «Напоминание».";
            case "Напоминание" -> "Клиент не оплатил после напоминания. " + controlReason
                    + " Повторите напоминание или укажите, почему откладываем.";
            case "Требует внимания" -> "Заказ требует внимания менеджера " + age
                    + ". " + controlReason + " Откройте заказ, устраните причину и зафиксируйте действие.";
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
            var clientMessageStatus = order.getClientMessageStatus();
            String label = safe(clientMessageStatus.label());
            String errorCode = safe(clientMessageStatus.errorCode()).toLowerCase(Locale.ROOT);
            String error = safe(clientMessageStatus.errorMessage());
            if ("rate_limited".equals(errorCode) && hasHealthyActiveClientMessageQueue(order)) {
                String nextAttempt = clientMessageStatus.nextAttemptAt().toString();
                return "Очередь автоответчика исправна. Следующий слот отправки: "
                        + nextAttempt + ". Ручное действие до этого времени не требуется.";
            }
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

    private boolean hasHealthyActiveClientMessageQueue(OrderDTOList order) {
        if (order == null || order.getClientMessageStatus() == null) {
            return false;
        }
        var status = order.getClientMessageStatus();
        if (!"scheduled".equalsIgnoreCase(safe(status.state()))
                || status.nextAttemptAt() == null
                || status.consecutiveFailures() > 0) {
            return false;
        }
        String errorCode = safe(status.errorCode()).toLowerCase(Locale.ROOT);
        return errorCode.isBlank() || "rate_limited".equals(errorCode);
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

    private List<ManagerControlConcreteItemResponse> chatBindingIssueExamples(Manager manager, LocalDate today, int limit) {
        return companyRepository.findChatBindingIssuesByManager(manager).stream()
                .limit(Math.max(1, limit))
                .map(company -> companyChatBindingIssueExample(company, today, manager))
                .toList();
    }

    private ManagerControlConcreteItemResponse companyChatBindingIssueExample(
            Company company,
            LocalDate today,
            Manager manager
    ) {
        String status = company == null || company.getStatus() == null ? "" : safe(company.getStatus().getTitle());
        String specialistName = companySpecialistName(company);
        return new ManagerControlConcreteItemResponse(
                null,
                "COMPANY_CHAT_BINDING",
                company.getId(),
                safe(company.getTitle()).isBlank() ? "Компания #" + company.getId() : company.getTitle(),
                specialistName.isBlank() ? "Специалист не назначен" : specialistName,
                status,
                company.getUpdateStatus() == null ? null : daysSince(company.getUpdateStatus(), today),
                "Почему в контроле: " + companyChatBindingReason(company)
                        + ". Сохраните правильную ссылку на чат и нажмите «Починить».",
                companyTargetUrl(manager, company),
                null,
                company.getUrlChat(),
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
                null,
                null,
                specialistName
        );
    }

    private String companyChatBindingReason(Company company) {
        String chat = safe(company == null ? null : company.getUrlChat()).toLowerCase(Locale.ROOT);
        if (isWhatsAppChat(chat)) return "WhatsApp-группа не привязана к компании";
        if (isTelegramChat(chat)) return "Telegram-группа не привязана к компании";
        if (isMaxChat(chat)) return "MAX-группа не привязана к компании";
        return "ссылка на чат не распознана";
    }

    private String companySpecialistName(Company company) {
        if (company == null || company.getWorkers() == null) return "";
        List<String> names = company.getWorkers().stream()
                .map(Worker::getUser)
                .map(this::userDisplayName)
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
        if (names.size() == 1) return names.getFirst();
        if (names.size() > 1) return String.join(", ", names);
        return "";
    }

    private OrderDTOList orderDtoFromOrder(Order order) {
        Company company = order == null ? null : order.getCompany();
        var filial = order == null ? null : order.getFilial();
        var city = filial == null ? null : filial.getCity();
        var status = order == null ? null : order.getStatus();
        return OrderDTOList.builder()
                .id(order == null ? null : order.getId())
                .companyId(company == null ? null : company.getId())
                .companyTitle(company == null ? null : company.getTitle())
                .companyStatus(company == null || company.getStatus() == null ? null : company.getStatus().getTitle())
                .filialTitle(filial == null ? null : filial.getTitle())
                .filialUrl(filial == null ? null : filial.getUrl())
                .filialCity(city == null ? null : city.getTitle())
                .status(status == null ? null : status.getTitle())
                .sum(order == null ? null : order.getSum())
                .companyUrlChat(company == null ? null : company.getUrlChat())
                .companyTelephone(company == null ? null : company.getTelephone())
                .companyComments(company == null ? null : company.getCommentsCompany())
                .amount(order == null ? null : order.getAmount())
                .counter(order == null ? null : order.getCounter())
                .waitingForClient(order != null && order.isWaitingForClient())
                .created(order == null ? null : order.getCreated())
                .changed(order == null ? null : order.getChanged())
                .statusChangedAt(order == null ? null : order.getStatusChangedAt())
                .payDay(order == null ? null : order.getPayDay())
                .orderComments(order == null ? null : order.getZametka())
                .groupId(company == null ? null : company.getGroupId())
                .telegramGroupChatId(company == null ? null : company.getTelegramGroupChatId())
                .telegramBotInviteUrl(company == null ? null : telegramGroupLinkService.buildInviteUrl(company))
                .maxGroupChatId(company == null ? null : company.getMaxGroupChatId())
                .maxBotInviteUrl(company == null ? null : maxGroupLinkService.buildInviteUrl(company))
                .build();
    }

    private ManagerControlConcreteItemResponse chatBindingIssueExample(OrderDTOList order, LocalDate today, Manager manager) {
        LocalDate changed = order.getChanged();
        return new ManagerControlConcreteItemResponse(
                null,
                "ORDER",
                order.getId(),
                safe(order.getCompanyTitle()).isBlank() ? "Заказ #" + order.getId() : order.getCompanyTitle(),
                orderSubtitle(order),
                safe(order.getStatus()),
                changed == null ? null : daysSince(changed, today),
                chatBindingIssueReason(order),
                companyBoardUrl(order),
                order.getOrderDetailsId() == null ? null : order.getOrderDetailsId().toString(),
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

    private String companyBoardUrl(OrderDTOList order) {
        Long companyId = order == null ? null : order.getCompanyId();
        String keyword = safe(order == null ? null : order.getCompanyTitle());
        if (keyword.isBlank() && companyId != null) {
            keyword = String.valueOf(companyId);
        }
        List<String> params = new ArrayList<>();
        params.add("section=companies");
        params.add("status=" + encode("Все"));
        params.add("pageNumber=0");
        params.add("pageSize=10");
        params.add("sortDirection=desc");
        if (!keyword.isBlank()) {
            params.add("keyword=" + encode(keyword));
        }
        return "/companies?" + String.join("&", params);
    }

    private String chatBindingIssueReason(OrderDTOList order) {
        String reason = chatBindingControlReason(order);
        if (reason.isBlank()) {
            reason = "чат компании не готов к отправке сообщений";
        }
        return "Почему в контроле: " + reason
                + ". Автоответчик не сможет отправить сообщение клиенту. "
                + chatBindingRepairInstructionLead(order)
                + " "
                + chatBindingManualInstruction(order);
    }

    private String chatBindingRepairInstructionLead(OrderDTOList order) {
        String chat = safe(order == null ? null : order.getCompanyUrlChat()).toLowerCase(Locale.ROOT);
        if (isWhatsAppChat(chat)) {
            return "Нажмите «Починить»: система проверит уже известные WhatsApp-привязки по этой ссылке.";
        }
        if (isTelegramChat(chat)) {
            return "Нажмите «Починить»: система перепроверит, не появилась ли Telegram-привязка, и закроет карточку, если бот уже добавлен.";
        }
        if (isMaxChat(chat)) {
            return "Нажмите «Починить»: система перепроверит, не появилась ли MAX-привязка, и закроет карточку, если бот уже добавлен.";
        }
        return "Нажмите «Починить»: система перепроверит привязку чата.";
    }

    private boolean isChatBindingIssueConcrete(ManagerDailyControlConcreteItem item) {
        if (item == null) {
            return false;
        }
        ManagerDailyControlItem parent = item.getParentItem();
        if (parent != null && "CHAT_BINDING_ISSUES".equals(parent.getReasonCode())) {
            return true;
        }
        String reason = safe(item.getReason()).toLowerCase(Locale.ROOT);
        return reason.contains("группа из ссылки") && reason.contains("не привязан");
    }

    private String chatBindingManualInstruction(OrderDTOList order) {
        String chat = safe(order == null ? null : order.getCompanyUrlChat()).toLowerCase(Locale.ROOT);
        if (isWhatsAppChat(chat)) {
            return "Как перепривязать: 1) откройте карточку компании и проверьте, что WhatsApp-ссылка ведет в нужную группу; "
                    + "2) если ссылка устарела, замените ее на актуальную; "
                    + "3) убедитесь, что хотя бы один подключенный WhatsApp-аккаунт состоит в группе; "
                    + "4) отправьте любое сообщение в группу; 5) вернитесь в замечание и нажмите «Починить».";
        }
        if (isTelegramChat(chat)) {
            String invite = safe(order == null ? null : order.getTelegramBotInviteUrl());
            return "Если починка не помогла: откройте ссылку добавления Telegram-бота"
                    + (invite.isBlank() ? "" : " " + invite)
                    + ", выберите нужную группу и добавьте бота администратором. После добавления нажмите «Починить» еще раз.";
        }
        if (isMaxChat(chat)) {
            String invite = safe(order == null ? null : order.getMaxBotInviteUrl());
            return "Если починка не помогла: откройте ссылку привязки MAX"
                    + (invite.isBlank() ? "" : " " + invite)
                    + ", запустите бота, затем добавьте его администратором в нужную группу. После добавления нажмите «Починить» еще раз.";
        }
        return "Если починка не помогла: проверьте ссылку на чат компании, привяжите нужную группу к боту или временно отправьте сообщение клиенту вручную.";
    }

    private String manualChatBindingRepairInstruction(Company company) {
        String problem = clientTextChatBindingProblem(company);
        String chat = safe(company == null ? null : company.getUrlChat()).toLowerCase(Locale.ROOT);
        if (isWhatsAppChat(chat)) {
            return "WhatsApp-починка не нашла группу автоматически: " + problem
                    + ". Проверьте, что подключенный WhatsApp-аккаунт состоит в группе из ссылки компании, ссылка не устарела, и отправьте любое сообщение в группу. Если ссылка не открывает нужную группу, замените ее в компании вручную.";
        }
        if (isTelegramChat(chat)) {
            String invite = safe(telegramGroupLinkService.buildInviteUrl(company));
            return "Telegram-группа пока не привязана: " + problem
                    + ". Откройте ссылку добавления бота"
                    + (invite.isBlank() ? "" : ": " + invite)
                    + ". В Telegram выберите нужную группу и добавьте бота администратором. Если бот уже добавлен, скопируйте из уведомления команду привязки и отправьте ее в этой группе. Если ссылка компании ведет не в эту группу или не открывается, замените ссылку вручную. После привязки система сама перепроверит и повторит задачу.";
        }
        if (isMaxChat(chat)) {
            String invite = safe(maxGroupLinkService.buildInviteUrl(company));
            return "MAX-группа пока не привязана: " + problem
                    + ". Откройте ссылку привязки"
                    + (invite.isBlank() ? "" : ": " + invite)
                    + ". Запустите бота, затем добавьте его администратором в нужную группу. Если ссылка компании ведет не в эту группу или не открывается, замените ссылку вручную. После успешного добавления бота нажмите «Починить» еще раз.";
        }
        return "Чат компании не удалось привязать автоматически: " + problem
                + ". Проверьте ссылку на группу в компании и привяжите ее к боту вручную.";
    }

    private boolean isWhatsAppChat(String chat) {
        return chat.startsWith("chat.whatsapp.com/")
                || chat.startsWith("https://chat.whatsapp.com/")
                || chat.startsWith("http://chat.whatsapp.com/");
    }

    private boolean isTelegramChat(String chat) {
        if (chat.contains("startgroup=")) {
            return false;
        }
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

    private LocalDateTime nextDayFollowUpAt(LocalDateTime now) {
        LocalDateTime base = now == null ? LocalDateTime.now() : now;
        return base.plusDays(1);
    }

    private String manualWorkerNotificationComment(ManagerDailyControlConcreteItem concreteItem) {
        if (!requiresWorkerExplanation(concreteItem)) {
            return "Специалисту отправлено напоминание: " + specialistProblemLabel(concreteItem) + ". Повторный контроль завтра.";
        }
        return "Специалисту отправлен запрос на пояснение: " + specialistProblemLabel(concreteItem) + ". Повторный контроль через "
                + WORKER_TASK_FOLLOW_UP_HOURS + " ч.";
    }

    private boolean requiresWorkerExplanation(ManagerDailyControlConcreteItem concreteItem) {
        if (isWorkerRiskConcrete(concreteItem)) {
            return true;
        }
        Long ageDays = concreteItem == null ? null : concreteItem.getAgeDays();
        return ageDays == null || ageDays >= 2;
    }

    private boolean canOverrideWorkerExplanation(Authentication authentication) {
        return managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER");
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
        concreteItem.setWorkerNotificationUserId(null);
        concreteItem.setWorkerNotificationSentAt(null);
        concreteItem.setWorkerNotificationAcceptedAt(null);
        concreteItem.setWorkerNotificationAcceptedByUserId(null);
        concreteItem.setWorkerNotificationFailureReason(null);
        concreteItem.setWorkerExplanationRequestedAt(null);
        concreteItem.setWorkerExplanationPromptedAt(null);
        concreteItem.setWorkerExplanation(null);
        concreteItem.setWorkerExplanationAt(null);
        concreteItem.setWorkerExplanationByUserId(null);
        concreteItem.setWorkerReminderSentAt(null);
        concreteItem.setWorkerReminderCount(0);
    }

    private boolean notifyWorkerAboutTaskRequest(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control) {
        User workerUser = workerUserForTask(concreteItem);
        if (workerUser == null || workerUser.getId() == null || concreteItem.getId() == null) {
            concreteItem.setWorkerNotificationFailureReason("Специалист карточки не найден");
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        concreteItem.setWorkerNotificationAttemptedAt(now);
        concreteItem.setWorkerNotificationUserId(workerUser.getId());
        concreteItem.setWorkerNotificationSentAt(null);
        concreteItem.setWorkerNotificationAcceptedAt(null);
        concreteItem.setWorkerNotificationAcceptedByUserId(null);
        concreteItem.setWorkerNotificationFailureReason(null);
        concreteItem.setWorkerExplanationRequestedAt(null);
        concreteItem.setWorkerExplanationPromptedAt(null);
        concreteItem.setWorkerExplanation(null);
        concreteItem.setWorkerExplanationAt(null);
        concreteItem.setWorkerExplanationByUserId(null);
        concreteItem.setWorkerReminderSentAt(null);
        concreteItem.setWorkerReminderCount(0);
        boolean explanationRequired = requiresWorkerExplanation(concreteItem);
        if (explanationRequired) {
            concreteItem.setWorkerExplanationRequestedAt(now);
        }
        String title = workerTaskRequestTitle(concreteItem);
        String text = workerTaskTelegramText(concreteItem, explanationRequired);
        if (explanationRequired && !personalReminderService.hasOpenSystemReminder(workerUser, SOURCE_WORKER_TASK_REQUEST, concreteItem.getId())) {
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
        boolean sent = notificationMediaDeliveryService.send(
                NotificationMediaEventCatalog.WORKER_TASK_FIRST.code(),
                workerUser.getWorkerTelegramGroupChatId(),
                workerUser.getId(),
                text,
                null,
                explanationRequired
                        ? List.of(List.of(workerTaskTelegramButton(concreteItem)))
                        : List.of()
        );
        if (sent) {
            concreteItem.setWorkerNotificationSentAt(now);
            if (isWorkerRiskConcrete(concreteItem)) {
                markRiskExplanationRequested(concreteItem, now);
            }
            return true;
        } else {
            concreteItem.setWorkerNotificationFailureReason("Telegram не отправил сообщение");
            return false;
        }
    }

    private String workerTaskTelegramText(ManagerDailyControlConcreteItem concreteItem, boolean explanationRequired) {
        Long orderId = orderIdForTask(concreteItem);
        String company = workerTaskCompanyTitle(concreteItem);
        List<String> lines = new ArrayList<>();
        lines.add(explanationRequired
                ? "🟡 ОЖИДАЕМ ОТВЕТ"
                : "🔔 НАПОМИНАНИЕ");
        lines.add(explanationRequired
                ? "Нужно пояснение: " + specialistProblemLabel(concreteItem) + "."
                : "Напоминание: " + specialistProblemLabel(concreteItem) + ".");
        lines.add("Причина: " + workerFriendlyReason(concreteItem));
        if (orderId != null) {
            lines.add("Заказ: #" + orderId);
        }
        if (!company.isBlank()) {
            lines.add("Фирма: " + company);
        }
        lines.add(explanationRequired
                ? "Что сделать: нажмите кнопку и отправьте короткое пояснение следующим сообщением."
                : "Что сделать: проверьте задачу. Пояснение не требуется.");
        return lines.stream()
                .filter(value -> !safe(value).isBlank())
                .collect(Collectors.joining("\n"));
    }

    private String workerFriendlyReason(ManagerDailyControlConcreteItem concreteItem) {
        String reason = safe(concreteItem == null ? null : concreteItem.getReason());
        if (reason.isBlank()) {
            reason = safe(concreteItem == null ? null : concreteItem.getStatusLabel());
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("client_text_reminder")) {
            return "Заказ ждет текст клиента, автонапоминание не ушло.";
        }
        reason = reason.replaceFirst("(?iu)^почему\\s+в\\s+контроле:\\s*", "");
        reason = reason.replaceAll("(?iu)\\bCLIENT_[A-Z0-9_]+\\b", "автонапоминание");
        reason = reason.replaceAll("\\s+", " ").trim();
        return reason.isBlank() ? "задача требует проверки" : compact(reason, 240);
    }

    private String workerTaskCompanyTitle(ManagerDailyControlConcreteItem concreteItem) {
        Order order = orderForTask(concreteItem);
        String company = safe(order == null || order.getCompany() == null ? null : order.getCompany().getTitle());
        if (!company.isBlank()) {
            return company;
        }
        company = safe(concreteItem == null ? null : concreteItem.getTitle());
        return company.startsWith("Заказ #") ? "" : compact(company, 120);
    }

    private Order orderForTask(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getEntityId() == null) {
            return null;
        }
        try {
            return switch (safe(concreteItem.getEntityType())) {
                case "BAD_REVIEW_TASK" -> {
                    BadReviewTask task = badReviewTaskService.getTask(concreteItem.getEntityId());
                    yield task == null ? null : task.getOrder();
                }
                case "RECOVERY_TASK" -> {
                    ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(concreteItem.getEntityId());
                    yield task == null ? null : task.getOrder();
                }
                case ENTITY_PUBLISH_REVIEW, ENTITY_NAGUL_REVIEW -> {
                    Review review = reviewRepository.findById(concreteItem.getEntityId()).orElse(null);
                    yield reviewOrder(review);
                }
                case ENTITY_WORKER_ORDER_NEW, ENTITY_WORKER_ORDER_CORRECT -> orderRepository.findById(concreteItem.getEntityId()).orElse(null);
                case "RISK" -> {
                    WorkerRiskIncident incident = riskIncidentRepository.findById(concreteItem.getEntityId()).orElse(null);
                    yield incident == null || incident.getOrderId() == null
                            ? null
                            : orderRepository.findById(incident.getOrderId()).orElse(null);
                }
                default -> null;
            };
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void markRiskExplanationRequested(ManagerDailyControlConcreteItem concreteItem, LocalDateTime now) {
        if (concreteItem == null || concreteItem.getEntityId() == null) {
            return;
        }
        WorkerRiskIncident incident = riskIncidentRepository.findById(concreteItem.getEntityId()).orElse(null);
        if (incident == null || incident.getStatus() != WorkerRiskIncidentStatus.OPEN) {
            return;
        }
        incident.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
        if (incident.getExplanationRequestedAt() == null) {
            incident.setExplanationRequestedAt(now == null ? LocalDateTime.now() : now);
        }
        riskIncidentRepository.save(incident);
    }

    private InlineKeyboardButton workerTaskTelegramButton(ManagerDailyControlConcreteItem concreteItem) {
        if (isWorkerRiskConcrete(concreteItem)) {
            return ManagerControlWorkerTaskTelegramCallbackService.riskExplanationButton(concreteItem.getId());
        }
        return ManagerControlWorkerTaskTelegramCallbackService.explanationButton(concreteItem.getId());
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
        if (ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE.equals(safe(concreteItem.getEntityType()))) {
            ScheduledClientMessageState state = scheduledClientMessageStateRepository.findById(concreteItem.getEntityId()).orElse(null);
            Company company = state == null || state.getCompanyId() == null
                    ? null
                    : companyRepository.findByIdWithWorkers(state.getCompanyId()).orElse(null);
            String names = companySpecialistName(company);
            if (!names.isBlank()) return names;
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
        String username = safe(user == null ? null : user.getUsername());
        return username.isBlank() ? "Специалист #" + (user == null ? "-" : user.getId()) : username;
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
        ).withSla(workerOrderControlStartedAt(order), null, null, null);
    }

    private LocalDateTime workerOrderControlStartedAt(Order order) {
        if (order == null) {
            return null;
        }
        LocalDateTime statusChangedAt = order.getStatusChangedAt();
        if (statusChangedAt == null && order.getChanged() != null) {
            statusChangedAt = order.getChanged().atStartOfDay();
        }
        return statusChangedAt == null ? null : statusChangedAt.plusDays(WORKER_ORDER_UNCHANGED_DAYS);
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
                            + bindingProblem + ". Если доступна кнопка «Починить», сначала нажмите ее. "
                            + "Если починка недоступна или не помогла, проверьте привязку чата или отправьте запрос вручную."
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
        if (!CompanyChatBindingPolicy.isRequired(company)) {
            return "";
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
        ).withSla(startOfDay(task.getScheduledDate()), null, null, null);
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
        ).withSla(startOfDay(review.getPublishedDate()), null, null, null);
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

    private List<ManagerControlConcreteItemResponse> publicationDateIssueExamples(Manager manager, int limit) {
        return reviewRepository.findPublicationDateIssuesByManager(
                        manager,
                        PageRequest.of(0, Math.max(1, limit))
                ).stream()
                .map(this::publicationDateIssueExample)
                .toList();
    }

    private ManagerControlConcreteItemResponse publicationDateIssueExample(Review review) {
        Order order = reviewOrder(review);
        LocalDateTime observedAt = order == null ? null : order.getStatusChangedAt();
        String reason = "Проблема: заказ находится в «Публикации», но у неопубликованного отзыва не назначена дата. "
                + "Решение: нажмите «Починить» — система проверит тексты и аккаунты и назначит даты. "
                + "Если починка не пройдет, откройте заказ по ссылке и выполните указанную в ошибке рекомендацию.";
        return new ManagerControlConcreteItemResponse(
                null,
                ENTITY_PUBLICATION_DATE_REVIEW,
                review.getId(),
                orderTitle(order, "Отзыв #" + review.getId()),
                taskSubtitle("Публикация", review.getWorker(), null, LocalDate.now()),
                "Нет даты публикации",
                observedAt == null ? null : daysSince(observedAt.toLocalDate(), LocalDate.now()),
                reason,
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
        ).withSla(observedAt, null, null, null);
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
        ).withSla(startOfDay(review.getPublishedDate()), null, null, null);
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
                badReviewTaskReason(task, today),
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
        ).withSla(startOfDay(task.getScheduledDate()), null, null, null);
    }

    private LocalDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private String badReviewTaskReason(BadReviewTask task, LocalDate today) {
        List<String> parts = new ArrayList<>();
        long days = daysSince(task == null ? null : task.getScheduledDate(), today);
        if (task != null && task.getScheduledDate() != null) {
            parts.add(days > 0
                    ? "Плохой отзыв просрочен " + days + " дн., план был " + task.getScheduledDate()
                    : "Плохой отзыв запланирован на сегодня");
        } else {
            parts.add("Плохой отзыв без плановой даты");
        }

        if (task != null && (task.getOriginalRating() != null || task.getTargetRating() != null)) {
            String from = task.getOriginalRating() == null ? "?" : task.getOriginalRating().toString();
            String to = task.getTargetRating() == null ? "?" : task.getTargetRating().toString();
            parts.add("рейтинг " + from + " -> " + to);
        }

        String comment = compact(task == null ? null : task.getComment(), 140);
        if (!comment.isBlank()) {
            parts.add("комментарий: " + comment);
        }

        parts.add("Проверьте карточку отзыва и работу специалиста.");
        return String.join(". ", parts);
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
        boolean explanationRequested = incident.getResolutionAction() == WorkerRiskResolutionAction.EXPLANATION_REQUESTED
                || incident.getExplanationRequestedAt() != null
                || incident.getExplanationPromptedAt() != null
                || incident.getWorkerExplanationAt() != null;
        LocalDateTime notificationStartedAt = explanationRequested
                ? firstNonNullTime(incident.getExplanationRequestedAt(), incident.getCreatedAt())
                : null;
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
                notificationStartedAt,
                notificationStartedAt,
                incident.getExplanationAcceptedAt(),
                incident.getExplanationAcceptedAt() == null ? null : incident.getWorkerUserId(),
                null,
                null,
                incident.getResolutionAction() == null ? null : incident.getResolutionAction().name(),
                incident.getWorkerExplanation(),
                incident.getWorkerExplanationAt(),
                incident.getPenaltyPoints(),
                incident.getRollbackStatus() == null ? null : incident.getRollbackStatus().name(),
                incident.getRollbackMessage(),
                canRollbackRiskIncident(incident),
                safe(incident.getWorkerName()).isBlank() ? incident.getWorkerUsername() : incident.getWorkerName(),
                null,
                null,
                null,
                null
        ).withSla(incident.getCreatedAt(), null, null, null);
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

    private long commonInvoiceActionCount(Manager manager, Set<Long> excludedInvoiceIds) {
        if (excludedInvoiceIds == null || excludedInvoiceIds.isEmpty()) {
            return commonInvoiceActionCount(manager);
        }
        return managerControlInvoices(manager).stream()
                .filter(invoice -> !excludedInvoiceIds.contains(invoice.getId()))
                .count();
    }

    private long commonInvoiceActionCount(Manager manager) {
        return commonInvoiceRepository.countManagerControlInvoices(
                manager,
                COMMON_INVOICE_CRITICAL_STATUSES,
                effectiveCommonInvoiceStaleStatuses(),
                CommonInvoiceStatus.PARTIALLY_PAID,
                CommonInvoiceStatus.COLLECTING,
                LocalDateTime.now().minusDays(COMMON_INVOICE_STALE_DAYS),
                LocalDateTime.now().minusHours(COMMON_INVOICE_PUBLICATION_BLOCKER_HOURS)
        );
    }

    private List<CommonInvoice> managerControlInvoices(Manager manager) {
        return commonInvoiceRepository.findManagerControlInvoices(
                manager,
                COMMON_INVOICE_CRITICAL_STATUSES,
                effectiveCommonInvoiceStaleStatuses(),
                CommonInvoiceStatus.PARTIALLY_PAID,
                CommonInvoiceStatus.COLLECTING,
                LocalDateTime.now().minusDays(COMMON_INVOICE_STALE_DAYS),
                LocalDateTime.now().minusHours(COMMON_INVOICE_PUBLICATION_BLOCKER_HOURS),
                PageRequest.of(0, 10_000)
        );
    }

    private void requireCommonInvoiceProblemResolved(ManagerDailyControlConcreteItem concreteItem) {
        Long invoiceId = concreteItem == null ? null : concreteItem.getEntityId();
        if (invoiceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У карточки общего счета нет ID счета");
        }
        CommonInvoice invoice = commonInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (!isCommonInvoiceManagerControlProblem(invoice, LocalDateTime.now())) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Общий счет все еще требует внимания: статус «"
                        + commonInvoiceStatusLabel(invoice.getStatus())
                        + "». Обновите счет в правой панели или используйте «Починить», если кнопка доступна."
        );
    }

    private void requireAutomationFailureResolved(ManagerDailyControlConcreteItem concreteItem) {
        Manager manager = concreteItem == null
                || concreteItem.getControl() == null
                ? null
                : concreteItem.getControl().getManager();
        if (!managerAutomationFailureService.isStillActionable(
                manager,
                concreteItem == null ? null : concreteItem.getEntityType(),
                concreteItem == null ? null : concreteItem.getEntityId()
        )) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Ошибка автоматизации все еще активна. Устраните причину и дождитесь успешной отправки или следующей синхронизации."
        );
    }

    private ManagerControlConcreteItemResponse repairPublicationDateConcreteItem(
            ManagerDailyControlConcreteItem concreteItem,
            ManagerDailyControl control,
            Principal principal
    ) {
        Review review = reviewRepository.findById(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв карточки контроля не найден"));
        Order order = reviewOrder(review);
        if (order == null || order.getId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У отзыва не найден заказ");
        }
        publicationApprovalService.repairMissingDates(
                order.getId(),
                "source=manager_control;controlItemId=" + concreteItem.getId()
        );
        Review repaired = reviewRepository.findById(review.getId()).orElse(review);
        if (!repaired.isPublish() && repaired.getPublishedDate() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Дата не назначена. Откройте заказ, проверьте тексты и аккаунты отзывов, затем повторите починку."
            );
        }
        return resolveRepairedConcreteItem(
                concreteItem,
                control,
                "Дата публикации назначена автоматически",
                principal,
                "Восстановлена отсутствующая дата публикации отзыва"
        );
    }

    private boolean isCommonInvoiceManagerControlProblem(CommonInvoice invoice, LocalDateTime now) {
        if (invoice == null) {
            return false;
        }
        if (hasText(invoice.getLastError()) || hasText(invoice.getPaymentSuccessNotificationError())) {
            return true;
        }
        CommonInvoiceStatus status = invoice.getStatus();
        if (COMMON_INVOICE_CRITICAL_STATUSES.contains(status)) {
            return true;
        }
        if (status == CommonInvoiceStatus.PARTIALLY_PAID
                && (invoice.getSentAt() == null || invoice.getNextReminderAt() == null)) {
            return true;
        }
        List<CommonInvoiceOrder> invoiceItems = commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        if (status == CommonInvoiceStatus.COLLECTING
                && commonInvoicePublicationBlockerService.hasOverdueBlockers(invoiceItems, now)) {
            return true;
        }
        LocalDateTime updatedAt = invoice.getUpdatedAt();
        LocalDateTime staleBefore = (now == null ? LocalDateTime.now() : now).minusDays(COMMON_INVOICE_STALE_DAYS);
        if (!effectiveCommonInvoiceStaleStatuses().contains(status)
                || updatedAt == null
                || updatedAt.isAfter(staleBefore)) {
            return false;
        }
        if (status != CommonInvoiceStatus.PARTIALLY_PAID
                && status != CommonInvoiceStatus.COLLECTING) {
            return true;
        }
        return invoiceItems.stream()
                .noneMatch(item -> item != null && !item.isReady());
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
        LocalDateTime firstObservedAt = LocalDateTime.now().minusMinutes(Math.max(0, example.waitingMinutes()));
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
        ).withSla(firstObservedAt, null, null, null);
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
        Set<Long> excludedInvoiceIds = managerAutomationFailureService.representedCommonInvoiceIds(manager);
        return managerControlInvoices(manager).stream()
                .filter(invoice -> !excludedInvoiceIds.contains(invoice.getId()))
                .limit(limit)
                .map(invoice -> commonInvoiceExample(invoice, today))
                .toList();
    }

    private Set<CommonInvoiceStatus> effectiveCommonInvoiceStaleStatuses() {
        if (appSettingService.getBoolean(AppSettingService.MANAGER_CONTROL_COLLECTING_STALE_ENABLED, true)) {
            return COMMON_INVOICE_STALE_STATUSES;
        }
        return Set.of(
                CommonInvoiceStatus.READY,
                CommonInvoiceStatus.INVOICED,
                CommonInvoiceStatus.REMINDER,
                CommonInvoiceStatus.PARTIALLY_PAID
        );
    }

    private List<ManagerControlConcreteItemResponse> automationFailureExamples(Manager manager, int limit) {
        return managerAutomationFailureService.issues(manager, limit).stream()
                .map(issue -> new ManagerControlConcreteItemResponse(
                        null,
                        issue.entityType(),
                        issue.entityId(),
                        issue.title(),
                        issue.subtitle(),
                        issue.status(),
                        issue.firstObservedAt() == null
                                ? null
                                : Math.max(0, ChronoUnit.DAYS.between(issue.firstObservedAt().toLocalDate(), LocalDate.now())),
                        issue.reason(),
                        issue.targetUrl(),
                        null,
                        issue.chatUrl(),
                        null,
                        null,
                        ManagerDailyControlItemStatus.OPEN.name(),
                        null,
                        null,
                        issue.lastAttemptAt(),
                        null,
                        null
                ).withSla(issue.firstObservedAt(), null, null, null))
                .toList();
    }

    private ManagerControlConcreteItemResponse commonInvoiceExample(CommonInvoice invoice, LocalDate today) {
        String accountName = invoice.getAccount() == null ? "" : safe(invoice.getAccount().getName());
        long remainingKopecks = Math.max(0, invoice.getAmountKopecks() - invoice.getPaidKopecks());
        List<CommonInvoiceOrder> items = commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        List<CommonInvoiceOrder> publicationBlockers = commonInvoicePublicationBlockerService.overdueBlockers(
                items,
                LocalDateTime.now()
        );
        LocalDateTime attentionStartedAt = publicationBlockers.stream()
                .map(CommonInvoiceOrder::getPublicationBlockerSince)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(invoice.getUpdatedAt());
        return new ManagerControlConcreteItemResponse(
                null,
                "COMMON_INVOICE",
                invoice.getId(),
                safe(invoice.getTitle()).isBlank() ? "Общий счет #" + invoice.getId() : invoice.getTitle(),
                commonInvoiceSubtitle(accountName, invoice.getAmountKopecks(), remainingKopecks),
                publicationBlockers.isEmpty()
                        ? commonInvoiceStatusLabel(invoice.getStatus())
                        : "Требует внимания · блокеров " + publicationBlockers.size(),
                attentionStartedAt == null ? null : daysSince(attentionStartedAt.toLocalDate(), today),
                commonInvoiceReason(invoice, today, items, publicationBlockers),
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
        ).withSla(attentionStartedAt, null, null, null);
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

    private String commonInvoiceReason(
            CommonInvoice invoice,
            LocalDate today,
            List<CommonInvoiceOrder> items,
            List<CommonInvoiceOrder> publicationBlockers
    ) {
        String lastError = safe(invoice.getLastError());
        if (!lastError.isBlank()) {
            return commonInvoiceLastErrorReason(invoice, lastError, items);
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
        if (publicationBlockers != null && !publicationBlockers.isEmpty()) {
            String blockers = publicationBlockers.stream()
                    .limit(5)
                    .map(item -> {
                        Order order = item.getOrder();
                        String orderId = order == null || order.getId() == null ? "?" : String.valueOf(order.getId());
                        long hours = item.getPublicationBlockerSince() == null
                                ? 0
                                : Math.max(0, Duration.between(item.getPublicationBlockerSince(), LocalDateTime.now()).toHours());
                        return "#" + orderId + " «" + orderStatusTitle(order) + "» (" + hours + " ч.)";
                    })
                    .collect(Collectors.joining(", "));
            long publicationOrLater = (items == null ? List.<CommonInvoiceOrder>of() : items).stream()
                    .map(CommonInvoiceOrder::getOrder)
                    .filter(commonInvoicePublicationBlockerService::isPublicationOrLater)
                    .count();
            return "Почему в замечаниях: в общем счете уже есть " + publicationOrLater
                    + " заказ(а) в «Публикации» или выше, но допубликационные позиции блокируют сбор более 48 часов. "
                    + "Блокеры: " + blockers
                    + ". Проверьте доставку напоминаний и состояние заказов. Состав счета автоматически не меняется.";
        }
        long ageDays = invoice.getUpdatedAt() == null ? 0 : daysSince(invoice.getUpdatedAt().toLocalDate(), today);
        if (status == CommonInvoiceStatus.COLLECTING) {
            long ready = items.stream().filter(CommonInvoiceOrder::isReady).count();
            String waitingStatuses = items.stream()
                    .filter(item -> !item.isReady())
                    .map(CommonInvoiceOrder::getOrder)
                    .filter(Objects::nonNull)
                    .map(this::orderStatusTitle)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .limit(4)
                    .collect(Collectors.joining(", "));
            return "Почему в замечаниях: общий счет уже " + ageDays
                    + " дн. находится в «Сборе» и еще не выставлен клиенту. Готово заказов: "
                    + ready + "/" + items.size()
                    + (waitingStatuses.isBlank() ? "" : "; не готовы статусы: " + waitingStatuses)
                    + ". Нажмите «Починить»: система пересчитает позиции и отправит счет, если все заказы действительно готовы.";
        }
        return "Счет завис в статусе «" + commonInvoiceStatusLabel(status) + "» " + ageDays
                + " дн. Нажмите «Починить»: система проверит текущий шаг и выполнит безопасное продолжение.";
    }

    private String commonInvoiceLastErrorReason(
            CommonInvoice invoice,
            String rawError,
            List<CommonInvoiceOrder> items
    ) {
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
        if (commonInvoiceMessageSendRepairable(invoice)) {
            return "Сообщение общего счета не отправлено в клиентский чат: " + limit(rawError, 160)
                    + ". Рекомендация: проверьте привязку чата и нажмите «Починить», чтобы повторить отправку.";
        }
        if (error.startsWith("message_send_stale") || error.startsWith("message_send_in_progress")) {
            return "Отправка сообщения по счету зависла. Рекомендация: откройте «Счет» и повторите отправку вручную.";
        }
        if (error.startsWith("payment_init")) {
            if (commonInvoiceUnsentTlsInitRepairable(invoice)) {
                if (commonInvoiceHasCompetingStandalonePayment(items)) {
                    return "Создание платежной ссылки остановилось на проверке сертификата, но у одного из заказов "
                            + "есть отдельный незавершенный платеж. Откройте «Счет» и сначала сверьте этот платеж вручную.";
                }
                return "Создание платежной ссылки остановилось на проверке сертификата до отправки запроса в T-Bank. "
                        + "Сертификат уже доступен текущему backend. Рекомендация: нажмите «Починить», "
                        + "чтобы безопасно удалить незавершенную попытку и повторно отправить счет.";
            }
            return "Проблема при создании платежной ссылки T-Bank. Рекомендация: откройте «Счет» и сверьте состояние платежа в банке.";
        }
        if (error.startsWith("standalone_payment_route_conflict")) {
            return "У заказа внутри общего счета осталась отдельная платежная ссылка. Нажмите «Починить»: "
                    + "система сверит начатые платежи, зачтет подтвержденные оплаты и закроет только ссылки без "
                    + "банковских или ручных признаков оплаты. При неоднозначном состоянии автоматическая починка остановится.";
        }
        if (error.startsWith("close_failed")) {
            return "Оплата получена, но часть заказов не закрылась. Рекомендация: исправьте заказы и повторите действие в карточке счета.";
        }
        if (error.startsWith("next_order_failed")) {
            return "Платеж закрыт, но следующие заказы не создались. Рекомендация: нажмите «Починить», чтобы повторить создание следующих заказов.";
        }
        if (error.startsWith("review_approval_failed:")) {
            String problem = errorField(rawError, "problem");
            String solution = errorField(rawError, "solution");
            return "Массовое одобрение остановлено без частичных изменений. Проблема: "
                    + (problem.isBlank() ? "не удалось назначить даты публикации" : problem)
                    + ". Решение: "
                    + (solution.isBlank() ? "проверьте заказы общего счета" : solution)
                    + ". После исправления нажмите «Починить», чтобы безопасно повторить одобрение.";
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

    private boolean commonInvoiceMessageSendRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        if (invoice == null || error.isBlank()) {
            return false;
        }
        return error.startsWith("telegram_not_sent")
                || error.startsWith("telegram_exception")
                || error.startsWith("telegram_group_missing")
                || error.startsWith("max_not_sent")
                || error.startsWith("max_exception")
                || error.startsWith("max_group_missing");
    }

    private boolean commonInvoiceUnsentTlsInitRepairable(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && safe(invoice.getTbankOrderId()).isBlank()
                && safe(invoice.getTbankPaymentId()).isBlank()
                && safe(invoice.getTbankTerminalKey()).isBlank()
                && invoice.getTbankPaymentAmountKopecks() == null
                && invoice.getTbankPaymentCreatedAt() == null
                && safe(invoice.getPaymentUrl()).isBlank()
                && CommonPaymentInitFailureClassifier.isPersistedTlsBeforeHttpFailure(invoice.getLastError());
    }

    private boolean commonInvoiceHasCompetingStandalonePayment(List<CommonInvoiceOrder> items) {
        List<Long> orderIds = (items == null ? List.<CommonInvoiceOrder>of() : items).stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return !orderIds.isEmpty()
                && paymentLinkRepository.findByOrderIdInForRead(orderIds).stream()
                .anyMatch(StandaloneBankPaymentPolicy::blocksCommonInvoiceTlsRecovery);
    }

    private boolean commonInvoiceStandaloneRouteRepairable(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && safe(invoice.getLastError()).toLowerCase(Locale.ROOT)
                .startsWith("standalone_payment_route_conflict");
    }

    private boolean commonInvoicePaymentNotificationRepairable(CommonInvoice invoice) {
        return !safe(invoice == null ? null : invoice.getPaymentSuccessNotificationError()).isBlank();
    }

    private boolean commonInvoiceNextOrderRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && error.startsWith("next_order_failed");
    }

    private List<ManagerControlConcreteItemResponse> suspiciousClientClosureExamples(Manager manager, int limit) {
        return clientChatMessageTrackerService.auditExamples(manager, limit).stream()
                .map(this::suspiciousClientClosureExample)
                .toList();
    }

    private ManagerControlConcreteItemResponse suspiciousClientClosureExample(ClientChatUnansweredExample example) {
        String companyTitle = safe(example.companyTitle()).isBlank()
                ? "Компания не определена"
                : example.companyTitle();
        String sender = safe(example.senderName()).isBlank() ? "Клиент" : example.senderName();
        return new ManagerControlConcreteItemResponse(
                null,
                ENTITY_CLIENT_CHAT_AUDIT,
                example.id(),
                companyTitle,
                platformLabel(example.platform()) + " · проверьте полноту ответа",
                "Нужен аудит",
                Math.max(0, example.waitingMinutes() / (60L * 24L)),
                sender + ": " + compact(example.lastMessageText(), 300)
                        + ". Укажите в комментарии найденный ответ или выполненное действие.",
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

    private boolean commonInvoiceReviewApprovalRepairable(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && safe(invoice.getLastError()).toLowerCase(Locale.ROOT).startsWith("review_approval_failed:");
    }

    private String errorField(String rawError, String field) {
        String source = safe(rawError);
        String marker = field + "=";
        int start = source.toLowerCase(Locale.ROOT).indexOf(marker.toLowerCase(Locale.ROOT));
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = source.indexOf(';', start);
        return safe(end < 0 ? source.substring(start) : source.substring(start, end));
    }

    private boolean hasText(String value) {
        return !safe(value).isBlank();
    }

    private record CommonInvoiceChatBinding(Company primaryCompany, Company linkedCompanyWithGroup) {
    }

    private record CommonInvoiceRepairOutcome(String comment, String eventDescription) {
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
            case ARCHIVED -> "Архив";
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
            case "PAYMENT_INTEGRITY" -> "Оплаченный заказ ошибочно вошел в повторный платежный цикл";
            case "PUBLICATION_DATE_ISSUES" -> "Есть заказы в публикации с отзывами без назначенной даты";
            case "CHAT_BINDING_ISSUES" -> "Есть заказы с непривязанной группой соцсети";
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
        if ("CHAT_BINDING_ISSUES".equals(reason)) {
            return 80;
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
        Long controlManagerId = control.getManager() == null ? null : control.getManager().getId();

        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            User owner = currentUser(principal);
            if (OWNER_CONTROL_ALL_MANAGERS.equalsIgnoreCase(safe(owner == null ? null : owner.getOwnerControlViewMode()))) {
                return;
            }
            Set<Long> managerIds = userService.findManagersByUserName(principal.getName()).stream()
                    .map(Manager::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (controlManagerId != null && managerIds.contains(controlManagerId)) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер недоступен");
        }

        if (managerPermissionService.hasRole(authentication, "MANAGER")) {
            User user = currentUser(principal);
            Long ownManagerId = user == null || user.getId() == null
                    ? null
                    : managerRepository.findByUserId(user.getId()).map(Manager::getId).orElse(null);
            if (controlManagerId != null && controlManagerId.equals(ownManagerId)) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер недоступен");
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав");
    }

    private Long actorUserId(Principal principal) {
        User user = currentUser(principal);
        return user == null ? null : user.getId();
    }

    private void acceptControlIfCurrentManager(ManagerDailyControl control, Principal principal, String comment) {
        if (control == null || control.getMorningCompletedAt() != null) {
            return;
        }
        Long actorUserId = actorUserId(principal);
        Long managerUserId = control.getManagerUserId();
        if (managerUserId == null && control.getManager() != null && control.getManager().getUser() != null) {
            managerUserId = control.getManager().getUser().getId();
        }
        if (actorUserId == null || !Objects.equals(actorUserId, managerUserId)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        if (control.getMorningStartedAt() == null) {
            control.setMorningStartedAt(now);
        }
        control.setMorningCompletedAt(now);
        control.setLastActivityAt(now);
        dailyControlRepository.save(control);
        saveEvent(control, null, actorUserId, ManagerDailyControlEventType.CONTROL_ACCEPTED, null, comment);
    }

    private User currentUser(Principal principal) {
        if (principal == null || principal.getName() == null) {
            return null;
        }
        return userService.findByUserName(principal.getName())
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
        invalidateManagerPerformance();
    }

    private void invalidateManagerPerformance() {
        if (managerPerformanceService != null) {
            managerPerformanceService.invalidate();
        }
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

    private LocalDate managerControlOrderCutoff(String status, LocalDate today) {
        LocalDate base = today == null ? LocalDate.now() : today;
        return base.minusDays(managerControlOrderThresholdDays(status));
    }

    private int managerControlOrderThresholdDays(String status) {
        if (REVIEW_CHECK_AUTOMATION_STATUSES.contains(safe(status))) {
            return reviewCheckIntervalDays();
        }
        return OVERDUE_NOTIFICATION_DAYS + 1;
    }

    private int reviewCheckIntervalDays() {
        if (appSettingService == null) {
            return ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS;
        }
        return Math.max(1, appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS,
                ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS
        ));
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
        Map<String, ManagerControlOverdueStatusResponse> statusesByName = orderRepository.summarizeManagerControlOverdueOrdersByManager(
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
                .collect(Collectors.toMap(
                        ManagerControlOverdueStatusResponse::status,
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));

        addDynamicOverdueStatus(statusesByName, manager, "На проверке", today, snoozedByStatus);

        return statusesByName.values().stream()
                .sorted(Comparator
                        .comparingInt((ManagerControlOverdueStatusResponse status) -> orderStatusDisplayRank(status.status()))
                        .thenComparing(ManagerControlOverdueStatusResponse::status, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private void addDynamicOverdueStatus(
            Map<String, ManagerControlOverdueStatusResponse> statusesByName,
            Manager manager,
            String status,
            LocalDate today,
            Map<String, Long> snoozedByStatus
    ) {
        LocalDate cutoff = managerControlOrderCutoff(status, today);
        Page<OrderDTOList> page = orderService.getManagerControlOverdueOrdersByManager(
                manager,
                "",
                status,
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
                1,
                "desc"
        );
        if (page == null) {
            return;
        }
        long adjustedCount = Math.max(0, page.getTotalElements() - snoozedByStatus.getOrDefault(status, 0L));
        if (adjustedCount <= 0) {
            statusesByName.remove(status);
            return;
        }
        LocalDate oldestChanged = page.getContent().stream()
                .map(OrderDTOList::getChanged)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(cutoff);
        statusesByName.put(status, new ManagerControlOverdueStatusResponse(
                status,
                adjustedCount,
                daysSince(oldestChanged, today),
                ordersUrl(manager, status)
        ));
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String text = safe(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private LocalDateTime firstNonNullTime(LocalDateTime... values) {
        if (values == null) {
            return null;
        }
        for (LocalDateTime value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Long firstNonNullLong(Long... values) {
        if (values == null) {
            return null;
        }
        for (Long value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
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

    private record SlaWindow(
            LocalDateTime firstObservedAt,
            LocalDateTime targetDeadlineAt,
            LocalDateTime hardDeadlineAt,
            String state
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
