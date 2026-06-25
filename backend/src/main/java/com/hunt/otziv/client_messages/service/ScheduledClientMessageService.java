package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.dto.ArchiveCompanyMessageCandidate;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageAttempt;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageAttemptStatus;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ArchiveCompanyMessageCandidateRepository;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageAttemptRepository;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.OrderPaymentMessageBuilder;
import com.hunt.otziv.p_products.status.OrderReviewCheckMessageBuilder;
import com.hunt.otziv.p_products.status.OrderStatusNotificationService;
import com.hunt.otziv.p_products.status.OrderStatusTransitionService;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryHoldService;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.whatsapp.service.WhatsAppAuthAlertService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduledClientMessageService {

    public static final String DEFAULT_REVIEW_CHECK_STATUSES = "На проверке";
    public static final String DEFAULT_CLIENT_TEXT_REMINDER_STATUSES = "Новый";
    public static final String DEFAULT_PAYMENT_REMINDER_STATUSES = "Выставлен счет,Напоминание";
    public static final String DEFAULT_PAYMENT_OVERDUE_STATUSES = "Выставлен счет,Напоминание";
    public static final String DEFAULT_CLOSED_ORDER_STATUSES = "Оплачено,Архив,Бан,Не оплачено";
    public static final String DEFAULT_PAYMENT_OVERDUE_TARGET_STATUS = "Не оплачено";
    public static final String DEFAULT_ARCHIVE_COMPANY_STATUS = "На стопе";
    public static final String DEFAULT_ARCHIVE_INACTIVE_ORDER_STATUSES = "Оплачено,Архив,Бан";
    public static final String DEFAULT_OPEN_NEXT_ORDER_REQUEST_STATUSES = "PENDING,FAILED";
    public static final String DEFAULT_REVIEW_LINK_BASE_URL = "https://o-ogo.ru";
    public static final String DEFAULT_REVIEW_REMINDER_TEXT = "{companyAndFilial}\n\nЗдравствуйте! Напоминаем, пожалуйста, проверьте шаблоны отзывов и внесите правки, если они нужны.\n\nСсылка на проверку отзывов: {reviewLink}";
    public static final String DEFAULT_CLIENT_TEXT_REMINDER_TEXT = "{companyAndFilial}\n\nЗдравствуйте! Напоминаем, пожалуйста, пришлите текст или пожелания для отзывов по заказу №{orderId}, чтобы мы могли продолжить работу.";
    public static final String DEFAULT_PUBLICATION_STARTED_TEXT = "{companyAndFilial}\n\nСпасибо, правки получили. Отзывы переданы в публикацию. Будем присылать короткие отчёты по мере публикации.";
    public static final String DEFAULT_PUBLICATION_PROGRESS_REPORT_TEXT = "{companyAndFilial}. Опубликован новый отзыв {progress}.";
    public static final String DEFAULT_PAYMENT_INSTRUCTION_SOURCE = "MANAGER_TEXT";
    public static final String DEFAULT_PAYMENT_REMINDER_TEXT = "{companyAndFilial}\n\n{managerPayText} К оплате: {sum} руб.";
    public static final String DEFAULT_PAYMENT_LINK_COPY_TEXT = "{companyAndFilial}\n\nЗдравствуйте, ваш заказ выполнен. К оплате: {sum} руб.\n\n{paymentInstruction}\n\n{paymentAfterword}";
    public static final String DEFAULT_PAYMENT_SUCCESS_TEXT = "Оплата прошла успешно.\n\nНовый заказ принят в работу.\n{orderLine}{companyLine}Сумма: {sum}\nСтраница оплаты: {paymentPage}\n\n{receiptText}";
    public static final String DEFAULT_REVIEW_RECOVERY_NOTICE_TEXT = "{companyAndFilial}\n\nВсе отзывы по заказу №{orderId} восстановлены. Продолжаем работу.";
    public static final String DEFAULT_ARCHIVE_OFFER_TEXT = "{company}\n\nЗдравствуйте! Давно не запускали новый заказ. Можем подготовить новую аккуратную серию отзывов и обновить карточку компании. Если актуально, напишите, пожалуйста, сколько отзывов нужно в этот раз.";

    public static final int DEFAULT_REMINDER_INTERVAL_DAYS = 2;
    public static final int DEFAULT_CLIENT_TEXT_REMINDER_INTERVAL_DAYS = 3;
    public static final int DEFAULT_REVIEW_CHECK_AUTO_ARCHIVE_DAYS = 30;
    public static final int DEFAULT_REVIEW_CHECK_RETRY_DELAY_HOURS = 2;
    public static final int DEFAULT_PAYMENT_INVOICE_RETRY_DELAY_HOURS = 2;
    public static final int DEFAULT_BAD_REVIEW_INVOICE_RETRY_DELAY_HOURS = 2;
    public static final int DEFAULT_REVIEW_RECOVERY_NOTICE_RETRY_DELAY_HOURS = 2;
    public static final int DEFAULT_BAD_REVIEW_AUTO_BAN_DELAY_DAYS = 2;
    public static final int DEFAULT_ARCHIVE_REORDER_MONTHS = 3;
    public static final int DEFAULT_ARCHIVE_REORDER_JITTER_DAYS = 10;
    public static final int DEFAULT_PAYMENT_OVERDUE_DAYS = 30;
    public static final int DEFAULT_NO_SEND_RETRY_DAYS = 1;
    public static final int DEFAULT_CANDIDATE_LIMIT = 200;
    public static final int DEFAULT_LOCK_MINUTES = 5;
    public static final int DEFAULT_RETENTION_DAYS = 90;
    public static final int DEFAULT_TICK_BATCH_SIZE = 5;
    public static final int DEFAULT_DAILY_LIMIT = 140;
    public static final int DEFAULT_DEFAULT_GAP_SECONDS = 180;
    public static final int DEFAULT_TELEGRAM_GAP_SECONDS = 90;
    public static final int DEFAULT_MAX_GAP_SECONDS = 90;
    public static final int DEFAULT_WHATSAPP_GAP_SECONDS = 180;
    public static final int DEFAULT_WHATSAPP_AUTH_RETRY_HOURS = 2;
    public static final int DEFAULT_WHATSAPP_AUTH_ALERT_COOLDOWN_HOURS = 12;
    public static final int DEFAULT_NO_SEND_MAX_FAILURES = 30;
    public static final int DEFAULT_ERROR_PROTECTION_THRESHOLD = 20;
    public static final int DEFAULT_ERROR_PROTECTION_WINDOW_MINUTES = 10;
    public static final int DEFAULT_ERROR_PROTECTION_COOLDOWN_MINUTES = 60;
    private static final Duration SUMMARY_LOG_INTERVAL = Duration.ofMinutes(5);
    private static final String STATUS_TO_CHECK = "В проверку";
    private static final String STATUS_IN_CHECK = "На проверке";
    private static final String STATUS_PUBLIC = "Опубликовано";
    private static final String STATUS_TO_PAY = "Выставлен счет";
    private static final String STATUS_NOT_PAID = "Не оплачено";
    private static final String STATUS_PAYMENT = "Оплачено";
    private static final String STATUS_ARCHIVE = "Архив";
    private static final String STATUS_BAN = "Бан";
    private static final String STATUS_REMINDER = "Напоминание";

    private final ScheduledClientMessageStateRepository stateRepository;
    private final ScheduledClientMessageAttemptRepository attemptRepository;
    private final ArchiveCompanyMessageCandidateRepository archiveCandidateRepository;
    private final OrderRepository orderRepository;
    private final CompanyRepository companyRepository;
    private final AppSettingService appSettingService;
    private final ClientChatMessageSender messageSender;
    private final ClientMessageSlotPlanner slotPlanner;
    private final OrderStatusTransitionService orderStatusTransitionService;
    private final OrderStatusNotificationService orderStatusNotificationService;
    private final OrderPaymentMessageBuilder orderPaymentMessageBuilder;
    private final PaymentLinkService paymentLinkService;
    private final OrderReviewCheckMessageBuilder reviewCheckMessageBuilder;
    private final BadReviewTaskService badReviewTaskService;
    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    private final WhatsAppAuthAlertService whatsAppAuthAlertService;
    private final ReviewRecoveryHoldService reviewRecoveryHoldService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final ReviewRecoveryBatchRepository reviewRecoveryBatchRepository;
    private final ObjectProvider<CommonBillingService> commonBillingServiceProvider;
    private final Clock clock = Clock.systemDefaultZone();
    @Value("${client.messages.reconcile-interval:PT5M}")
    private Duration reconcileInterval;
    private LocalDateTime lastReconcileAt;
    private LocalDateTime lastCleanupAt;
    private LocalDateTime lastSummaryLogAt;

    @Scheduled(
            fixedDelayString = "${client.messages.tick-delay-ms:30000}",
            initialDelayString = "${client.messages.initial-delay-ms:90000}"
    )
    @Transactional
    public void tick() {
        LocalDateTime nowStorage = LocalDateTime.now(clock);
        LocalDateTime nowIrkutsk = nowIrkutsk();
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED, true)) {
            logWorkerDisabled(nowStorage, nowIrkutsk);
            return;
        }

        LocalDateTime pausedUntil = clientMessagesPausedUntil();
        if (pausedUntil != null && pausedUntil.isAfter(nowStorage)) {
            if (clearLegacyDryRunPause(pausedUntil)) {
                pausedUntil = null;
            } else {
                logPaused(nowStorage, nowIrkutsk, pausedUntil);
                return;
            }
        }

        ClientMessageReconcileSummary reconcileSummary = ClientMessageReconcileSummary.empty();
        if (shouldReconcileCandidates(nowStorage)) {
            reconcileSummary = reconcileCandidates(nowStorage);
            lastReconcileAt = nowStorage;
        }
        cleanupOldAttempts(nowStorage);
        releaseDryRunMessagesIfLiveEnabled(nowStorage);

        String windowsSpec = businessWindows();
        if (!slotPlanner.isAllowedNow(nowIrkutsk, windowsSpec)) {
            logTickSummary(nowStorage, nowIrkutsk, windowsSpec, false, reconcileSummary, 0, 0);
            return;
        }

        int batchSize = intSetting(AppSettingService.CLIENT_MESSAGES_TICK_BATCH_SIZE, DEFAULT_TICK_BATCH_SIZE, 1, 100);
        List<ScheduledClientMessageState> dueStates = stateRepository.findDue(
                ScheduledMessageStateStatus.ACTIVE,
                nowStorage,
                PageRequest.of(0, batchSize)
        );

        int processed = 0;
        for (ScheduledClientMessageState state : dueStates) {
            if (!lockState(state, nowStorage)) {
                continue;
            }
            processState(state.getId(), nowStorage);
            processed++;
        }
        logTickSummary(nowStorage, nowIrkutsk, windowsSpec, true, reconcileSummary, dueStates.size(), processed);
    }

    private ClientMessageReconcileSummary reconcileCandidates(LocalDateTime nowStorage) {
        int clientTextReminderCandidates = 0;
        int reviewCheckCandidates = 0;
        int reviewCheckAutoArchiveCandidates = 0;
        int paymentReminderCandidates = 0;
        int paymentOverdueCandidates = 0;
        int archiveReorderCandidates = 0;
        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_ENABLED, true)) {
            int intervalDays = clientTextReminderIntervalDays();
            clientTextReminderCandidates = ensureClientTextWaitingStates(
                    listSetting(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES, DEFAULT_CLIENT_TEXT_REMINDER_STATUSES),
                    nowStorage.minusDays(intervalDays),
                    intervalDays
            );
        }

        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_ENABLED, true)) {
            int intervalDays = reviewCheckIntervalDays();
            reviewCheckCandidates = ensureOrderStates(
                    ClientMessageScenario.REVIEW_CHECK_REMINDER,
                    listSetting(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES, DEFAULT_REVIEW_CHECK_STATUSES),
                    nowStorage.minusDays(intervalDays),
                    intervalDays
            );
        }
        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_AUTO_ARCHIVE_ENABLED, true)) {
            int autoArchiveDays = reviewCheckAutoArchiveDays();
            reviewCheckAutoArchiveCandidates = ensureOrderStates(
                    ClientMessageScenario.REVIEW_CHECK_AUTO_ARCHIVE,
                    listSetting(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES, DEFAULT_REVIEW_CHECK_STATUSES),
                    nowStorage.minusDays(autoArchiveDays),
                    autoArchiveDays
            );
        }

        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, true)) {
            int intervalDays = paymentReminderIntervalDays();
            paymentReminderCandidates = ensureOrderStates(
                    ClientMessageScenario.PAYMENT_REMINDER,
                    listSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_STATUSES, DEFAULT_PAYMENT_REMINDER_STATUSES),
                    nowStorage.minusDays(intervalDays),
                    intervalDays
            );
        }

        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED, true)) {
            int overdueDays = intSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS, DEFAULT_PAYMENT_OVERDUE_DAYS, 1, 365);
            paymentOverdueCandidates = ensureOrderStates(
                    ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION,
                    listSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_STATUSES, DEFAULT_PAYMENT_OVERDUE_STATUSES),
                    nowStorage.minusDays(overdueDays),
                    overdueDays
            );
        }

        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_ENABLED, true)) {
            archiveReorderCandidates = ensureArchiveCompanyStates(nowStorage);
        }
        return new ClientMessageReconcileSummary(
                clientTextReminderCandidates,
                reviewCheckCandidates,
                reviewCheckAutoArchiveCandidates,
                paymentReminderCandidates,
                paymentOverdueCandidates,
                archiveReorderCandidates
        );
    }

    private boolean shouldReconcileCandidates(LocalDateTime nowStorage) {
        Duration interval = reconcileInterval == null || reconcileInterval.isNegative()
                ? Duration.ZERO
                : reconcileInterval;
        return lastReconcileAt == null || !lastReconcileAt.plus(interval).isAfter(nowStorage);
    }

    private int ensureClientTextWaitingStates(
            Collection<String> statuses,
            LocalDateTime cutoff,
            int intervalDays
    ) {
        List<OrderRepository.ClientMessageCandidate> candidates = orderRepository.findClientTextWaitingMessageCandidates(
                statuses,
                cutoff,
                PageRequest.of(0, candidateLimit())
        );

        int created = 0;
        for (OrderRepository.ClientMessageCandidate order : candidates) {
            LocalDateTime waitingChangedAt = order.getStatusChangedAt();
            LocalDateTime baseDueAt = waitingChangedAt.plusDays(intervalDays);
            String targetKey = clientTextWaitingTargetKey(order.getId(), waitingChangedAt);

            if (ensureState(
                    ClientMessageScenario.CLIENT_TEXT_REMINDER,
                    ClientMessageTargetType.ORDER,
                    targetKey,
                    order.getCompanyId(),
                    order.getId(),
                    null,
                    scheduleAtStorage(baseDueAt)
            )) {
                created++;
            }
        }
        if (created > 0) {
            log.info("Client messages scheduled client-text states created={} candidates={}", created, candidates.size());
        }
        return candidates.size();
    }

    private int ensureOrderStates(
            ClientMessageScenario scenario,
            Collection<String> statuses,
            LocalDateTime cutoff,
            int intervalDays
    ) {
        List<OrderRepository.ClientMessageCandidate> candidates = orderRepository.findClientMessageCandidates(
                statuses,
                cutoff,
                PageRequest.of(0, candidateLimit())
        );

        int created = 0;
        for (OrderRepository.ClientMessageCandidate order : candidates) {
            LocalDateTime statusChangedAt = order.getStatusChangedAt();
            LocalDateTime baseDueAt = statusChangedAt.plusDays(intervalDays);
            String targetKey = orderTargetKey(order.getId(), statusChangedAt);

            if (ensureState(
                    scenario,
                    ClientMessageTargetType.ORDER,
                    targetKey,
                    order.getCompanyId(),
                    order.getId(),
                    null,
                    scheduleAtStorage(baseDueAt)
            )) {
                created++;
            }
        }
        if (created > 0) {
            log.info("Client messages scheduled new order states scenario={} created={} candidates={}", scenario, created, candidates.size());
        }
        return candidates.size();
    }

    private int ensureArchiveCompanyStates(LocalDateTime nowStorage) {
        int archiveReorderMonths = archiveReorderMonths();
        LocalDateTime cutoff = nowStorage.minusMonths(archiveReorderMonths);
        List<ArchiveCompanyMessageCandidate> candidates = archiveCandidateRepository.findCandidates(
                cutoff,
                candidateLimit(),
                archiveCompanyStatus(),
                listSetting(AppSettingService.CLIENT_MESSAGES_ARCHIVE_INACTIVE_ORDER_STATUSES, DEFAULT_ARCHIVE_INACTIVE_ORDER_STATUSES),
                listSetting(AppSettingService.CLIENT_MESSAGES_OPEN_NEXT_ORDER_REQUEST_STATUSES, DEFAULT_OPEN_NEXT_ORDER_REQUEST_STATUSES)
        );
        int created = 0;
        for (ArchiveCompanyMessageCandidate candidate : candidates) {
            String targetKey = archiveCompanyTargetKey(candidate.companyId(), candidate.statusChangedAt());
            if (ensureState(
                    ClientMessageScenario.ARCHIVE_REORDER_OFFER,
                    ClientMessageTargetType.ARCHIVE_COMPANY,
                    targetKey,
                    candidate.companyId(),
                    null,
                    candidate.archiveOrderId(),
                    archiveReorderAttemptAt(candidate.statusChangedAt().plusMonths(archiveReorderMonths), targetKey)
            )) {
                created++;
            }
        }
        if (created > 0) {
            log.info("Client messages scheduled new archive states created={} candidates={}", created, candidates.size());
        }
        return candidates.size();
    }

    private boolean ensureState(
            ClientMessageScenario scenario,
            ClientMessageTargetType targetType,
            String targetKey,
            Long companyId,
            Long orderId,
            Long archiveOrderId,
            LocalDateTime nextAttemptAt
    ) {
        Optional<ScheduledClientMessageState> existing = stateRepository.findByScenarioAndTargetKey(scenario, targetKey);
        if (existing.isPresent()) {
            ScheduledClientMessageState state = existing.get();
            if (state.getStatus() == ScheduledMessageStateStatus.ACTIVE && state.getNextAttemptAt() == null) {
                state.setNextAttemptAt(nextAttemptAt);
            }
            if (archiveOrderId != null && state.getArchiveOrderId() == null) {
                state.setArchiveOrderId(archiveOrderId);
            }
            return false;
        }

        stateRepository.save(ScheduledClientMessageState.builder()
                .scenario(scenario)
                .targetType(targetType)
                .targetKey(targetKey)
                .companyId(companyId)
                .orderId(orderId)
                .archiveOrderId(archiveOrderId)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(nextAttemptAt)
                .build());
        return true;
    }

    private boolean lockState(ScheduledClientMessageState state, LocalDateTime nowStorage) {
        return stateRepository.lockDueState(
                state.getId(),
                nowStorage,
                nowStorage.plus(Duration.ofMinutes(DEFAULT_LOCK_MINUTES))
        ) > 0;
    }

    private void processState(Long stateId, LocalDateTime nowStorage) {
        ScheduledClientMessageState state = stateRepository.findById(stateId).orElse(null);
        if (state == null || state.getStatus() != ScheduledMessageStateStatus.ACTIVE) {
            return;
        }

        LocalDateTime nowIrkutsk = nowIrkutsk();
        boolean requiresMessageSlot = requiresClientMessageSlot(state.getScenario());
        if (requiresMessageSlot && !withinDailyLimit(nowStorage)) {
            postpone(state, nextBusinessDayStartStorage(nowIrkutsk), "daily_limit", "Дневной лимит авторассылки исчерпан");
            return;
        }

        Company company = resolveCompany(state);
        if (company == null) {
            disable(state, nowStorage, "company_missing", "Компания для авторассылки не найдена");
            return;
        }

        if (requiresMessageSlot) {
            String expectedChannel = expectedChannel(company);
            LocalDateTime allowedByGap = slotPlanner.afterGap(
                    nowIrkutsk,
                    lastSentAtIrkutsk(expectedChannel),
                    gapSeconds(expectedChannel),
                    businessWindows()
            );
            if (allowedByGap.isAfter(nowIrkutsk.plusSeconds(1))) {
                postpone(state, toStorageTime(allowedByGap), "rate_limited", "Следующий слот отправки: " + allowedByGap);
                return;
            }
        }

        switch (state.getScenario()) {
            case CLIENT_TEXT_REMINDER -> sendClientTextReminder(state, company, nowStorage);
            case REVIEW_CHECK_REMINDER -> sendOrderReminder(
                    state,
                    company,
                    listSetting(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES, DEFAULT_REVIEW_CHECK_STATUSES),
                    "Заказ уже не в статусе проверки отзывов",
                    reviewCheckIntervalDays(),
                    true,
                    nowStorage
            );
            case REVIEW_CHECK_DELIVERY_RETRY -> retryReviewCheckDelivery(state, company, nowStorage);
            case REVIEW_CHECK_AUTO_ARCHIVE -> autoArchiveStaleReviewCheck(state, nowStorage);
            case PAYMENT_REMINDER -> sendOrderReminder(
                    state,
                    company,
                    listSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_STATUSES, DEFAULT_PAYMENT_REMINDER_STATUSES),
                    "Заказ уже не ожидает оплату",
                    paymentReminderIntervalDays(),
                    false,
                    nowStorage
            );
            case PAYMENT_INVOICE_RETRY -> retryPaymentInvoice(state, company, nowStorage);
            case ARCHIVE_REORDER_OFFER -> sendArchiveOffer(state, company, nowStorage);
            case PAYMENT_OVERDUE_ESCALATION -> escalateOverduePayment(state, nowStorage);
            case BAD_REVIEW_INVOICE -> retryBadReviewInvoice(state, company, nowStorage);
            case BAD_REVIEW_AUTO_BAN -> autoBanAfterBadReviews(state, nowStorage);
            case REVIEW_RECOVERY_NOTICE -> sendReviewRecoveryNotice(state, company, nowStorage);
        }
    }

    private void sendClientTextReminder(
            ScheduledClientMessageState state,
            Company company,
            LocalDateTime nowStorage
    ) {
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null) {
            disable(state, nowStorage, "order_missing", "Заказ для напоминания о тексте клиента не найден");
            return;
        }

        if (!order.isWaitingForClient()) {
            markDone(state, nowStorage, "client_text_received", "Заказ уже не ждет текст клиента");
            return;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return;
        }

        String status = statusTitle(order);
        if (!listSetting(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES, DEFAULT_CLIENT_TEXT_REMINDER_STATUSES).contains(status)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже не в статусе ожидания текста клиента");
            return;
        }
        if (!isCurrentClientTextWaitingCycle(state, order)) {
            markDone(state, nowStorage, "client_text_cycle_changed", "Заказ уже перешел в новый цикл ожидания текста клиента");
            return;
        }

        String message = clientTextReminderText(order);
        sendMessage(state, company, manager(order), message, nowStorage, clientTextReminderIntervalDays());
    }

    private void sendOrderReminder(
            ScheduledClientMessageState state,
            Company company,
            Collection<String> expectedStatuses,
            String changedMessage,
            int nextIntervalDays,
            boolean reviewCheck,
            LocalDateTime nowStorage
    ) {
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null) {
            disable(state, nowStorage, "order_missing", "Заказ для авторассылки не найден");
            return;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return;
        }
        if (!reviewCheck && completePaymentMessageForCommonBillingLinkedOrder(
                state,
                order,
                nowStorage,
                "Заказ входит в общий счет; одиночное платежное напоминание не отправляется"
        )) {
            return;
        }

        String status = statusTitle(order);
        if (!expectedStatuses.contains(status)) {
            markDone(state, nowStorage, "order_status_changed", changedMessage);
            return;
        }
        if (!isCurrentOrderCycle(state, order)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже перешел в новый цикл статуса");
            return;
        }

        String message;
        try {
            message = reviewCheck
                    ? reviewCheckReminderText(order)
                    : paymentReminderText(order);
        } catch (PaymentInstructionException e) {
            registerFailure(state, nowStorage, "payment_instruction_failed", e.getMessage(), null, 0);
            return;
        }
        boolean sent = sendMessage(state, company, manager(order), message, nowStorage, nextIntervalDays);
        if (sent && !reviewCheck && STATUS_TO_PAY.equals(status)) {
            movePaymentReminderToReminderStatus(state, order, nowStorage);
        }
    }

    private void retryReviewCheckDelivery(ScheduledClientMessageState state, Company company, LocalDateTime nowStorage) {
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null) {
            disable(state, nowStorage, "order_missing", "Заказ для повторной отправки проверки отзывов не найден");
            return;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return;
        }

        String status = statusTitle(order);
        if (!STATUS_TO_CHECK.equals(status)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже вышел из статуса ожидания отправки проверки");
            return;
        }
        if (!isCurrentOrderCycle(state, order)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже перешел в новый цикл статуса");
            return;
        }

        String message = reviewCheckReminderText(order);
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)) {
            registerDryRun(state, nowStorage, message, null);
            return;
        }

        long startedAt = System.currentTimeMillis();
        try {
            String appliedStatus = orderStatusNotificationService.sendMessageToClientChat(
                    STATUS_TO_CHECK,
                    order,
                    order.getManager() == null ? null : order.getManager().getClientId(),
                    order.getCompany() == null ? null : order.getCompany().getGroupId(),
                    message,
                    STATUS_IN_CHECK
            );
            long durationMs = System.currentTimeMillis() - startedAt;
            if (STATUS_IN_CHECK.equals(appliedStatus)) {
                registerSuccess(state, nowStorage, expectedChannel(company), message, durationMs, null);
                markDone(state, nowStorage, null, null);
                log.info("Review check delivery retry sent orderId={} stateId={}", order.getId(), state.getId());
                return;
            }

            registerFailure(
                    state,
                    nowStorage,
                    "client_chat_send_failed",
                    "Ссылка на проверку не отправлена, заказ остался в статусе \"" + appliedStatus + "\"",
                    message,
                    durationMs
            );
        } catch (Exception e) {
            registerFailure(
                    state,
                    nowStorage,
                    "review_check_retry_exception",
                    readableException(e),
                    message,
                    System.currentTimeMillis() - startedAt
            );
        }
    }

    private void retryPaymentInvoice(ScheduledClientMessageState state, Company company, LocalDateTime nowStorage) {
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null) {
            disable(state, nowStorage, "order_missing", "Заказ для повторной отправки счета не найден");
            return;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return;
        }
        if (completePaymentMessageForCommonBillingLinkedOrder(
                state,
                order,
                nowStorage,
                "Заказ входит в общий счет; одиночный финальный счет не отправляется"
        )) {
            return;
        }

        String status = statusTitle(order);
        if (!STATUS_PUBLIC.equals(status)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже вышел из статуса опубликованного счета");
            return;
        }
        if (!isCurrentOrderCycle(state, order)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже перешел в новый цикл статуса");
            return;
        }

        String message;
        try {
            message = orderPaymentMessageBuilder.publishedOrderPaymentMessage(order);
        } catch (ResponseStatusException e) {
            registerFailure(state, nowStorage, "payment_instruction_failed", readableException(e), null, 0);
            return;
        } catch (Exception e) {
            registerFailure(state, nowStorage, "payment_instruction_failed", readableException(e), null, 0);
            return;
        }

        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)) {
            registerDryRun(state, nowStorage, message, null);
            return;
        }

        long startedAt = System.currentTimeMillis();
        try {
            String appliedStatus = orderStatusNotificationService.sendMessageToClientChat(
                    STATUS_PUBLIC,
                    order,
                    order.getManager() == null ? null : order.getManager().getClientId(),
                    order.getCompany() == null ? null : order.getCompany().getGroupId(),
                    message,
                    STATUS_TO_PAY
            );
            long durationMs = System.currentTimeMillis() - startedAt;
            if (STATUS_TO_PAY.equals(appliedStatus)) {
                registerSuccess(state, nowStorage, expectedChannel(company), message, durationMs, null);
                markDone(state, nowStorage, null, null);
                log.info("Payment invoice retry sent orderId={} stateId={}", order.getId(), state.getId());
                return;
            }

            registerFailure(
                    state,
                    nowStorage,
                    "client_chat_send_failed",
                    "Финальный счет не отправлен, заказ остался в статусе \"" + appliedStatus + "\"",
                    message,
                    durationMs
            );
        } catch (Exception e) {
            registerFailure(
                    state,
                    nowStorage,
                    "payment_invoice_retry_exception",
                    readableException(e),
                    message,
                    System.currentTimeMillis() - startedAt
            );
        }
    }

    private void autoArchiveStaleReviewCheck(ScheduledClientMessageState state, LocalDateTime nowStorage) {
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null) {
            disable(state, nowStorage, "order_missing", "Заказ для автоархива проверки не найден");
            return;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return;
        }

        String status = statusTitle(order);
        if (!listSetting(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES, DEFAULT_REVIEW_CHECK_STATUSES).contains(status)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже не в статусе проверки отзывов");
            return;
        }
        if (!isCurrentOrderCycle(state, order)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже перешел в новый цикл статуса");
            return;
        }

        String message = "Заказ #" + order.getId() + " автоматически переведен в Архив: клиент не подтвердил тексты за "
                + reviewCheckAutoArchiveDays() + " дн.";
        try {
            boolean changed = orderStatusTransitionService.changeStatusForOrder(order.getId(), "Архив");
            if (changed) {
                recordAttempt(state, ScheduledMessageAttemptStatus.SENT, "system", null, null, message, 0);
                markDone(state, nowStorage, null, null);
                log.info("Review check auto-archived orderId={} stateId={}", order.getId(), state.getId());
                return;
            }
            registerFailure(state, nowStorage, "status_change_failed", "Статус заказа не изменен", message, 0);
        } catch (Exception e) {
            registerFailure(state, nowStorage, "review_check_auto_archive_exception", readableException(e), message, 0);
        }
    }

    private void retryBadReviewInvoice(ScheduledClientMessageState state, Company company, LocalDateTime nowStorage) {
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null) {
            disable(state, nowStorage, "order_missing", "Заказ для повторной отправки счета после плохого отзыва не найден");
            return;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return;
        }
        if (refreshCommonInvoiceForLinkedOrder(order)) {
            markDone(
                    state,
                    nowStorage,
                    "common_billing_linked",
                    "Заказ входит в общий счет; одиночный счет после плохого не отправляется"
            );
            return;
        }

        String status = statusTitle(order);
        if (badReviewInvoiceTerminalStatuses().contains(status)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже не ожидает счет после плохого отзыва");
            return;
        }
        if (!STATUS_NOT_PAID.equals(status)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ после плохих уже не в статусе \"Не оплачено\"");
            return;
        }

        String message;
        try {
            message = badReviewTaskService.buildBadReviewInvoiceMessage(order);
        } catch (Exception e) {
            registerFailure(state, nowStorage, "payment_instruction_failed", readableException(e), null, 0);
            return;
        }

        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)) {
            registerDryRun(state, nowStorage, message, null);
            return;
        }

        long startedAt = System.currentTimeMillis();
        try {
            boolean sent = orderStatusNotificationService.sendInformationalMessageToClientChat(
                    order,
                    order.getManager() == null ? null : order.getManager().getClientId(),
                    order.getCompany() == null ? null : order.getCompany().getGroupId(),
                    message,
                    "счет после плохого отзыва"
            );
            long durationMs = System.currentTimeMillis() - startedAt;
            if (sent) {
                registerSuccess(state, nowStorage, expectedChannel(company), message, durationMs, null);
                markDone(state, nowStorage, null, null);
                scheduleBadReviewAutoBanIfReady(order);
                log.info("Bad review invoice retry sent orderId={} stateId={}", order.getId(), state.getId());
                return;
            }

            registerFailure(
                    state,
                    nowStorage,
                    "client_chat_send_failed",
                    "Счет после плохого отзыва не отправлен, заказ остался в статусе \"" + status + "\"",
                    message,
                    durationMs
            );
        } catch (Exception e) {
            registerFailure(
                    state,
                    nowStorage,
                    "bad_review_invoice_retry_exception",
                    readableException(e),
                    message,
                    System.currentTimeMillis() - startedAt
            );
        }
    }

    private void scheduleBadReviewAutoBanIfReady(Order order) {
        var summary = badReviewTaskService.getSummaryForOrder(order.getId());
        if (summary != null && summary.pending() == 0 && summary.done() > 0) {
            paymentInvoiceRetryScheduler.scheduleBadReviewAutoBan(order);
        }
    }

    private boolean refreshCommonInvoiceForLinkedOrder(Order order) {
        if (order == null || order.getId() == null) {
            return false;
        }
        CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
        return commonBillingService != null && commonBillingService.refreshLinkedOrderAmount(order.getId());
    }

    private boolean completePaymentMessageForCommonBillingLinkedOrder(
            ScheduledClientMessageState state,
            Order order,
            LocalDateTime nowStorage,
            String message
    ) {
        if (!refreshCommonInvoiceForLinkedOrder(order)) {
            return false;
        }
        markDone(state, nowStorage, "common_billing_linked", message);
        return true;
    }

    private void autoBanAfterBadReviews(ScheduledClientMessageState state, LocalDateTime nowStorage) {
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED, true)) {
            recordAttempt(
                    state,
                    ScheduledMessageAttemptStatus.SKIPPED,
                    "system",
                    "bad_review_auto_ban_disabled",
                    "Автобан после плохих выключен настройкой",
                    "Автобан после плохих выключен настройкой",
                    0
            );
            markDone(state, nowStorage, null, null);
            return;
        }

        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null) {
            disable(state, nowStorage, "order_missing", "Заказ для автобана после плохих не найден");
            return;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return;
        }

        String status = statusTitle(order);
        if (STATUS_PAYMENT.equals(status) || STATUS_ARCHIVE.equals(status) || STATUS_BAN.equals(status)) {
            markDone(state, nowStorage, "order_closed", "Заказ уже закрыт: " + status);
            return;
        }
        if (!STATUS_NOT_PAID.equals(status) && !STATUS_TO_PAY.equals(status) && !STATUS_REMINDER.equals(status)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже не ожидает автобан после плохих: " + status);
            return;
        }

        var summary = badReviewTaskService.getSummaryForOrder(order.getId());
        if (summary == null || summary.pending() > 0 || summary.done() <= 0) {
            markDone(state, nowStorage, "bad_reviews_not_ready", "Плохие задачи уже не готовы к автобану");
            return;
        }

        String message = "Заказ #" + order.getId() + " автоматически переведен в Бан: после финального счета за плохие отзывы прошло "
                + badReviewAutoBanDelayDays() + " дн., оплаты нет.";
        try {
            boolean changed = orderStatusTransitionService.changeStatusForOrder(order.getId(), STATUS_BAN);
            if (changed) {
                recordAttempt(state, ScheduledMessageAttemptStatus.SENT, "system", null, null, message, 0);
                markDone(state, nowStorage, null, null);
                log.info("Bad review auto-ban applied orderId={} stateId={}", order.getId(), state.getId());
                return;
            }
            registerFailure(state, nowStorage, "status_change_failed", "Статус заказа не изменен", message, 0);
        } catch (Exception e) {
            registerFailure(state, nowStorage, "bad_review_auto_ban_exception", readableException(e), message, 0);
        }
    }

    private void sendArchiveOffer(ScheduledClientMessageState state, Company company, LocalDateTime nowStorage) {
        String expectedStatus = archiveCompanyStatus();
        if (!expectedStatus.equals(companyStatusTitle(company))) {
            markDone(state, nowStorage, "company_status_changed", "Компания уже не в статусе \"" + expectedStatus + "\"");
            return;
        }
        if (!isCurrentArchiveCompanyCycle(state, company)) {
            markDone(state, nowStorage, "company_status_changed", "Компания уже перешла в новый цикл статуса");
            return;
        }
        if (archiveCandidateRepository.hasArchiveReorderBlocker(
                company == null ? null : company.getId(),
                listSetting(AppSettingService.CLIENT_MESSAGES_ARCHIVE_INACTIVE_ORDER_STATUSES, DEFAULT_ARCHIVE_INACTIVE_ORDER_STATUSES),
                listSetting(AppSettingService.CLIENT_MESSAGES_OPEN_NEXT_ORDER_REQUEST_STATUSES, DEFAULT_OPEN_NEXT_ORDER_REQUEST_STATUSES)
        )) {
            markDone(state, nowStorage, "archive_reorder_blocked", "У компании уже есть активный заказ или открытая заявка на следующий заказ");
            return;
        }

        String message = archiveOfferText(company);
        sendMessage(state, company, company.getManager(), message, nowStorage, null);
    }

    private void escalateOverduePayment(ScheduledClientMessageState state, LocalDateTime nowStorage) {
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null) {
            disable(state, nowStorage, "order_missing", "Заказ для просрочки оплаты не найден");
            return;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return;
        }

        String status = statusTitle(order);
        if (listSetting(AppSettingService.CLIENT_MESSAGES_CLOSED_ORDER_STATUSES, DEFAULT_CLOSED_ORDER_STATUSES).contains(status)) {
            markDone(state, nowStorage, "order_closed", "Заказ уже не требует автоэскалации оплаты");
            return;
        }
        if (!listSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_STATUSES, DEFAULT_PAYMENT_OVERDUE_STATUSES).contains(status)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже не ожидает оплату");
            return;
        }
        if (!isCurrentOrderCycle(state, order)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже перешел в новый цикл статуса");
            return;
        }

        String targetStatus = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_TARGET_STATUS,
                DEFAULT_PAYMENT_OVERDUE_TARGET_STATUS
        );
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_LIVE_ENABLED, false)) {
            recordAttempt(
                    state,
                    ScheduledMessageAttemptStatus.SKIPPED,
                    null,
                    "payment_overdue_dry_run",
                    "Автоперевод в \"" + targetStatus + "\" выключен настройкой",
                    "Кандидат на перевод в " + targetStatus + ": заказ #" + order.getId(),
                    0
            );
            state.setLastAttemptAt(nowStorage);
            state.setLastErrorCode("payment_overdue_dry_run");
            state.setLastErrorMessage("Автоперевод в \"" + targetStatus + "\" выключен настройкой");
            state.setLockedUntil(null);
            state.setNextAttemptAt(scheduleAtStorage(nowStorage.plusDays(1)));
            stateRepository.save(state);
            log.info("Payment overdue dry-run orderId={} currentStatus={}", order.getId(), status);
            return;
        }

        try {
            boolean changed = orderStatusTransitionService.changeStatusForOrder(order.getId(), targetStatus);
            if (changed) {
                recordAttempt(state, ScheduledMessageAttemptStatus.SENT, "system", null, null,
                        "Заказ #" + order.getId() + " переведен в " + targetStatus, 0);
                markDone(state, nowStorage, null, null);
            } else {
                registerFailure(state, nowStorage, "status_change_failed", "Статус заказа не изменен", null, 0);
            }
        } catch (Exception e) {
            registerFailure(state, nowStorage, "status_change_exception", readableException(e), null, 0);
        }
    }

    private void sendReviewRecoveryNotice(ScheduledClientMessageState state, Company company, LocalDateTime nowStorage) {
        Long batchId = ReviewRecoveryNoticeScheduler.batchIdFromTargetKey(state.getTargetKey());
        if (batchId == null) {
            disable(state, nowStorage, "review_recovery_batch_missing", "Не удалось определить пачку восстановления");
            return;
        }

        ReviewRecoveryBatch batch = reviewRecoveryBatchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            disable(state, nowStorage, "review_recovery_batch_missing", "Пачка восстановления не найдена");
            return;
        }
        if (batch.getStatus() == ReviewRecoveryBatchStatus.CLIENT_NOTIFIED
                || batch.getStatus() == ReviewRecoveryBatchStatus.ARCHIVED) {
            markDone(state, nowStorage, "review_recovery_already_notified", "Клиент уже отмечен уведомленным");
            return;
        }
        if (batch.getStatus() == ReviewRecoveryBatchStatus.OPEN) {
            postpone(state, scheduleAtStorage(nowStorage.plusHours(reviewRecoveryNoticeRetryDelayHours())),
                    "review_recovery_still_open", "Восстановление еще не завершено");
            return;
        }
        if (batch.getStatus() != ReviewRecoveryBatchStatus.COMPLETED) {
            markDone(state, nowStorage, "review_recovery_status_changed", "Пачка восстановления уже не ждет уведомления");
            return;
        }

        Order order = batch.getOrder();
        if (order == null || order.getId() == null) {
            disable(state, nowStorage, "order_missing", "Заказ для уведомления о восстановлении не найден");
            return;
        }
        if (reviewRecoveryHoldService.shouldSkipClientRecoveryNotice(order)) {
            reviewRecoveryTaskService.markClientNotifiedAutomatically(batch.getId());
            markDone(state, nowStorage, "order_closed", "Заказ закрыт, клиентское уведомление о восстановлении не требуется");
            return;
        }

        String message = reviewRecoveryNoticeText(order);
        boolean sent = sendMessage(state, company, manager(order), message, nowStorage, null);
        if (sent) {
            reviewRecoveryTaskService.markClientNotifiedAutomatically(batch.getId());
            markDone(state, nowStorage, null, null);
            log.info("Review recovery notice sent batchId={} orderId={} stateId={}", batch.getId(), order.getId(), state.getId());
        }
    }

    private boolean sendMessage(
            ScheduledClientMessageState state,
            Company company,
            Manager manager,
            String message,
            LocalDateTime nowStorage,
            Integer nextIntervalDays
    ) {
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)) {
            registerDryRun(state, nowStorage, message, nextIntervalDays);
            return false;
        }

        long startedAt = System.currentTimeMillis();
        ClientMessageSendResult result = messageSender.send(
                company,
                manager == null ? null : manager.getClientId(),
                company.getGroupId(),
                message
        );
        long durationMs = System.currentTimeMillis() - startedAt;

        if (result.sent()) {
            if (isWhatsAppChannel(result.channel())) {
                whatsAppAuthAlertService.notifyRecovered(
                        manager == null ? null : manager.getClientId(),
                        "успешная отправка автоответчика",
                        nowStorage,
                        managerCandidates(company, manager)
                );
            }
            registerSuccess(state, nowStorage, result.channel(), message, durationMs, nextIntervalDays);
            return true;
        } else {
            registerFailure(state, nowStorage, result.errorCode(), result.errorMessage(), message, durationMs, company, manager);
            return false;
        }
    }

    private void movePaymentReminderToReminderStatus(ScheduledClientMessageState state, Order order, LocalDateTime nowStorage) {
        try {
            if (orderStatusTransitionService.changeStatusForOrder(order.getId(), STATUS_REMINDER)) {
                markDone(state, nowStorage, null, null);
                log.info("Payment reminder moved orderId={} to status {}", order.getId(), STATUS_REMINDER);
            }
        } catch (Exception e) {
            log.warn("Payment reminder was sent, but order {} was not moved to {}", order.getId(), STATUS_REMINDER, e);
        }
    }

    private void registerDryRun(
            ScheduledClientMessageState state,
            LocalDateTime nowStorage,
            String message,
            Integer nextIntervalDays
    ) {
        recordAttempt(
                state,
                ScheduledMessageAttemptStatus.SKIPPED,
                null,
                "client_messages_dry_run",
                "Live-отправка выключена настройкой; сообщение не отправлено",
                message,
                0
        );
        state.setLastAttemptAt(nowStorage);
        state.setLastErrorCode(null);
        state.setLastErrorMessage(null);
        state.setConsecutiveFailures(0);
        state.setLockedUntil(null);
        state.setNextAttemptAt(nextNoSendAttemptAt(nowStorage));
        stateRepository.save(state);
        log.info("Scheduled client message dry-run scenario={} target={}", state.getScenario(), state.getTargetKey());
    }

    @Transactional
    public int releaseDryRunMessagesIfLiveEnabled() {
        return releaseDryRunMessagesIfLiveEnabled(LocalDateTime.now(clock));
    }

    private int releaseDryRunMessagesIfLiveEnabled(LocalDateTime nowStorage) {
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)) {
            return 0;
        }
        int released = stateRepository.releaseDryRunStates(nowStorage);
        if (released > 0) {
            log.info("Client messages released dry-run states after live enabled: {}", released);
        }
        return released;
    }

    private void registerSuccess(
            ScheduledClientMessageState state,
            LocalDateTime nowStorage,
            String channel,
            String message,
            long durationMs,
            Integer nextIntervalDays
    ) {
        recordAttempt(state, ScheduledMessageAttemptStatus.SENT, channel, null, null, message, durationMs);
        state.setLastAttemptAt(nowStorage);
        state.setLastSuccessAt(nowStorage);
        state.setLastErrorCode(null);
        state.setLastErrorMessage(null);
        state.setConsecutiveFailures(0);
        state.setSentCount(state.getSentCount() + 1);
        state.setLockedUntil(null);
        state.setNextAttemptAt(nextSuccessAttemptAt(
                state.getScenario(),
                nowStorage,
                nextIntervalDays,
                state.getTargetKey() + ":" + state.getSentCount()
        ));
        stateRepository.save(state);

        String sentAt = nowIrkutsk().toString();
        appSettingService.setString(lastSentSettingKey("ANY"), sentAt);
        if (channel != null && !channel.isBlank()) {
            appSettingService.setString(lastSentSettingKey(channel), sentAt);
        }
        log.info("Scheduled client message sent scenario={} target={} channel={}", state.getScenario(), state.getTargetKey(), channel);
    }

    private void registerFailure(
            ScheduledClientMessageState state,
            LocalDateTime nowStorage,
            String errorCode,
            String errorMessage,
            String message,
            long durationMs
    ) {
        registerFailure(state, nowStorage, errorCode, errorMessage, message, durationMs, null, null);
    }

    private boolean postponeForReviewRecoveryIfNeeded(
            ScheduledClientMessageState state,
            Order order,
            LocalDateTime nowStorage
    ) {
        if (!reviewRecoveryHoldService.shouldPauseClientMessages(order)) {
            return false;
        }
        String message = "У заказа идет восстановление отзывов, клиентский сценарий поставлен на паузу";
        recordAttempt(state, ScheduledMessageAttemptStatus.SKIPPED, null, "review_recovery_active", message, message, 0);
        state.setLastAttemptAt(nowStorage);
        state.setLastErrorCode("review_recovery_active");
        state.setLastErrorMessage(message);
        state.setLockedUntil(null);
        state.setNextAttemptAt(scheduleAtStorage(nowStorage.plusDays(1)));
        stateRepository.save(state);
        return true;
    }

    private void registerFailure(
            ScheduledClientMessageState state,
            LocalDateTime nowStorage,
            String errorCode,
            String errorMessage,
            String message,
            long durationMs,
            Company company,
            Manager manager
    ) {
        String code = hasText(errorCode) ? errorCode : "unknown_error";
        String readable = hasText(errorMessage) ? errorMessage : "Неизвестная ошибка авторассылки";
        recordAttempt(state, ScheduledMessageAttemptStatus.FAILED, null, code, readable, message, durationMs);

        state.setLastAttemptAt(nowStorage);
        state.setLastErrorCode(code);
        state.setLastErrorMessage(limit(readable, 1000));
        state.setConsecutiveFailures(state.getConsecutiveFailures() + 1);
        state.setLockedUntil(null);

        FailureRetryPolicy policy = failureRetryPolicy(code, readable, state.getConsecutiveFailures(), nowStorage);
        if (policy.disable()) {
            state.setStatus(ScheduledMessageStateStatus.DISABLED);
            state.setNextAttemptAt(null);
        } else {
            state.setNextAttemptAt(policy.nextAttemptAt());
        }
        log.warn(
                "Scheduled client message failed scenario={} target={} reason={} message={} status={} nextAttemptAt={}",
                state.getScenario(),
                state.getTargetKey(),
                code,
                readable,
                state.getStatus(),
                state.getNextAttemptAt()
        );

        stateRepository.save(state);
        if (policy.notifyWhatsAppAuth()) {
            Manager targetManager = manager != null ? manager : company == null ? null : company.getManager();
            whatsAppAuthAlertService.notifyAuthIssue(
                    targetManager == null ? null : targetManager.getClientId(),
                    company == null ? null : company.getTitle(),
                    "фоновый автоответчик",
                    code,
                    readable,
                    nowStorage,
                    toIrkutskTime(nextWhatsAppAuthAttemptAt(nowStorage)),
                    managerCandidates(company, manager)
            );
        }
        if (policy.countForMassProtection()) {
            applyMassErrorProtection(nowStorage, code, readable);
        }
    }

    private FailureRetryPolicy failureRetryPolicy(
            String code,
            String readable,
            int consecutiveFailures,
            LocalDateTime nowStorage
    ) {
        if (isWhatsAppAuthUnavailable(code, readable)) {
            return new FailureRetryPolicy(nextWhatsAppAuthAttemptAt(nowStorage), false, true, false);
        }
        if (isWhatsAppFastRetryError(code, readable)) {
            return new FailureRetryPolicy(nextWhatsAppAuthAttemptAt(nowStorage), false, false, false);
        }
        if (isManualFixError(code)) {
            boolean disable = consecutiveFailures >= DEFAULT_NO_SEND_MAX_FAILURES;
            return new FailureRetryPolicy(disable ? null : nextNoSendAttemptAt(nowStorage), disable, false, false);
        }
        if (isOperationalNoSendError(code)) {
            return new FailureRetryPolicy(nextNoSendAttemptAt(nowStorage), false, false, false);
        }
        return new FailureRetryPolicy(nextNoSendAttemptAt(nowStorage), false, false, true);
    }

    private boolean isWhatsAppFastRetryError(String code, String readable) {
        String normalized = normalizedFailureText(code, readable);
        return normalized.contains("whatsapp_not_ready")
                || normalized.contains("not_ready")
                || normalized.contains("client is not ready")
                || normalized.contains("client_unavailable")
                || normalized.contains("http 503")
                || normalized.contains("http_error")
                || normalized.contains("gateway timeout")
                || normalized.contains("connection refused");
    }

    private boolean isWhatsAppAuthUnavailable(String code, String readable) {
        String normalized = normalizedFailureText(code, readable);
        return normalized.contains("authenticated=false")
                || normalized.contains("\"authenticated\":false")
                || normalized.contains("\"authenticated\": false")
                || normalized.contains("state\":\"qr")
                || normalized.contains("\"state\":\"qr\"")
                || normalized.contains("\"state\": \"qr\"")
                || normalized.contains("hasqr=true")
                || normalized.contains("\"hasqr\":true")
                || normalized.contains("\"hasqr\": true")
                || normalized.contains("scan it")
                || normalized.contains("не авториз");
    }

    private boolean isManualFixError(String code) {
        String normalized = code == null ? "" : code.toLowerCase(Locale.ROOT);
        return normalized.equals("whatsapp_group_missing")
                || normalized.equals("telegram_group_missing")
                || normalized.equals("max_group_missing")
                || normalized.equals("chat_platform_unknown")
                || normalized.equals("whatsapp_client_missing")
                || normalized.equals("unknown_client")
                || normalized.equals("missing_client")
                || normalized.equals("empty_client_url")
                || normalized.equals("missing_group_id")
                || normalized.equals("message_empty")
                || normalized.equals("missing_message");
    }

    private boolean isOperationalNoSendError(String code) {
        String normalized = code == null ? "" : code.toLowerCase(Locale.ROOT);
        return normalized.contains("payment_instruction")
                || normalized.contains("status_change")
                || normalized.contains("auto_archive")
                || normalized.contains("auto_ban");
    }

    private String normalizedFailureText(String code, String readable) {
        return ((code == null ? "" : code) + " " + (readable == null ? "" : readable))
                .toLowerCase(Locale.ROOT);
    }

    private List<Manager> managerCandidates(Company company, Manager manager) {
        Manager targetManager = manager != null ? manager : company == null ? null : company.getManager();
        return targetManager == null ? List.of() : List.of(targetManager);
    }

    private boolean isWhatsAppChannel(String channel) {
        return channel != null && "whatsapp".equals(channel.trim().toLowerCase(Locale.ROOT));
    }

    private void markDone(ScheduledClientMessageState state, LocalDateTime nowStorage, String code, String message) {
        if (hasText(code) || hasText(message)) {
            recordAttempt(state, ScheduledMessageAttemptStatus.SKIPPED, null, code, message, message, 0);
        }
        state.setStatus(ScheduledMessageStateStatus.DONE);
        state.setLastAttemptAt(nowStorage);
        state.setLastErrorCode(null);
        state.setLastErrorMessage(null);
        state.setConsecutiveFailures(0);
        state.setNextAttemptAt(null);
        state.setLockedUntil(null);
        stateRepository.save(state);
    }

    private void disable(ScheduledClientMessageState state, LocalDateTime nowStorage, String code, String message) {
        recordAttempt(state, ScheduledMessageAttemptStatus.FAILED, null, code, message, message, 0);
        state.setStatus(ScheduledMessageStateStatus.DISABLED);
        state.setLastAttemptAt(nowStorage);
        state.setLastErrorCode(code);
        state.setLastErrorMessage(limit(message, 1000));
        state.setNextAttemptAt(null);
        state.setLockedUntil(null);
        stateRepository.save(state);
    }

    private void postpone(ScheduledClientMessageState state, LocalDateTime nextAttemptAt, String code, String message) {
        state.setNextAttemptAt(nextAttemptAt);
        state.setLastErrorCode(code);
        state.setLastErrorMessage(limit(message, 1000));
        state.setLockedUntil(null);
        stateRepository.save(state);
    }

    private void recordAttempt(
            ScheduledClientMessageState state,
            ScheduledMessageAttemptStatus status,
            String channel,
            String errorCode,
            String errorMessage,
            String message,
            long durationMs
    ) {
        attemptRepository.save(ScheduledClientMessageAttempt.builder()
                .stateId(state.getId())
                .scenario(state.getScenario())
                .targetType(state.getTargetType())
                .targetKey(state.getTargetKey())
                .companyId(state.getCompanyId())
                .orderId(state.getOrderId())
                .archiveOrderId(state.getArchiveOrderId())
                .status(status)
                .channel(channel)
                .errorCode(errorCode)
                .errorMessage(limit(errorMessage, 1000))
                .messagePreview(limit(message, 500))
                .durationMs(durationMs)
                .build());
    }

    private Company resolveCompany(ScheduledClientMessageState state) {
        Long companyId = state.getCompanyId();
        if (companyId == null && state.getOrderId() != null) {
            companyId = orderRepository.findByIdForMutation(state.getOrderId())
                    .map(Order::getCompany)
                    .map(Company::getId)
                    .orElse(null);
        }
        if (companyId == null) {
            return null;
        }
        Long resolvedCompanyId = companyId;
        return companyRepository.findByIdForCompanyDto(resolvedCompanyId)
                .or(() -> companyRepository.findById(resolvedCompanyId))
                .orElse(null);
    }

    private String clientTextReminderText(Order order) {
        return renderOrderTemplate(
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_TEXT,
                        DEFAULT_CLIENT_TEXT_REMINDER_TEXT
                ),
                order,
                Map.of()
        );
    }

    private String reviewCheckReminderText(Order order) {
        return reviewCheckMessageBuilder.reviewCheckMessage(order);
    }

    private String paymentReminderText(Order order) {
        String template = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_TEXT,
                DEFAULT_PAYMENT_REMINDER_TEXT
        );
        String managerPayText = managerPayText(order);
        String paymentInstruction = managerPayText;
        String paymentLink = "";
        String tbankPaymentCopyText = "";
        if (requiresTbankPaymentLink(template)) {
            ManagerPaymentLinkResponse link = createTbankPaymentLink(order);
            paymentLink = link.url();
            tbankPaymentCopyText = link.copyText();
            if (usesTbankPaymentInstructionSource() && isDefaultPaymentReminderTemplate(template)) {
                return tbankPaymentCopyText;
            }
            if (usesTbankPaymentInstructionSource()) {
                paymentInstruction = link.instructionText();
            }
        }

        return renderOrderTemplate(
                template,
                order,
                Map.of(
                        "managerPayText", paymentInstruction,
                        "legacyManagerPayText", managerPayText,
                        "paymentInstruction", paymentInstruction,
                        "paymentLink", paymentLink,
                        "tbankPaymentLink", paymentLink,
                        "tbankPaymentCopyText", tbankPaymentCopyText
                )
        );
    }

    private String reviewRecoveryNoticeText(Order order) {
        return renderOrderTemplate(
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_TEXT,
                        DEFAULT_REVIEW_RECOVERY_NOTICE_TEXT
                ),
                order,
                Map.of()
        );
    }

    private String managerPayText(Order order) {
        return order.getManager() != null && hasText(order.getManager().getPayText())
                ? order.getManager().getPayText().trim()
                : "Здравствуйте, напоминаем об оплате выполненного заказа. Пришлите чек, пожалуйста, как оплатите.";
    }

    private boolean requiresTbankPaymentLink(String template) {
        return usesTbankPaymentInstructionSource()
                || containsVariable(template, "paymentLink")
                || containsVariable(template, "tbankPaymentLink")
                || containsVariable(template, "tbankPaymentCopyText");
    }

    private boolean isDefaultPaymentReminderTemplate(String template) {
        return DEFAULT_PAYMENT_REMINDER_TEXT.equals(template);
    }

    private boolean containsVariable(String template, String variable) {
        return template != null && template.contains("{" + variable + "}");
    }

    private ManagerPaymentLinkResponse createTbankPaymentLink(Order order) {
        try {
            return paymentLinkService.createForOrder(order.getId());
        } catch (Exception e) {
            throw new PaymentInstructionException("Не удалось подготовить ссылку T-Bank для заказа #"
                    + order.getId() + ": " + readableException(e), e);
        }
    }

    private String archiveOfferText(Company company) {
        String title = company.getTitle() == null || company.getTitle().isBlank() ? "вашей компании" : company.getTitle();
        return renderTemplate(
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_ARCHIVE_OFFER_TEXT,
                        DEFAULT_ARCHIVE_OFFER_TEXT
                ),
                Map.of("company", title)
        );
    }

    private String companyAndFilial(Order order) {
        String companyTitle = order.getCompany() == null || order.getCompany().getTitle() == null
                ? "Компания"
                : order.getCompany().getTitle();
        String filialTitle = order.getFilial() == null || order.getFilial().getTitle() == null
                ? ""
                : ". " + order.getFilial().getTitle();
        return companyTitle + filialTitle;
    }

    private Manager manager(Order order) {
        return order.getManager() != null ? order.getManager() : order.getCompany() == null ? null : order.getCompany().getManager();
    }

    private String statusTitle(Order order) {
        return order.getStatus() == null || order.getStatus().getTitle() == null ? "" : order.getStatus().getTitle();
    }

    private String companyStatusTitle(Company company) {
        return company.getStatus() == null || company.getStatus().getTitle() == null ? "" : company.getStatus().getTitle();
    }

    private LocalDateTime nextSuccessAttemptAt(
            ClientMessageScenario scenario,
            LocalDateTime nowStorage,
            Integer nextIntervalDays,
            String jitterSeed
    ) {
        if (scenario == ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION
                || scenario == ClientMessageScenario.REVIEW_CHECK_AUTO_ARCHIVE
                || scenario == ClientMessageScenario.BAD_REVIEW_AUTO_BAN
                || scenario == ClientMessageScenario.REVIEW_RECOVERY_NOTICE) {
            return null;
        }
        if (scenario == ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY) {
            return scheduleAtStorage(nowStorage.plusHours(reviewCheckRetryDelayHours()));
        }
        if (scenario == ClientMessageScenario.PAYMENT_INVOICE_RETRY) {
            return scheduleAtStorage(nowStorage.plusHours(paymentInvoiceRetryDelayHours()));
        }
        if (scenario == ClientMessageScenario.BAD_REVIEW_INVOICE) {
            return scheduleAtStorage(nowStorage.plusHours(badReviewInvoiceRetryDelayHours()));
        }
        if (scenario == ClientMessageScenario.ARCHIVE_REORDER_OFFER) {
            return archiveReorderAttemptAt(nowStorage.plusMonths(archiveReorderMonths()), jitterSeed);
        }
        return scheduleAtStorage(nowStorage.plusDays(nextIntervalDays == null ? DEFAULT_REMINDER_INTERVAL_DAYS : nextIntervalDays));
    }

    private LocalDateTime nextNoSendAttemptAt(LocalDateTime nowStorage) {
        return scheduleAtStorage(nowStorage.plusDays(DEFAULT_NO_SEND_RETRY_DAYS));
    }

    private LocalDateTime nextWhatsAppAuthAttemptAt(LocalDateTime nowStorage) {
        return scheduleAtStorage(nowStorage.plusHours(whatsAppAuthRetryHours()));
    }

    private boolean withinDailyLimit(LocalDateTime nowStorage) {
        int limit = intSetting(AppSettingService.CLIENT_MESSAGES_DAILY_LIMIT, DEFAULT_DAILY_LIMIT, 1, 5000);
        LocalDate irkutskToday = nowIrkutsk().toLocalDate();
        LocalDateTime dayStart = toStorageTime(irkutskToday.atStartOfDay());
        return attemptRepository.countClientSentSince(ScheduledMessageAttemptStatus.SENT, dayStart) < limit;
    }

    private int gapSeconds(String expectedChannel) {
        String normalized = expectedChannel == null ? "ANY" : expectedChannel.toUpperCase(Locale.ROOT);
        if ("WHATSAPP".equals(normalized)) {
            return intSetting(AppSettingService.CLIENT_MESSAGES_WHATSAPP_GAP_SECONDS, DEFAULT_WHATSAPP_GAP_SECONDS, 30, 86400);
        }
        if ("TELEGRAM".equals(normalized)) {
            return intSetting(AppSettingService.CLIENT_MESSAGES_TELEGRAM_GAP_SECONDS, DEFAULT_TELEGRAM_GAP_SECONDS, 30, 86400);
        }
        if ("MAX".equals(normalized)) {
            return intSetting(AppSettingService.CLIENT_MESSAGES_MAX_GAP_SECONDS, DEFAULT_MAX_GAP_SECONDS, 30, 86400);
        }
        return intSetting(AppSettingService.CLIENT_MESSAGES_DEFAULT_GAP_SECONDS, DEFAULT_DEFAULT_GAP_SECONDS, 30, 86400);
    }

    private String expectedChannel(Company company) {
        String value = company.getUrlChat();
        if (!hasText(value)) {
            return "ANY";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("^(?:https?://)?chat\\.whatsapp\\.com/.+")) {
            return "WhatsApp";
        }
        if (normalized.matches("^(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/.+")
                || normalized.startsWith("tg://resolve?")) {
            return "Telegram";
        }
        if (normalized.matches("^(?:https?://)?(?:web\\.)?max\\.ru/.+")) {
            return "MAX";
        }
        return "ANY";
    }

    private LocalDateTime lastSentAtIrkutsk(String channel) {
        LocalDateTime channelValue = parseLocalDateTime(appSettingService.getString(lastSentSettingKey(channel), null));
        return channelValue != null
                ? channelValue
                : parseLocalDateTime(appSettingService.getString(lastSentSettingKey("ANY"), null));
    }

    private String lastSentSettingKey(String channel) {
        return "client.messages.last-sent-at." + (hasText(channel) ? channel : "ANY");
    }

    private LocalDateTime scheduleAtStorage(LocalDateTime desiredStorageTime) {
        LocalDateTime desiredIrkutsk = toIrkutskTime(desiredStorageTime);
        LocalDateTime allowedIrkutsk = slotPlanner.nextAllowedAt(desiredIrkutsk, businessWindows());
        return toStorageTime(allowedIrkutsk);
    }

    private LocalDateTime nextBusinessDayStartStorage(LocalDateTime nowIrkutsk) {
        LocalDateTime tomorrowStart = nowIrkutsk.toLocalDate().plusDays(1).atTime(10, 0);
        return toStorageTime(slotPlanner.nextAllowedAt(tomorrowStart, businessWindows()));
    }

    private LocalDateTime toIrkutskTime(LocalDateTime storageTime) {
        ZoneId storageZone = clock.getZone();
        return storageTime.atZone(storageZone)
                .withZoneSameInstant(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                .toLocalDateTime();
    }

    private LocalDateTime toStorageTime(LocalDateTime irkutskTime) {
        ZoneId storageZone = clock.getZone();
        return irkutskTime.atZone(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                .withZoneSameInstant(storageZone)
                .toLocalDateTime();
    }

    private LocalDateTime nowIrkutsk() {
        return ZonedDateTime.now(clock)
                .withZoneSameInstant(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                .toLocalDateTime()
                .withNano(0);
    }

    private int clientTextReminderIntervalDays() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_INTERVAL_DAYS,
                DEFAULT_CLIENT_TEXT_REMINDER_INTERVAL_DAYS,
                1,
                365
        );
    }

    private int reviewCheckIntervalDays() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS,
                DEFAULT_REMINDER_INTERVAL_DAYS,
                1,
                365
        );
    }

    private int paymentReminderIntervalDays() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_INTERVAL_DAYS,
                DEFAULT_REMINDER_INTERVAL_DAYS,
                1,
                365
        );
    }

    private int reviewCheckAutoArchiveDays() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_AUTO_ARCHIVE_DAYS,
                DEFAULT_REVIEW_CHECK_AUTO_ARCHIVE_DAYS,
                1,
                3650
        );
    }

    private int reviewCheckRetryDelayHours() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_RETRY_DELAY_HOURS,
                DEFAULT_REVIEW_CHECK_RETRY_DELAY_HOURS,
                1,
                168
        );
    }

    private int paymentInvoiceRetryDelayHours() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INVOICE_RETRY_DELAY_HOURS,
                DEFAULT_PAYMENT_INVOICE_RETRY_DELAY_HOURS,
                1,
                168
        );
    }

    private int badReviewInvoiceRetryDelayHours() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_RETRY_DELAY_HOURS,
                DEFAULT_BAD_REVIEW_INVOICE_RETRY_DELAY_HOURS,
                1,
                168
        );
    }

    private int reviewRecoveryNoticeRetryDelayHours() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_RETRY_DELAY_HOURS,
                DEFAULT_REVIEW_RECOVERY_NOTICE_RETRY_DELAY_HOURS,
                1,
                168
        );
    }

    private int badReviewAutoBanDelayDays() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_DELAY_DAYS,
                DEFAULT_BAD_REVIEW_AUTO_BAN_DELAY_DAYS,
                1,
                365
        );
    }

    private int whatsAppAuthRetryHours() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_WHATSAPP_AUTH_RETRY_HOURS,
                DEFAULT_WHATSAPP_AUTH_RETRY_HOURS,
                1,
                48
        );
    }

    private int archiveReorderMonths() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_MONTHS,
                DEFAULT_ARCHIVE_REORDER_MONTHS,
                1,
                36
        );
    }

    private int archiveReorderJitterDays() {
        return intSetting(
                AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_JITTER_DAYS,
                DEFAULT_ARCHIVE_REORDER_JITTER_DAYS,
                0,
                30
        );
    }

    LocalDateTime archiveReorderAttemptAt(LocalDateTime baseAt, String seed) {
        int maxJitterDays = archiveReorderJitterDays();
        if (maxJitterDays <= 0) {
            return scheduleAtStorage(baseAt);
        }
        int offsetDays = Math.floorMod(Objects.hashCode(seed), maxJitterDays + 1);
        return scheduleAtStorage(baseAt.plusDays(offsetDays));
    }

    private int candidateLimit() {
        return intSetting(AppSettingService.CLIENT_MESSAGES_CANDIDATE_LIMIT, DEFAULT_CANDIDATE_LIMIT, 1, 5000);
    }

    private int intSetting(String key, int fallback, int min, int max) {
        int value = appSettingService.getInt(key, fallback);
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private String businessWindows() {
        String windows = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        );
        return ClientMessageSlotPlanner.isValidWindowsSpec(windows)
                ? windows
                : ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC;
    }

    private String archiveCompanyStatus() {
        return appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_ARCHIVE_COMPANY_STATUS,
                DEFAULT_ARCHIVE_COMPANY_STATUS
        );
    }

    private boolean usesTbankPaymentInstructionSource() {
        return "TBANK_LINK".equals(paymentInstructionSource());
    }

    private boolean requiresClientMessageSlot(ClientMessageScenario scenario) {
        return scenario != ClientMessageScenario.REVIEW_CHECK_AUTO_ARCHIVE
                && scenario != ClientMessageScenario.BAD_REVIEW_AUTO_BAN;
    }

    private String paymentInstructionSource() {
        String raw = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                DEFAULT_PAYMENT_INSTRUCTION_SOURCE
        );
        String value = (hasText(raw) ? raw : DEFAULT_PAYMENT_INSTRUCTION_SOURCE).trim().toUpperCase(Locale.ROOT);
        return "TBANK_LINK".equals(value) ? "TBANK_LINK" : DEFAULT_PAYMENT_INSTRUCTION_SOURCE;
    }

    private List<String> listSetting(String key, String fallbackCsv) {
        List<String> values = splitCsv(appSettingService.getString(key, fallbackCsv));
        return values.isEmpty() ? splitCsv(fallbackCsv) : values;
    }

    private List<String> closedOrderStatuses() {
        return listSetting(AppSettingService.CLIENT_MESSAGES_CLOSED_ORDER_STATUSES, DEFAULT_CLOSED_ORDER_STATUSES);
    }

    private List<String> badReviewInvoiceTerminalStatuses() {
        return closedOrderStatuses().stream()
                .filter((status) -> !STATUS_NOT_PAID.equals(status))
                .toList();
    }

    private List<String> splitCsv(String value) {
        if (!hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .distinct()
                .toList();
    }

    private String renderOrderTemplate(String template, Order order, Map<String, String> extraVariables) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("company", order.getCompany() == null || order.getCompany().getTitle() == null
                ? "Компания"
                : order.getCompany().getTitle());
        variables.put("filial", order.getFilial() == null || order.getFilial().getTitle() == null
                ? ""
                : order.getFilial().getTitle());
        variables.put("companyAndFilial", companyAndFilial(order));
        variables.put("orderId", order == null || order.getId() == null ? "" : order.getId().toString());
        variables.put("sum", money(payableSum(order)));
        variables.putAll(extraVariables);
        return renderTemplate(template, variables);
    }

    private BigDecimal payableSum(Order order) {
        if (order == null) {
            return BigDecimal.ZERO;
        }
        try {
            return badReviewTaskService.getPayableSum(order);
        } catch (RuntimeException e) {
            log.warn("Не удалось посчитать сумму с плохими задачами для автонапоминания, orderId={}",
                    order.getId(), e);
            return order.getSum() == null ? BigDecimal.ZERO : order.getSum();
        }
    }

    private String renderTemplate(String template, Map<String, String> variables) {
        String result = hasText(template) ? template : "";
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result.trim();
    }

    private String orderTargetKey(Long orderId, LocalDateTime statusChangedAt) {
        return "order:" + orderId + ":" + statusChangedAt.withNano(0);
    }

    private String clientTextWaitingTargetKey(Long orderId, LocalDateTime waitingChangedAt) {
        return "client-text:" + orderId + ":" + waitingChangedAt.withNano(0);
    }

    private boolean isCurrentClientTextWaitingCycle(ScheduledClientMessageState state, Order order) {
        return clientTextWaitingTargetKey(order.getId(), clientTextWaitingChangedAt(order)).equals(state.getTargetKey());
    }

    private LocalDateTime clientTextWaitingChangedAt(Order order) {
        if (order.getWaitingForClientChangedAt() != null) {
            return order.getWaitingForClientChangedAt();
        }
        return orderStatusChangedAt(order);
    }

    private boolean isCurrentOrderCycle(ScheduledClientMessageState state, Order order) {
        return orderTargetKey(order.getId(), orderStatusChangedAt(order)).equals(state.getTargetKey());
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
        return LocalDateTime.now(clock);
    }

    private String archiveCompanyTargetKey(Long companyId, LocalDateTime statusChangedAt) {
        return "archive-company:" + companyId + ":" + statusChangedAt.withNano(0);
    }

    private boolean isCurrentArchiveCompanyCycle(ScheduledClientMessageState state, Company company) {
        return archiveCompanyTargetKey(company.getId(), companyStatusChangedAt(company)).equals(state.getTargetKey());
    }

    private LocalDateTime companyStatusChangedAt(Company company) {
        if (company.getStatusChangedAt() != null) {
            return company.getStatusChangedAt();
        }
        if (company.getUpdateStatus() != null) {
            return company.getUpdateStatus().atStartOfDay();
        }
        if (company.getCreateDate() != null) {
            return company.getCreateDate().atStartOfDay();
        }
        return LocalDateTime.now(clock);
    }

    private void applyMassErrorProtection(LocalDateTime nowStorage, String code, String readable) {
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_ENABLED, true)) {
            return;
        }
        LocalDateTime currentPausedUntil = clientMessagesPausedUntil();
        if (currentPausedUntil != null && currentPausedUntil.isAfter(nowStorage)) {
            return;
        }

        int windowMinutes = intSetting(
                AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_WINDOW_MINUTES,
                DEFAULT_ERROR_PROTECTION_WINDOW_MINUTES,
                1,
                1440
        );
        int threshold = intSetting(
                AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_THRESHOLD,
                DEFAULT_ERROR_PROTECTION_THRESHOLD,
                1,
                10000
        );
        long recentFailures = attemptRepository.countByStatusAndAttemptedAtGreaterThanEqual(
                ScheduledMessageAttemptStatus.FAILED,
                nowStorage.minusMinutes(windowMinutes)
        );
        if (recentFailures < threshold) {
            return;
        }

        int cooldownMinutes = intSetting(
                AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_COOLDOWN_MINUTES,
                DEFAULT_ERROR_PROTECTION_COOLDOWN_MINUTES,
                1,
                1440
        );
        LocalDateTime pausedUntil = nowStorage.plusMinutes(cooldownMinutes).withNano(0);
        String reason = "За " + windowMinutes + " мин. накоплено " + recentFailures
                + " ошибок. Последняя: " + code + " - " + readable;
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL, pausedUntil.toString());
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_PAUSE_REASON, limit(reason, 500));
        log.warn("Client messages paused by error protection until={} reason={}", pausedUntil, reason);
    }

    private LocalDateTime clientMessagesPausedUntil() {
        return parseLocalDateTime(appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL, null));
    }

    private boolean clearLegacyDryRunPause(LocalDateTime pausedUntil) {
        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)) {
            return false;
        }
        String reason = appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAUSE_REASON, "");
        if (reason == null || !reason.contains("client_messages_live_disabled")) {
            return false;
        }
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL, "");
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_PAUSE_REASON, "");
        log.info("Client messages legacy dry-run pause cleared pausedUntil={} reason={}", pausedUntil, reason);
        return true;
    }

    private void cleanupOldAttempts(LocalDateTime nowStorage) {
        if (lastCleanupAt != null && lastCleanupAt.plusHours(1).isAfter(nowStorage)) {
            return;
        }
        lastCleanupAt = nowStorage;
        int retentionDays = intSetting(AppSettingService.CLIENT_MESSAGES_RETENTION_DAYS, DEFAULT_RETENTION_DAYS, 1, 3650);
        int deleted = attemptRepository.deleteOlderThan(nowStorage.minusDays(retentionDays));
        if (deleted > 0) {
            log.info("Cleaned old scheduled client message attempts: {}", deleted);
        }
        int deletedStates = stateRepository.deleteTerminalOlderThan(
                List.of(ScheduledMessageStateStatus.DONE, ScheduledMessageStateStatus.DISABLED),
                nowStorage.minusDays(retentionDays)
        );
        if (deletedStates > 0) {
            log.info("Cleaned old scheduled client message states: {}", deletedStates);
        }
    }

    private void logWorkerDisabled(LocalDateTime nowStorage, LocalDateTime nowIrkutsk) {
        if (!shouldLogSummary(nowStorage, 0, 0)) {
            return;
        }
        lastSummaryLogAt = nowStorage;
        log.info(
                "Client messages tick skipped: worker disabled nowIrkutsk={} live={}",
                nowIrkutsk,
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)
        );
    }

    private void logPaused(LocalDateTime nowStorage, LocalDateTime nowIrkutsk, LocalDateTime pausedUntil) {
        if (!shouldLogSummary(nowStorage, 0, 0)) {
            return;
        }
        lastSummaryLogAt = nowStorage;
        log.warn(
                "Client messages tick skipped: paused until={} nowIrkutsk={} reason={}",
                pausedUntil,
                nowIrkutsk,
                appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAUSE_REASON, "Причина не указана")
        );
    }

    private void logTickSummary(
            LocalDateTime nowStorage,
            LocalDateTime nowIrkutsk,
            String windowsSpec,
            boolean windowAllowed,
            ClientMessageReconcileSummary reconcileSummary,
            int dueCount,
            int processedCount
    ) {
        if (!shouldLogSummary(nowStorage, dueCount, processedCount)) {
            return;
        }
        lastSummaryLogAt = nowStorage;
        long activeStates = stateRepository.countByStatus(ScheduledMessageStateStatus.ACTIVE);
        long disabledStates = stateRepository.countByStatus(ScheduledMessageStateStatus.DISABLED);
        log.info(
                "Client messages tick: live={} nowIrkutsk={} windowAllowed={} windows=\"{}\" candidates clientText={} reviewCheck={} reviewCheckAutoArchive={} paymentReminder={} paymentOverdue={} archiveReorder={} states active={} disabled={} due={} processed={}",
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true),
                nowIrkutsk,
                windowAllowed,
                windowsSpec,
                reconcileSummary.clientTextReminderCandidates(),
                reconcileSummary.reviewCheckCandidates(),
                reconcileSummary.reviewCheckAutoArchiveCandidates(),
                reconcileSummary.paymentReminderCandidates(),
                reconcileSummary.paymentOverdueCandidates(),
                reconcileSummary.archiveReorderCandidates(),
                activeStates,
                disabledStates,
                dueCount,
                processedCount
        );
    }

    private boolean shouldLogSummary(LocalDateTime nowStorage, int dueCount, int processedCount) {
        return lastSummaryLogAt == null
                || dueCount > 0
                || processedCount > 0
                || lastSummaryLogAt.plus(SUMMARY_LOG_INTERVAL).isBefore(nowStorage);
    }

    private LocalDateTime parseLocalDateTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String readableException(Exception e) {
        if (e instanceof ResponseStatusException responseStatusException
                && hasText(responseStatusException.getReason())) {
            return responseStatusException.getReason();
        }
        String message = e.getMessage();
        return hasText(message) ? message : e.getClass().getSimpleName();
    }

    private String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record ClientMessageReconcileSummary(
            int clientTextReminderCandidates,
            int reviewCheckCandidates,
            int reviewCheckAutoArchiveCandidates,
            int paymentReminderCandidates,
            int paymentOverdueCandidates,
            int archiveReorderCandidates
    ) {
        private static ClientMessageReconcileSummary empty() {
            return new ClientMessageReconcileSummary(0, 0, 0, 0, 0, 0);
        }
    }

    private record FailureRetryPolicy(
            LocalDateTime nextAttemptAt,
            boolean disable,
            boolean notifyWhatsAppAuth,
            boolean countForMassProtection
    ) {
    }

    private static class PaymentInstructionException extends RuntimeException {
        private PaymentInstructionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
