package com.hunt.otziv.client_messages.service;
import com.hunt.otziv.whatsapp.service.WhatsAppOperationKey;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.dto.ArchiveCompanyMessageCandidate;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.api.PaymentDeadlineNoticeSource;
import com.hunt.otziv.client_messages.api.UncertainClientMessageDelivery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageAttempt;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageAttemptStatus;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ArchiveCompanyMessageCandidateRepository;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageAttemptRepository;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateBatchRepository;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateBatchRepository.StateSeed;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderPaymentMessageBuilder;
import com.hunt.otziv.p_products.status.service.OrderReviewCheckMessageBuilder;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.service.BankPaymentInstructionSource;
import com.hunt.otziv.payments.service.OrderPaymentIntegrityService;
import com.hunt.otziv.payments.service.PaymentIssueReminderService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryHoldService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService.Lease;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.whatsapp.service.WhatsAppAuthAlertService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduledClientMessageService implements PaymentDeadlineNoticeSource, UncertainClientMessageDelivery,
        com.hunt.otziv.client_messages.api.PeriodicReminderRestart,
        com.hunt.otziv.client_messages.api.ReviewReminderClosure {

    private static final ObjectMapper DELIVERY_JSON = new ObjectMapper();


    private static final int REVIEW_RECOVERY_RECHECK_MINUTES = 10;

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
    public static final String DEFAULT_PUBLICATION_PROGRESS_REPORT_TEXT = com.hunt.otziv.config.settings.api.PublicationProgressSettings.DEFAULT_TEMPLATE;
    public static final String DEFAULT_PAYMENT_INSTRUCTION_SOURCE = "MANAGER_TEXT";
    public static final String DEFAULT_PAYMENT_REMINDER_TEXT = "{companyAndFilial}\n\n{managerPayText} К оплате: {sum} руб.";
    public static final String DEFAULT_PAYMENT_LINK_COPY_TEXT = "{companyAndFilial}\n\nЗдравствуйте, ваш заказ выполнен. К оплате: {sum} руб.\n\n{paymentInstruction}\n\n{paymentAfterword}";
    public static final String DEFAULT_PAYMENT_SUCCESS_TEXT = "Оплата прошла успешно.\n\nНовый заказ принят в работу.\n{orderLine}{companyLine}Сумма: {sum}\nСтраница оплаты: {paymentPage}\n\n{receiptText}";
    public static final String DEFAULT_REVIEW_RECOVERY_NOTICE_TEXT = "{companyAndFilial}\n\nВсе отзывы по заказу №{orderId} восстановлены. Продолжаем работу.";
    public static final String DEFAULT_ARCHIVE_OFFER_TEXT = "{company}\n\nЗдравствуйте! Давно не запускали новый заказ. Можем подготовить новую аккуратную серию отзывов и обновить карточку компании. Если актуально, напишите, пожалуйста, сколько отзывов нужно в этот раз?";

    public static final int DEFAULT_REMINDER_INTERVAL_DAYS = 2;
    public static final int DEFAULT_CLIENT_TEXT_REMINDER_INTERVAL_DAYS = 2;
    public static final int DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS = 7;
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
    public static final int DEFAULT_TRANSIENT_RETRY_MINUTES = 15;
    public static final String AUTO_RECOVERED_ERROR_CODE = "client_message_state_auto_recovered";
    public static final int DEFAULT_NO_SEND_MAX_FAILURES = 30;
    public static final int DEFAULT_ERROR_PROTECTION_THRESHOLD = 20;
    public static final int DEFAULT_ERROR_PROTECTION_WINDOW_MINUTES = 10;
    public static final int DEFAULT_ERROR_PROTECTION_COOLDOWN_MINUTES = 60;
    private static final Duration SUMMARY_LOG_INTERVAL = Duration.ofMinutes(5);
    private static final String RECONCILE_LEASE_NAME = "client-messages-reconciliation";
    private static final String STATUS_TO_CHECK = "В проверку";
    private static final String STATUS_IN_CHECK = "На проверке";
    private static final String STATUS_PUBLIC = "Опубликовано";
    private static final String STATUS_TO_PAY = "Выставлен счет";
    private static final String STATUS_NOT_PAID = "Не оплачено";
    private static final String STATUS_PAYMENT = "Оплачено";
    private static final String STATUS_ARCHIVE = "Архив";
    private static final String STATUS_BAN = "Бан";
    private static final String STATUS_REMINDER = "Напоминание";
    private static final String PAYMENT_RETURN_REOPENED_ERROR_CODE = "payment_return_reopened";
    private static final Set<ClientMessageScenario> ORDER_STATUS_AUTOMATION_SCENARIOS = Set.of(
            ClientMessageScenario.REVIEW_CHECK_REMINDER,
            ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY,
            ClientMessageScenario.REVIEW_CHECK_AUTO_ARCHIVE,
            ClientMessageScenario.PAYMENT_INVOICE_RETRY,
            ClientMessageScenario.PAYMENT_REMINDER,
            ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION
    );
    private static final Set<ClientMessageScenario> PAYMENT_AUTOMATION_SCENARIOS = Set.of(
            ClientMessageScenario.PAYMENT_INVOICE_RETRY,
            ClientMessageScenario.PAYMENT_REMINDER,
            ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION
    );

    private final ScheduledClientMessageStateRepository stateRepository;
    private final ScheduledClientMessageStateBatchRepository stateBatchRepository;
    private final ScheduledClientMessageAttemptRepository attemptRepository;
    private final ArchiveCompanyMessageCandidateRepository archiveCandidateRepository;
    private final OrderRepository orderRepository;
    private final CompanyRepository companyRepository;
    private final AppSettingService appSettingService;
    private final ClientChatMessageSender messageSender;
    private final ClientMessageSlotPlanner slotPlanner;
    private final OrderStatusTransitionService orderStatusTransitionService;
    private final OrderStatusNotificationService orderStatusNotificationService;
    private final LegacyOrderMessagePreparationRecovery legacyPreparationRecovery;
    private final OrderPaymentMessageBuilder orderPaymentMessageBuilder;
    private final PaymentLinkService paymentLinkService;
    private final PaymentIssueReminderService paymentIssueReminderService;
    private final OrderReviewCheckMessageBuilder reviewCheckMessageBuilder;
    private final BadReviewTaskService badReviewTaskService;
    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    private final WhatsAppAuthAlertService whatsAppAuthAlertService;
    private final ReviewRecoveryHoldService reviewRecoveryHoldService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final ReviewRecoveryBatchRepository reviewRecoveryBatchRepository;
    private final ObjectProvider<CommonBillingService> commonBillingServiceProvider;
    private final OrderPaymentIntegrityService orderPaymentIntegrityService;
    private final ClientMessageTransactionRunner transactionRunner;
    private final JdbcTemplate jdbcTemplate;
    private final ScheduledDeliveryRecovery deliveryRecovery;
    private final SchedulerLeaseService schedulerLeaseService;
    private final ClientMessageTime messageTime;
    private final com.hunt.otziv.client_chat_control.api.ReminderContactHistory archiveChatHistory;
    private final com.hunt.otziv.u_users.api.AutomationNoticeRecipients archiveNoticeRecipients;
    private final ReviewArchiveNotifications archiveNotifications;
    private final org.springframework.context.ApplicationEventPublisher applicationEvents;
    private final com.hunt.otziv.client_messages.api.OrderManualContactReservation manualContactReservation;
    private final com.hunt.otziv.p_products.api.OrderDeliveryControlContext orderDeliveryContext;
    @Value("${client.messages.reconcile-interval:PT5M}")
    private Duration reconcileInterval;
    @Value("${client.messages.reconcile-lease-duration:PT10M}")
    private Duration reconcileLeaseDuration;
    private LocalDateTime lastReconcileAt;
    private LocalDateTime lastCleanupAt;
    private LocalDateTime lastSummaryLogAt;

    @Override
    @Transactional(readOnly = true)
    public List<Long> approachingIds(LocalDateTime now, LocalDateTime horizon, long afterId, int limit) {
        if (!paymentAttentionEnabled()) return List.of();
        return stateRepository.findApproachingPaymentDueIds(now, horizon, afterId,
                PageRequest.of(0, Math.min(Math.max(limit, 1), 200)));
    }

    @Override
    @Transactional
    public Optional<PaymentDeadlineNoticeSource.Deadline> dueWithin(
            long stateId, LocalDateTime now, LocalDateTime horizon) {
        if (!paymentAttentionEnabled()) return Optional.empty();
        return stateRepository.findByIdForUpdate(stateId)
                .filter(state -> state.getScenario() == ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION)
                .filter(state -> state.getStatus() == ScheduledMessageStateStatus.ACTIVE)
                .filter(state -> state.getOrderId() != null && state.getNextAttemptAt() != null)
                .filter(state -> state.getNextAttemptAt().isAfter(now)
                        && !state.getNextAttemptAt().isAfter(horizon))
                .map(state -> new PaymentDeadlineNoticeSource.Deadline(state.getId(), state.getOrderId()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(long stateId) {
        return stateRepository.findById(stateId)
                .filter(state -> state.getScenario() == ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION)
                .filter(state -> state.getStatus() == ScheduledMessageStateStatus.ACTIVE)
                .isPresent();
    }

    private boolean paymentAttentionEnabled() {
        return appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED, true)
                && appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_LIVE_ENABLED, false)
                && appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, true);
    }

    @Scheduled(
            fixedDelayString = "${client.messages.tick-delay-ms:30000}",
            initialDelayString = "${client.messages.initial-delay-ms:90000}"
    )
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tick() {
        LocalDateTime nowStorage = messageTime.nowStorage();
        LocalDateTime nowIrkutsk = messageTime.nowIrkutsk();
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

        boolean reconcileDue = shouldReconcileCandidates(nowStorage);
        boolean cleanupDue = shouldCleanupOldAttempts(nowStorage);
        ClientMessageReconcileSummary reconcileSummary = ClientMessageReconcileSummary.empty();
        if (reconcileDue) {
            try {
                Optional<ClientMessageReconcileSummary> acquired = reconcileCandidatesWithLease(nowStorage);
                if (acquired.isPresent()) {
                    reconcileSummary = acquired.get();
                    lastReconcileAt = nowStorage;
                }
            } catch (RuntimeException e) {
                log.error("Scheduled client message reconciliation transaction failed", e);
            }
        }
        if (cleanupDue) {
            try {
                transactionRunner.runInNewTransaction(() -> cleanupOldAttempts(nowStorage));
                lastCleanupAt = nowStorage;
            } catch (RuntimeException e) {
                log.error("Scheduled client message cleanup transaction failed", e);
            }
        }
        try {
            transactionRunner.runInNewTransaction(() -> releaseDryRunMessagesIfLiveEnabled(nowStorage));
        } catch (RuntimeException e) {
            log.error("Scheduled client message dry-run release transaction failed", e);
        }
        try {
            transactionRunner.runInNewTransaction(() -> {
                recoverExpiredBadReviewDeliveries(nowStorage);
                stateRepository.releaseExpiredOrdinaryPreparationClaims(nowStorage);
                releaseReenabledBadReviewScenarios(nowStorage);
            });
            recoverOrdinaryDeliveries(nowStorage);
            legacyPreparationRecovery.recoverDue(nowStorage);
        } catch (RuntimeException e) {
            log.error("Bad-review delivery recovery transaction failed", e);
        }

        String windowsSpec = businessWindows();
        if (!slotPlanner.isAllowedNow(nowIrkutsk, windowsSpec)) {
            logTickSummary(nowStorage, nowIrkutsk, windowsSpec, false, reconcileSummary, 0, 0);
            return;
        }

        int batchSize = intSetting(AppSettingService.CLIENT_MESSAGES_TICK_BATCH_SIZE, DEFAULT_TICK_BATCH_SIZE, 1, 100);
        List<Long> dueStateIds = stateRepository.findDueIds(
                ScheduledMessageStateStatus.ACTIVE,
                nowStorage,
                PageRequest.of(0, batchSize)
        );

        int processed = 0;
        for (Long stateId : dueStateIds) {
            LocalDateTime claimNow = messageTime.databaseTimestamp(messageTime.nowStorage());
            LocalDateTime claimedUntil = claimNow.plus(Duration.ofMinutes(DEFAULT_LOCK_MINUTES));
            boolean claimed;
            try {
                claimed = transactionRunner.callInNewTransaction(
                        () -> lockDueState(stateId, claimNow, claimedUntil)
                );
            } catch (RuntimeException e) {
                log.error("Scheduled client message claim transaction failed stateId={}", stateId, e);
                continue;
            }
            if (!claimed) {
                continue;
            }
            try {
                if (isBadReviewInvoiceState(stateId)) {
                    processClaimedBadReviewInvoiceOutsideTransaction(stateId, claimNow, claimedUntil, null);
                } else {
                    processClaimedStateOutsideTransaction(stateId, claimNow, claimedUntil, false);
                }
                processed++;
            } catch (RuntimeException e) {
                log.error("Scheduled client message state transaction rolled back stateId={}", stateId, e);
                quarantineRolledBackState(
                        stateId,
                        claimedUntil,
                        messageTime.databaseTimestamp(messageTime.nowStorage()),
                        e
                );
            }
        }
        logTickSummary(nowStorage, nowIrkutsk, windowsSpec, true, reconcileSummary, dueStateIds.size(), processed);
    }

    @Transactional
    public int releaseReviewRecoveryHold(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return 0;
        }
        return stateRepository.releaseReviewRecoveryHolds(orderId, messageTime.nowStorage());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ManualRetryResult retryNow(Long stateId) {
        if (stateId == null || stateId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная задача автоматизации");
        }
        ScheduledClientMessageState state = stateRepository.findById(stateId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Задача автоматизации не найдена"
                ));
        if (state.getStatus() != ScheduledMessageStateStatus.ACTIVE) {
            return manualRetryResult(state, false);
        }
        if (ClientMessageStateSafety.isLegacyPreparationFailure(state)
                && legacyPreparationRecovery.recover(stateId, messageTime.databaseTimestamp(messageTime.nowStorage()))) {
            state = transactionRunner.callInNewTransaction(() -> stateRepository.findById(stateId).orElseThrow());
        }
        if (ClientMessageStateSafety.blocksAutomaticRearm(state)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ClientMessageStateSafety.isLegacyPreparationFailure(state)
                            ? "Подготовка остановлена: пока недостаточно данных, чтобы подтвердить новый цикл заказа без риска повторной отправки."
                            : "Исход предыдущей отправки не определен. Требуется восстановить подтверждение доставки; автоматический повтор заблокирован."
            );
        }
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED, true)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автоматизация сообщений выключена в настройках"
            );
        }
        boolean reconcilePaymentBeforeRetry = "payment_instruction_failed".equalsIgnoreCase(
                state.getLastErrorCode() == null ? "" : state.getLastErrorCode().trim()
        ) && state.getOrderId() != null;
        LocalDateTime nowStorage = messageTime.databaseTimestamp(messageTime.nowStorage());
        LocalDateTime pausedUntil = clientMessagesPausedUntil();
        if (pausedUntil != null && pausedUntil.isAfter(nowStorage)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автоматизация сообщений приостановлена до " + pausedUntil
            );
        }
        LocalDateTime claimedUntil = nowStorage.plus(Duration.ofMinutes(DEFAULT_LOCK_MINUTES));
        boolean claimed = transactionRunner.callInNewTransaction(
                () -> lockActiveState(stateId, nowStorage, claimedUntil)
        );
        if (!claimed) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Задача уже обрабатывается. Повторите через несколько секунд."
            );
        }

        try {
            if (state.getScenario() == ClientMessageScenario.BAD_REVIEW_INVOICE) {
                processClaimedBadReviewInvoiceOutsideTransaction(
                        state.getId(), nowStorage, claimedUntil, null
                );
            } else {
                processClaimedStateOutsideTransaction(state.getId(), nowStorage, claimedUntil, reconcilePaymentBeforeRetry);
            }
        } catch (RuntimeException e) {
            log.error("Manual client message retry transaction rolled back stateId={}", state.getId(), e);
            quarantineRolledBackState(
                    state.getId(),
                    claimedUntil,
                    messageTime.databaseTimestamp(messageTime.nowStorage()),
                    e
            );
        }
        ScheduledClientMessageState refreshed = stateRepository.findById(state.getId()).orElse(state);
        return manualRetryResult(refreshed, true);
    }

    public enum OrdinaryDeliveryRecoveryResult {
        UNRESOLVED, HISTORY_UNAVAILABLE, HISTORY_CHECKED_NO_RECEIPT, SENT_CONFIRMED, UNSENT_CONFIRMED
    }

    @Transactional(readOnly = true)
    public Optional<PendingReminderRestart> pendingReminderRestart(long stateId, Long managerId) {
        var state = stateRepository.findById(stateId).orElse(null);
        if (state == null || state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                || !Set.of(ClientMessageScenario.REVIEW_CHECK_REMINDER, ClientMessageScenario.PAYMENT_REMINDER).contains(state.getScenario())
                || state.getDeliveryStatus() != null || state.getNextAttemptAt() == null || state.getLastErrorCode() != null)
            return Optional.empty();
        var source = orderDeliveryContext.read(state.getOrderId()).orElse(null);
        if (source == null || !Objects.equals(source.managerId(), managerId)
                || !Objects.equals(state.getTargetKey(), "order:" + source.orderId() + ":" + source.cycleAt().withNano(0)))
            return Optional.empty();
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM client_reminder_restart_audit
                WHERE (subject_type='SCHEDULED' AND subject_id=? AND new_generation=? AND new_generation>0)
                   OR (subject_type='MANUAL' AND reason='periodic_reminder_resumed_after_cancelled_manual'
                     AND JSON_UNQUOTE(JSON_EXTRACT(previous_snapshot,'$.scheduledStateId'))=?
                     AND JSON_UNQUOTE(JSON_EXTRACT(previous_snapshot,'$.scheduledTargetKey'))=?
                     AND JSON_UNQUOTE(JSON_EXTRACT(previous_snapshot,'$.orderGeneration'))=?
                     AND JSON_UNQUOTE(JSON_EXTRACT(previous_snapshot,'$.dispatchGeneration'))=?
                     AND next_attempt_at=?)
                """, Integer.class, stateId, state.getDispatchGeneration(), Long.toString(stateId), state.getTargetKey(),
                Long.toString(source.generation()), Long.toString(state.getDispatchGeneration()), state.getNextAttemptAt());
        if (count == null || count <= 0) return Optional.empty();
        return Optional.of(new PendingReminderRestart(source, state.getNextAttemptAt(), isDeferredReminderGeneration(state)));
    }

    private boolean isDeferredReminderGeneration(ScheduledClientMessageState state) {
        Integer deferred = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM client_reminder_restart_audit
                WHERE subject_type='SCHEDULED' AND subject_id=? AND new_generation=?
                  AND reason='periodic_next_cycle_scheduled_unknown_preserved'
                """, Integer.class, state.getId(), state.getDispatchGeneration());
        return deferred != null && deferred > 0;
    }

    /** Explicit repair of a periodic reminder. Status transitions and invoices never use this path. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<ReminderRestartCheck> checkStaleReminderRestart(long stateId, Long managerId) {
        var state = stateRepository.findById(stateId).orElse(null);
        LocalDateTime now = messageTime.nowStorage();
        if (!stalePeriodicReminder(state, now)) return Optional.empty();
        var order = orderDeliveryContext.read(state.getOrderId()).orElse(null);
        if (order == null || !Objects.equals(managerId, order.managerId())) return Optional.empty();
        var prepared = decodeScheduledDelivery(state);
        if (prepared.orderAction() != null || prepared.recoveryBatchId() != null
                || !Objects.equals(prepared.stateId(), state.getId()) || prepared.scenario() != state.getScenario()
                || !Objects.equals(prepared.token(), state.getDeliveryToken())
                || !Objects.equals(prepared.targetKey(), state.getTargetKey()) || !hasText(prepared.operationId())
                || !Objects.equals(prepared.orderId(), order.orderId())
                || prepared.orderGeneration() != order.generation()
                || !Objects.equals(prepared.orderStatus(), order.status())) return Optional.empty();
        String envelope = state.getDeliveryEnvelope();
        var preparedAt = state.getDeliveryPreparedAt();
        var receipt = messageSender.recoverRecordedOutcomeFromChat(prepared.operationId(), preparedAt);
        // Leave positive receipt finalization to the existing workflow, which also records the contact on its card.
        if (receipt == null || receipt.sent() || !"whatsapp_history_checked_no_receipt".equals(receipt.errorCode()))
            return Optional.empty();
        var reference = new DeliveryReference(prepared.stateId(), prepared.token(), prepared.targetKey(),
                prepared.operationId(), prepared.clientId(), prepared.groupId());
        return Optional.of(new ReminderRestartCheck(reference, envelope, order, preparedAt, messageTime.nowStorage()));
    }

    private boolean stalePeriodicReminder(ScheduledClientMessageState state, LocalDateTime now) {
        return uncertainPeriodicReminder(state, now, now.minusDays(2));
    }

    private boolean uncertainPeriodicReminder(ScheduledClientMessageState state, LocalDateTime now, LocalDateTime cutoff) {
        return state != null && state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                && Set.of(ClientMessageScenario.REVIEW_CHECK_REMINDER, ClientMessageScenario.PAYMENT_REMINDER).contains(state.getScenario())
                && "UNKNOWN".equals(state.getDeliveryStatus()) && hasText(state.getDeliveryEnvelope())
                && state.getDeliveryPreparedAt() != null && !state.getDeliveryPreparedAt().isAfter(cutoff)
                && (state.getLockedUntil() == null || state.getLockedUntil().isBefore(now))
                && (ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN.equals(state.getLastErrorCode())
                    || ClientMessageStateSafety.MANUAL_DELIVERY_HANDOFF_PENDING.equals(state.getLastErrorCode()));
    }

    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<DeferredReminderCheck> checkDeferredReminderRestart(long stateId, Long managerId) {
        var state = stateRepository.findById(stateId).orElse(null);
        LocalDateTime now = messageTime.nowStorage();
        if (!uncertainPeriodicReminder(state, now, now.minusMinutes(5))
                || !Set.of("WhatsApp", "MAX", "Telegram").contains(state.getDeliveryChannel() == null ? "" : state.getDeliveryChannel()))
            return Optional.empty();
        var order = orderDeliveryContext.read(state.getOrderId()).orElse(null);
        if (order == null || !Objects.equals(managerId, order.managerId())) return Optional.empty();
        var prepared = decodeScheduledDelivery(state);
        if (prepared.orderAction() != null || prepared.recoveryBatchId() != null
                || !Objects.equals(prepared.stateId(), state.getId()) || prepared.scenario() != state.getScenario()
                || !Objects.equals(prepared.token(), state.getDeliveryToken())
                || !Objects.equals(prepared.targetKey(), state.getTargetKey()) || !hasText(prepared.operationId())
                || !Objects.equals(prepared.orderId(), order.orderId())
                || prepared.orderGeneration() > order.generation()
                || !Objects.equals(state.getTargetKey(), "order:" + order.orderId() + ":" + order.cycleAt().withNano(0))
                || !Objects.equals(prepared.orderStatus(), order.status())) return Optional.empty();
        // An older lineage may fence the old send, but cannot prevent an independent future occurrence
        // in this same order/status cycle. The current lineage is frozen in the check and locked again on write.
        var receipt = messageSender.recordedOutcome(prepared.operationId());
        // Positive receipts and known failures retain their ordinary finalization/retry path.
        if (receipt == null || receipt.sent() || ClientMessageDelivery.isKnownUnsent(receipt)
                || !messageSender.hasSettledUncertainOperation(prepared.operationId())) return Optional.empty();
        var reference = new DeliveryReference(prepared.stateId(), prepared.token(), prepared.targetKey(),
                prepared.operationId(), prepared.clientId(), prepared.groupId());
        return Optional.of(new DeferredReminderCheck(new ReminderRestartCheck(reference, state.getDeliveryEnvelope(),
                order, state.getDeliveryPreparedAt(), messageTime.nowStorage()), state.getDeliveryChannel()));
    }

    @Override @Transactional(readOnly = true)
    public long reminderRecoveryGeneration() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM client_reminder_restart_audit
                WHERE reason IN ('periodic_next_cycle_scheduled_unknown_preserved',
                  'periodic_invoice_next_cycle_scheduled_unknown_preserved')
                """, Long.class);
        return count == null ? 0 : count;
    }

    @Override public LocalDateTime futurePaymentReminderAt(LocalDateTime from) {
        LocalDateTime base = messageTime.nowStorage();
        if (from != null && from.isAfter(base)) base = from;
        LocalDateTime desired = base.plusDays(Math.max(2, paymentReminderIntervalDays()));
        if (desired.getNano() != 0) desired = desired.withNano(0).plusSeconds(1);
        return scheduleAtStorage(desired);
    }

    @Override @Transactional(readOnly = true)
    public List<RecoveryCandidate> deferredReminderRecoveryCandidates(long afterStateId, int limit) {
        var ids = jdbcTemplate.queryForList("""
                SELECT state_id FROM scheduled_client_message_state
                WHERE state_id>? AND state_status='ACTIVE' AND delivery_status='UNKNOWN'
                  AND scenario IN ('REVIEW_CHECK_REMINDER','PAYMENT_REMINDER') AND order_id IS NOT NULL
                  AND delivery_prepared_at<=? AND (locked_until IS NULL OR locked_until<?)
                ORDER BY state_id LIMIT ?
                """, Long.class, afterStateId, messageTime.nowStorage().minusMinutes(5), messageTime.nowStorage(),
                Math.max(1, Math.min(20, limit)));
        var result = new ArrayList<RecoveryCandidate>();
        for (Long id : ids) {
            var state = stateRepository.findById(id).orElse(null);
            Long managerId = state == null || state.getOrderId() == null ? null
                    : orderDeliveryContext.read(state.getOrderId()).map(source -> source.managerId()).orElse(null);
            // Include orphaned candidates so a bounded scan can advance beyond them.
            result.add(new RecoveryCandidate(id, managerId));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReservedReminderCheck> checkManualReservationReminder(long stateId, Long managerId) {
        var state = stateRepository.findById(stateId).orElse(null);
        LocalDateTime now = messageTime.nowStorage();
        if (!reservedPeriodicReminder(state, now)) return Optional.empty();
        var source = orderDeliveryContext.read(state.getOrderId()).orElse(null);
        if (source == null || !Objects.equals(source.managerId(), managerId)) return Optional.empty();
        var order = orderRepository.findByIdForOrderDto(source.orderId()).orElse(null);
        if (!reservedReminderOrderMatches(state, order) || manager(order) == null
                || !hasText(manager(order).getClientId()) || order.getCompany() == null
                || !hasText(order.getCompany().getGroupId())
                || !Objects.equals(state.getTargetKey(), "order:" + source.orderId() + ":" + source.cycleAt().withNano(0)))
            return Optional.empty();
        return Optional.of(new ReservedReminderCheck(stateId, state.getTargetKey(), state.getScenario().name(), source,
                manager(order).getClientId(), order.getCompany().getGroupId(), state.getDispatchGeneration(),
                state.getSentCount(), state.getLastSuccessAt(), now));
    }

    private boolean reservedPeriodicReminder(ScheduledClientMessageState state, LocalDateTime now) {
        return state != null && state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                && Set.of(ClientMessageScenario.REVIEW_CHECK_REMINDER, ClientMessageScenario.PAYMENT_REMINDER).contains(state.getScenario())
                && "manual_contact_reserved".equals(state.getLastErrorCode())
                && (state.getLockedUntil() == null || state.getLockedUntil().isBefore(now))
                && state.getDeliveryStatus() == null && state.getDeliveryToken() == null
                && state.getDeliveryEnvelope() == null && state.getDeliveryMessage() == null
                && state.getDeliveryChannel() == null && state.getDeliveryTaskId() == null
                && state.getDeliveryPreparedAt() == null;
    }

    private boolean reservedReminderOrderMatches(ScheduledClientMessageState state, Order order) {
        return order != null && !order.isComplete() && order.getPayDay() == null && isCurrentOrderCycle(state, order)
                && (state.getScenario() == ClientMessageScenario.REVIEW_CHECK_REMINDER ? STATUS_IN_CHECK.equals(statusTitle(order))
                    : Set.of(STATUS_TO_PAY, STATUS_REMINDER).contains(statusTitle(order)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LocalDateTime resumeAfterCancelledManualContact(ReservedReminderCheck check, LocalDateTime lastContact) {
        var current = orderDeliveryContext.lock(check.order().orderId()).orElseThrow();
        LocalDateTime now = messageTime.nowStorage();
        Order order = orderRepository.findByIdForMutation(current.orderId()).orElseThrow();
        var state = stateRepository.findByIdForUpdate(check.stateId()).orElseThrow();
        if (!Objects.equals(current, check.order()) || check.checkedAt().isBefore(now.minusMinutes(5))
                || !reservedPeriodicReminder(state, now) || !reservedReminderOrderMatches(state, order)
                || !Objects.equals(state.getOrderId(), current.orderId())
                || !Objects.equals(state.getTargetKey(), check.targetKey())
                || !Objects.equals(state.getScenario().name(), check.scenario())
                || state.getDispatchGeneration() != check.dispatchGeneration() || state.getSentCount() != check.sentCount()
                || !Objects.equals(state.getLastSuccessAt(), check.lastSuccessAt())
                || manager(order) == null || !Objects.equals(manager(order).getClientId(), check.clientId())
                || order.getCompany() == null || !Objects.equals(order.getCompany().getGroupId(), check.groupId())
                || lastContact == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Во время сверки изменился заказ или отправка. Обновите карточку: повторное сообщение не создано.");
        int days = Math.max(2, state.getScenario() == ClientMessageScenario.REVIEW_CHECK_REMINDER
                ? reviewCheckIntervalDays() : paymentReminderIntervalDays());
        LocalDateTime contact = state.getLastSuccessAt() != null && state.getLastSuccessAt().isAfter(lastContact)
                ? state.getLastSuccessAt() : lastContact;
        LocalDateTime desired = contact.plusDays(days);
        if (desired.isBefore(now.plusMinutes(1))) desired = now.plusMinutes(1);
        LocalDateTime next = scheduleAtStorage(desired);
        // There is no scheduled delivery to replace. Keep its generation, confirmed
        // contacts and payment data; the caller audits the cancelled manual command
        // and retires it in this same transaction before either change can commit.
        state.setLastErrorCode(null);
        state.setLastErrorMessage(null);
        state.setConsecutiveFailures(0);
        state.setLockedUntil(null);
        state.setNextAttemptAt(next);
        stateRepository.save(state);
        return next;
    }

    /** Caller holds card/order locks. Atomic with retiring old manual reminder commands. No transport call. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LocalDateTime restartStaleReminder(ReminderRestartCheck check, LocalDateTime lastContact, String actor) {
        return restartPeriodicReminder(check, lastContact, actor, null);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public LocalDateTime restartDeferredReminder(DeferredReminderCheck check, LocalDateTime lastContact, String actor) {
        if (check == null || check.channel() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Исходный канал отправки не подтверждён. Очередь сохранена.");
        return restartPeriodicReminder(check.source(), lastContact, actor, check.channel());
    }

    private LocalDateTime restartPeriodicReminder(ReminderRestartCheck check, LocalDateTime lastContact, String actor,
            String deferredChannel) {
        var current = orderDeliveryContext.lock(check.order().orderId()).orElseThrow();
        LocalDateTime now = messageTime.nowStorage();
        boolean deferred = deferredChannel != null;
        if (!hasText(actor) || !Objects.equals(current, check.order()) || check.checkedAt().isBefore(now.minusMinutes(5)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ изменился во время проверки. Обновите карточку.");
        Order order = orderRepository.findByIdForMutation(current.orderId()).orElseThrow();
        var state = stateRepository.findByIdForUpdate(check.delivery().stateId()).orElseThrow();
        if (!(deferred ? uncertainPeriodicReminder(state, now, now.minusMinutes(5)) : stalePeriodicReminder(state, now))
                || deferred && (!Set.of("WhatsApp", "MAX", "Telegram").contains(deferredChannel)
                    || !Objects.equals(deferredChannel, state.getDeliveryChannel()))
                || !Objects.equals(state.getDeliveryEnvelope(), check.envelope())
                || !Objects.equals(state.getDeliveryPreparedAt(), check.preparedAt())
                || !Objects.equals(state.getDeliveryToken(), check.delivery().token())
                || !Objects.equals(state.getTargetKey(), check.delivery().targetKey())
                || !isCurrentOrderCycle(state, order) || order.isComplete() || order.getPayDay() != null
                || !Objects.equals(check.delivery().clientId(), manager(order).getClientId())
                || order.getCompany() == null || !Objects.equals(check.delivery().groupId(), order.getCompany().getGroupId())
                || !(state.getScenario() == ClientMessageScenario.REVIEW_CHECK_REMINDER ? STATUS_IN_CHECK.equals(statusTitle(order))
                    : Set.of(STATUS_TO_PAY, STATUS_REMINDER).contains(statusTitle(order))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Текущий этап заказа или отправка изменились. Обновите карточку.");
        int days = Math.max(2, state.getScenario() == ClientMessageScenario.REVIEW_CHECK_REMINDER
                ? reviewCheckIntervalDays() : paymentReminderIntervalDays());
        LocalDateTime contact = check.preparedAt();
        // A new periodic occurrence waits a full interval from restoration, even if the old send happened.
        // This path needs no assertion that incomplete channel history proves non-delivery or client silence.
        if (deferred && now.isAfter(contact)) contact = now;
        if (lastContact != null && lastContact.isAfter(contact)) contact = lastContact;
        if (state.getLastSuccessAt() != null && state.getLastSuccessAt().isAfter(contact)) contact = state.getLastSuccessAt();
        LocalDateTime desired = contact.plusDays(days);
        if (deferred && desired.getNano() != 0) desired = desired.withNano(0).plusSeconds(1);
        if (desired.isBefore(now.plusMinutes(1))) desired = now.plusMinutes(1);
        LocalDateTime next = scheduleAtStorage(desired);
        long generation = Math.addExact(state.getDispatchGeneration(), 1);
        jdbcTemplate.update("""
                INSERT INTO client_reminder_restart_audit
                  (request_key,subject_type,subject_id,old_operation_id,actor,reason,previous_snapshot,new_generation,next_attempt_at)
                SELECT ?, 'SCHEDULED', state_id, ?, ?, ?,
                  JSON_OBJECT('deliveryStatus',delivery_status,'envelope',delivery_envelope,'token',delivery_token,
                    'preparedAt',delivery_prepared_at,'errorCode',last_error_code,'sentCount',sent_count,
                    'lastSuccessAt',last_success_at,'generation',dispatch_generation,'channel',delivery_channel,
                    'currentOrderGeneration',?), ?, ?
                FROM scheduled_client_message_state WHERE state_id=?
                """, "repair:" + check.delivery().operationId(), check.delivery().operationId(), actor,
                deferred ? "periodic_next_cycle_scheduled_unknown_preserved" : "periodic_reminder_restarted_after_history_check",
                current.generation(), generation, next, state.getId());
        clearDeliveryPreparation(state);
        state.setDispatchGeneration(generation);
        state.setConsecutiveFailures(0);
        state.setLastErrorCode(null);
        state.setLastErrorMessage(null);
        state.setLockedUntil(null);
        state.setNextAttemptAt(next);
        stateRepository.save(state);
        if (deferred && applicationEvents != null) applicationEvents.publishEvent(
                new com.hunt.otziv.client_messages.api.ClientReminderQueueRestored(messageTime.nowIrkutsk().toLocalDate()));
        return next;
    }

    public record UncertainDeliveryPreview(Long stateId, String operationId, String message,
                                           LocalDateTime preparedAt) { }

    @Override @Transactional(readOnly = true)
    public List<Long> archiveCandidates(long afterId, int limit) {
        return stateRepository.findSilenceArchiveCandidates(afterId, PageRequest.of(0, Math.max(1, Math.min(50,limit))));
    }

    record ArchiveCheck(long stateId, String targetKey, int sentCount, LocalDateTime lastSuccess,
            com.hunt.otziv.p_products.api.OrderDeliveryControlContext.Snapshot order,
            ClientMessageDelivery.Target company, String clientId, String groupId) { }

    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Decision archiveAfterReminders(long stateId) {
        var requested = stateRepository.findById(stateId).orElse(null);
        if (requested == null || requested.getScenario() != ClientMessageScenario.REVIEW_CHECK_REMINDER)
            return new Decision(false,null);
        if (!appSettingService.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_AUTO_ARCHIVE_ENABLED,true))
            return new Decision(false,"Автоархив выключен настройкой.");
        ArchiveCheck check = transactionRunner.callInNewTransaction(() -> archiveCheck(stateId));
        if (check == null) return new Decision(false,"Автоархив ожидает семи подтверждённых напоминаний и окончания срока для ответа; также проверяется текущий этап заказа.");
        // Transport calls precede the write transaction. Missing/partial history never means client silence.
        if (archiveChatHistory.hasReplyBlockingArchive(check.clientId(),check.groupId(),check.lastSuccess()))
            return new Decision(false,"Автоархив приостановлен: в WhatsApp есть ответ клиента, который нужно разобрать.");
        boolean changed = transactionRunner.callInPreparationTransaction(() -> commitReviewArchive(check));
        return new Decision(changed,changed ? "Заказ архивирован; предложение и уведомления записаны в очередь."
                : "Автоархив остановлен: изменились заказ, оплата, отправка или данные ответа клиента.");
    }

    private boolean silenceThresholdReached(ScheduledClientMessageState state, LocalDateTime now) {
        return state != null && state.getScenario() == ClientMessageScenario.REVIEW_CHECK_REMINDER
                && state.getStatus() == ScheduledMessageStateStatus.ACTIVE && state.getSentCount() >= 7
                && state.getDeliveryStatus() == null && !hasText(state.getDeliveryEnvelope())
                && !(state.getDispatchGeneration() > 0 && state.getNextAttemptAt() != null
                    && state.getNextAttemptAt().isAfter(now) && isDeferredReminderGeneration(state))
                && state.getLastSuccessAt() != null
                && !state.getLastSuccessAt().plusDays(Math.max(2,reviewCheckIntervalDays())).isAfter(now)
                && (state.getLockedUntil() == null || !state.getLockedUntil().isAfter(now));
    }

    private ArchiveCheck archiveCheck(long stateId) {
        var state = stateRepository.findById(stateId).orElse(null);
        if (!silenceThresholdReached(state,messageTime.nowStorage())) return null;
        var source = orderDeliveryContext.read(state.getOrderId()).orElse(null);
        Order order = orderRepository.findById(state.getOrderId()).orElse(null);
        if (source == null || order == null || !STATUS_IN_CHECK.equals(statusTitle(order))
                || !isCurrentOrderCycle(state,order) || order.isComplete() || order.getPayDay() != null
                || order.getCounter() > 0 || order.getCompany() == null || manager(order) == null) return null;
        Company company = order.getCompany();
        return new ArchiveCheck(stateId,state.getTargetKey(),state.getSentCount(),state.getLastSuccessAt(),source,
                companyTarget(company),manager(order).getClientId(),company.getGroupId());
    }

    private boolean commitReviewArchive(ArchiveCheck check) {
        var current = orderDeliveryContext.lock(check.order().orderId()).orElse(null);
        if (!Objects.equals(current,check.order()) || manualContactReservation.blocksScheduledContact(check.order().orderId())) return false;
        var state = stateRepository.findByIdForUpdate(check.stateId()).orElse(null);
        if (!silenceThresholdReached(state,messageTime.nowStorage()) || !Objects.equals(state.getTargetKey(),check.targetKey())
                || state.getSentCount() != check.sentCount() || !Objects.equals(state.getLastSuccessAt(),check.lastSuccess())) return false;
        Order order = orderRepository.findByIdForMutation(current.orderId()).orElseThrow();
        if (!STATUS_IN_CHECK.equals(statusTitle(order)) || !isCurrentOrderCycle(state,order) || order.isComplete()
                || order.getPayDay() != null || order.getCounter() > 0 || orderPaymentIntegrityService.hasSettledPaymentEvidence(order)
                || !Objects.equals(check.clientId(),manager(order).getClientId())
                || !Objects.equals(check.groupId(),order.getCompany().getGroupId())
                || archiveChatHistory.hasOpenReply(check.groupId()) || postponeForReviewRecoveryIfNeeded(state,order,messageTime.nowStorage())) return false;
        var billing = commonBillingServiceProvider.getObject();
        var membership = billing.archiveReviewMembershipAfterReminders(order.getId());
        try {
            if (!orderStatusTransitionService.changeStatusForOrder(order.getId(),STATUS_ARCHIVE))
                throw new IllegalStateException("Order archive was not applied");
        } catch (Exception failure) { throw new IllegalStateException("Не удалось перевести заказ в архив",failure); }
        Company company = order.getCompany();
        LocalDateTime offerAt = null;
        String offerStatus;
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_ENABLED,true)) {
            offerStatus = "Предложение нового заказа пока не поставлено: архивная рассылка выключена.";
        } else if (!archiveCompanyStatus().equals(companyStatusTitle(company)) || archiveCandidateRepository.hasArchiveReorderBlocker(
                company.getId(),listSetting(AppSettingService.CLIENT_MESSAGES_ARCHIVE_INACTIVE_ORDER_STATUSES,DEFAULT_ARCHIVE_INACTIVE_ORDER_STATUSES),
                listSetting(AppSettingService.CLIENT_MESSAGES_OPEN_NEXT_ORDER_REQUEST_STATUSES,DEFAULT_OPEN_NEXT_ORDER_REQUEST_STATUSES))) {
            offerStatus = "Предложение нового заказа отложено: у компании есть активный заказ или заявка либо компания ещё не на стопе.";
        } else {
            String key = archiveCompanyTargetKey(company.getId(),companyStatusChangedAt(company));
            offerAt = scheduleOfferAfterReviewArchive(company,key,messageTime.nowStorage());
            offerStatus = offerAt == null
                    ? "Предложение нового заказа отложено: сначала система проверит ранее созданную архивную рассылку. Повтор не создаётся."
                    : "Предложение нового заказа поставлено в очередь на " + messageTime.toIrkutskTime(offerAt)
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + " (Иркутск).";
        }
        archiveNotifications.recordArchive(state.getId(),order.getId(),check.targetKey(),check.sentCount(),
                Map.of("order",check.order(),"lastReminder",check.lastSuccess(),"membership",membership),offerAt,offerStatus);
        String clientText = company.getTitle() + "\n\nЗдравствуйте! Согласование текстов заказа №" + order.getId()
                + " после семи напоминаний не получено. Мы остановили работу и перенесли заказ в архив. "
                + "Если захотите вернуться к заказу или обсудить новый, напишите нам в этот чат.";
        archiveNotifications.enqueue(state.getId(),"client",new ReviewArchiveNotifications.Envelope(
                check.company(),check.clientId(),check.groupId(),clientText,null));
        String staffText = "Заказ №" + order.getId() + " — " + company.getTitle()
                + "\nПереведён в архив после " + check.sentCount() + " подтверждённых напоминаний без согласования.\n"
                + offerStatus + "\nУведомление клиенту поставлено в очередь. Оплата не изменялась.";
        for (Long chat : archiveNoticeRecipients.archiveRecipients(current.managerId()))
            archiveNotifications.enqueue(state.getId(),"staff:" + chat,new ReviewArchiveNotifications.Envelope(null,null,null,staffText,chat));
        markDone(state,messageTime.nowStorage(),null,null);
        return true;
    }

    private LocalDateTime scheduleOfferAfterReviewArchive(Company company, String key, LocalDateTime archivedAt) {
        var existing = stateRepository.findByScenarioAndTargetKeyForUpdate(ClientMessageScenario.ARCHIVE_REORDER_OFFER,key);
        LocalDateTime due = archiveReorderAttemptAt(archivedAt.plusMonths(archiveReorderMonths()),key);
        if (existing.isPresent()) {
            var offer = existing.get();
            if (ClientMessageStateSafety.blocksAutomaticRearm(offer) || offer.getDeliveryStatus() != null
                    || hasText(offer.getDeliveryEnvelope())
                    || offer.getLockedUntil() != null && offer.getLockedUntil().isAfter(archivedAt)) return null;
            applyStateSeed(existing,new StateSeed(ClientMessageScenario.ARCHIVE_REORDER_OFFER,
                    ClientMessageTargetType.ARCHIVE_COMPANY,key,company.getId(),null,null,due));
            if (offer.getStatus() != ScheduledMessageStateStatus.ACTIVE) return null;
            if (offer.getNextAttemptAt() != null && offer.getNextAttemptAt().isAfter(due)) due = offer.getNextAttemptAt();
            offer.setNextAttemptAt(due);
            stateRepository.save(offer);
        } else {
            applyStateSeed(existing,new StateSeed(ClientMessageScenario.ARCHIVE_REORDER_OFFER,
                    ClientMessageTargetType.ARCHIVE_COMPANY,key,company.getId(),null,null,due));
        }
        return due;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasUnknownOutcome(Long stateId) {
        return stateId != null && stateRepository.findById(stateId)
                .map(state -> "UNKNOWN".equals(state.getDeliveryStatus())).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public UncertainClientMessageDelivery.Preview preview(Long stateId) {
        UncertainDeliveryPreview result = uncertainDeliveryPreview(stateId);
        return new UncertainClientMessageDelivery.Preview(result.stateId(), result.operationId(),
                result.message(), result.preparedAt());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UncertainClientMessageDelivery.Preview handoff(Long stateId, String operationId,
                                                            Long actorUserId, Long controlEntityId, String note) {
        UncertainDeliveryPreview result = handoffUncertainOrdinaryDelivery(
                stateId, operationId, actorUserId, controlEntityId, note);
        return new UncertainClientMessageDelivery.Preview(result.stateId(), result.operationId(),
                result.message(), result.preparedAt());
    }

    @Transactional(readOnly = true)
    public UncertainDeliveryPreview uncertainDeliveryPreview(Long stateId) {
        ScheduledClientMessageState state = stateRepository.findById(stateId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Задача отправки не найдена"));
        if (state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                || !"UNKNOWN".equals(state.getDeliveryStatus())
                || !ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN.equals(state.getLastErrorCode())
                || state.getScenario() == ClientMessageScenario.BAD_REVIEW_INVOICE
                || !hasText(state.getDeliveryEnvelope())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Эта отправка не ожидает ручной проверки");
        }
        PreparedScheduledDelivery prepared = decodeScheduledDelivery(state);
        if (!Objects.equals(stateId, prepared.stateId())
                || !Objects.equals(state.getDeliveryToken(), prepared.token())
                || !hasText(prepared.operationId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сохранённая операция отправки не совпадает с задачей");
        }
        return new UncertainDeliveryPreview(stateId, prepared.operationId(), prepared.message(),
                state.getDeliveryPreparedAt());
    }

    /** Human handoff leaves the immutable operation UNKNOWN. It never implies delivery or permission to resend. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UncertainDeliveryPreview handoffUncertainOrdinaryDelivery(Long stateId, String operationId,
                                                                     Long actorUserId, Long controlEntityId,
                                                                     String note) {
        if (actorUserId == null || actorUserId <= 0 || controlEntityId == null || controlEntityId <= 0
                || note == null || note.trim().length() < 10 || note.trim().length() > 400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Укажите результат проверки чата (10–400 символов)");
        }
        UncertainDeliveryPreview preview = uncertainDeliveryPreview(stateId);
        if (!Objects.equals(preview.operationId(), operationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Операция изменилась. Обновите карточку.");
        }
        OrdinaryDeliveryRecoveryResult check = recoverUncertainOrdinaryDelivery(stateId);
        if (check == OrdinaryDeliveryRecoveryResult.HISTORY_UNAVAILABLE) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "История WhatsApp недоступна. Ручная передача пока заблокирована.");
        }
        if (check != OrdinaryDeliveryRecoveryResult.HISTORY_CHECKED_NO_RECEIPT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Квитанция или текущее состояние операции требуют повторной проверки. Обновите карточку.");
        }
        return transactionRunner.callInNewTransaction(() -> {
            ScheduledClientMessageState locked = stateRepository.findByIdForUpdate(stateId).orElseThrow();
            PreparedScheduledDelivery lockedPrepared = decodeScheduledDelivery(locked);
            if (locked.getStatus() != ScheduledMessageStateStatus.ACTIVE
                    || !"UNKNOWN".equals(locked.getDeliveryStatus())
                    || !ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN.equals(locked.getLastErrorCode())
                    || !Objects.equals(locked.getDeliveryToken(), lockedPrepared.token())
                    || !Objects.equals(lockedPrepared.operationId(), operationId)
                    || !Objects.equals(lockedPrepared.message(), preview.message())
                    || !Objects.equals(locked.getDeliveryPreparedAt(), preview.preparedAt())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Операция изменилась во время проверки");
            }
            LocalDateTime now = messageTime.databaseTimestamp(messageTime.nowStorage());
            locked.setLastErrorCode(ClientMessageStateSafety.MANUAL_DELIVERY_HANDOFF_PENDING);
            locked.setLastErrorMessage("Старая отправка осталась без подтверждения. Передана на ручную проверку чата и связи с клиентом; автоматический повтор запрещён.");
            locked.setDeliveryRecoveryCheckedAt(now);
            locked.setLastAttemptAt(now);
            locked.setNextAttemptAt(null);
            locked.setLockedUntil(null);
            stateRepository.save(locked);
            jdbcTemplate.update("""
                    INSERT INTO scheduled_client_message_manual_handoffs
                    (state_id, operation_id, control_entity_id, actor_user_id, checked_at, note, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, stateId, operationId, controlEntityId, actorUserId,
                    now, note.trim(), now);
            return preview;
        });
    }

    /** Reconcile a saved operation without sending. UNKNOWN remains held when proof is unavailable. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrdinaryDeliveryRecoveryResult recoverUncertainOrdinaryDelivery(Long stateId) {
        if (stateId == null || stateId <= 0) return OrdinaryDeliveryRecoveryResult.UNRESOLVED;
        ScheduledClientMessageState snapshot = stateRepository.findById(stateId).orElse(null);
        if (snapshot == null || snapshot.getStatus() != ScheduledMessageStateStatus.ACTIVE
                || snapshot.getScenario() == ClientMessageScenario.BAD_REVIEW_INVOICE
                || !"UNKNOWN".equals(snapshot.getDeliveryStatus())
                || !(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN.equals(snapshot.getLastErrorCode())
                    || ClientMessageStateSafety.MANUAL_DELIVERY_HANDOFF_PENDING.equals(snapshot.getLastErrorCode()))
                || !hasText(snapshot.getDeliveryEnvelope()) || !hasText(snapshot.getDeliveryToken())) {
            return OrdinaryDeliveryRecoveryResult.UNRESOLVED;
        }
        PreparedScheduledDelivery prepared;
        try {
            prepared = decodeScheduledDelivery(snapshot);
        } catch (RuntimeException unreadable) {
            log.warn("Saved delivery cannot be reconciled: stateId={}", stateId);
            return OrdinaryDeliveryRecoveryResult.UNRESOLVED;
        }
        if (!Objects.equals(stateId, prepared.stateId())
                || !Objects.equals(snapshot.getDeliveryToken(), prepared.token())
                || !Objects.equals(snapshot.getTargetKey(), prepared.targetKey())
                || snapshot.getScenario() != prepared.scenario()
                || !Objects.equals(snapshot.getOrderId(), prepared.orderId())
                || !hasText(prepared.operationId())) {
            return OrdinaryDeliveryRecoveryResult.UNRESOLVED;
        }
        ClientMessageSendResult receipt = messageSender.recoverRecordedOutcomeFromChat(
                prepared.operationId(), snapshot.getDeliveryPreparedAt());
        if (receipt != null && "whatsapp_history_unavailable".equals(receipt.errorCode())) {
            return OrdinaryDeliveryRecoveryResult.HISTORY_UNAVAILABLE;
        }
        if (receipt != null && "whatsapp_history_checked_no_receipt".equals(receipt.errorCode())) {
            return OrdinaryDeliveryRecoveryResult.HISTORY_CHECKED_NO_RECEIPT;
        }
        if (receipt == null || !receipt.sent()) {
            return OrdinaryDeliveryRecoveryResult.UNRESOLVED;
        }
        LocalDateTime now = messageTime.databaseTimestamp(messageTime.nowStorage());
        boolean finalized = transactionRunner.callInNewTransaction(
                () -> finalizeScheduledDelivery(prepared, receipt, now, 0));
        if (!finalized) return OrdinaryDeliveryRecoveryResult.UNRESOLVED;
        notifyScheduledDeliveryOutcome(prepared, receipt, now);
        return receipt.sent() ? OrdinaryDeliveryRecoveryResult.SENT_CONFIRMED
                : OrdinaryDeliveryRecoveryResult.UNSENT_CONFIRMED;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<RecoveredBadReviewDeliveryResult> recoverUncertainBadReviewInvoiceDelivery(
            Long stateId,
            boolean chatDeliveryVerified
    ) {
        if (stateId == null || stateId <= 0) {
            return Optional.empty();
        }
        ScheduledClientMessageState snapshot = stateRepository.findById(stateId).orElse(null);
        if (!isRecoverableUncertainBadReviewDelivery(snapshot)) {
            return Optional.empty();
        }
        // The legacy boolean/text match is not operation-bound evidence.
        // Preserve this signature for callers, but require the immutable saved command and receipt.
        if (!"UNKNOWN".equals(snapshot.getDeliveryStatus()) || !hasText(snapshot.getDeliveryEnvelope())
                || !hasText(snapshot.getDeliveryToken())) return Optional.empty();
        PreparedBadReviewDelivery prepared;
        try { prepared = DELIVERY_JSON.readValue(snapshot.getDeliveryEnvelope(), PreparedBadReviewDelivery.class); }
        catch (Exception unreadable) { return Optional.empty(); }
        if (!Objects.equals(stateId, prepared.stateId()) || !Objects.equals(snapshot.getOrderId(), prepared.orderId())
                || !Objects.equals(snapshot.getDeliveryToken(), prepared.deliveryToken())) return Optional.empty();
        ClientMessageSendResult receipt = messageSender.recoverRecordedOutcomeFromChat(
                WhatsAppOperationKey.of("bad-review-invoice-v1", prepared.stateId(), prepared.deliveryToken()),
                snapshot.getDeliveryPreparedAt());
        if (receipt == null || !receipt.sent() || !hasText(receipt.messageId())) return Optional.empty();
        String savedEnvelope = snapshot.getDeliveryEnvelope();
        return transactionRunner.callInNewTransaction(() -> {
            if (prepared.orderId() != null) orderDeliveryContext.lock(prepared.orderId());
            ScheduledClientMessageState state = stateRepository.findByIdForUpdate(stateId).orElse(null);
            if (!isRecoverableUncertainBadReviewDelivery(state) || !"UNKNOWN".equals(state.getDeliveryStatus())
                    || !Objects.equals(state.getDeliveryEnvelope(), savedEnvelope)
                    || !Objects.equals(state.getDeliveryToken(), prepared.deliveryToken())
                    || !Objects.equals(state.getOrderId(), prepared.orderId())) return Optional.empty();
            LocalDateTime now = messageTime.databaseTimestamp(messageTime.nowStorage());
            stateRepository.lockDispatchBudget();
            state.setDeliveryStatus("SENT");
            registerSuccess(state, now, receipt.channel(), prepared.message(), 0, null);
            markDone(state, now, null, null);
            return Optional.of(new RecoveredBadReviewDeliveryResult(stateId, state.getOrderId(), false,
                    "Существующая отправка счёта подтверждена квитанцией исходной операции; повторная отправка не создавалась"));
        });
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reconcileCandidatesNow() {
        LocalDateTime nowStorage = messageTime.nowStorage();
        if (reconcileCandidatesWithLease(nowStorage).isPresent()) {
            lastReconcileAt = nowStorage;
        }
    }

    private Optional<ClientMessageReconcileSummary> reconcileCandidatesWithLease(LocalDateTime nowStorage) {
        Optional<Lease> acquired = schedulerLeaseService.tryAcquire(
                RECONCILE_LEASE_NAME,
                reconcileLeaseDuration
        );
        if (acquired.isEmpty()) {
            log.debug("Client message reconciliation skipped because another instance owns the lease");
            return Optional.empty();
        }
        Lease lease = acquired.get();
        try {
            return Optional.of(transactionRunner.callInNewTransaction(() -> reconcileCandidates(nowStorage)));
        } finally {
            schedulerLeaseService.release(lease);
        }
    }

    @Transactional
    public boolean ensureClientTextReminderForOrder(Order order) {
        if (order == null || !order.isWaitingForClient()) {
            return false;
        }
        String status = statusTitle(order);
        if (!listSetting(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES, DEFAULT_CLIENT_TEXT_REMINDER_STATUSES)
                .contains(status)) {
            return false;
        }

        LocalDateTime waitingChangedAt = clientTextWaitingChangedAt(order);
        String targetKey = clientTextWaitingTargetKey(order.getId(), waitingChangedAt);
        LocalDateTime nextAttemptAt = scheduleAtStorage(messageTime.nowStorage());
        Optional<ScheduledClientMessageState> existing = stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.CLIENT_TEXT_REMINDER,
                targetKey
        );
        if (existing.isPresent()) {
            ScheduledClientMessageState state = existing.get();
            if (ClientMessageStateSafety.blocksAutomaticRearm(state)) {
                log.warn(
                        "Automatic client-text state rearm blocked pending manual review stateId={} code={}",
                        state.getId(),
                        state.getLastErrorCode()
                );
                return false;
            }
            state.setStatus(ScheduledMessageStateStatus.ACTIVE);
            state.setTargetType(ClientMessageTargetType.ORDER);
            state.setCompanyId(order.getCompany() == null ? null : order.getCompany().getId());
            state.setOrderId(order.getId());
            state.setArchiveOrderId(null);
            state.setNextAttemptAt(nextAttemptAt);
            state.setLockedUntil(null);
            state.setLastErrorCode(null);
            state.setLastErrorMessage(null);
            state.setConsecutiveFailures(0);
            stateRepository.save(state);
            return false;
        }

        stateRepository.save(ScheduledClientMessageState.builder()
                .scenario(ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey(targetKey)
                .companyId(order.getCompany() == null ? null : order.getCompany().getId())
                .orderId(order.getId())
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(nextAttemptAt)
                .build());
        return true;
    }

    @Transactional
    public boolean synchronizeClientTextReminderForOrder(Order order) {
        if (order == null || order.getId() == null || order.getId() <= 0) {
            return false;
        }

        String currentTargetKey = order.isWaitingForClient()
                ? clientTextWaitingTargetKey(order.getId(), clientTextWaitingChangedAt(order))
                : null;
        closeObsoleteClientTextReminderStates(order.getId(), currentTargetKey);

        if (!order.isWaitingForClient()) {
            return false;
        }
        return ensureClientTextReminderForOrderOnSchedule(order);
    }

    public LocalDateTime effectiveNextAttemptAt(LocalDateTime nextAttemptAt) {
        if (nextAttemptAt == null) {
            return null;
        }
        LocalDateTime nowStorage = messageTime.nowStorage();
        LocalDateTime desired = nextAttemptAt.isBefore(nowStorage) ? nowStorage : nextAttemptAt;
        return scheduleAtStorage(desired);
    }

    private void closeObsoleteClientTextReminderStates(Long orderId, String currentTargetKey) {
        LocalDateTime nowStorage = messageTime.nowStorage();
        stateRepository.findByOrderIdIn(List.of(orderId)).stream()
                .filter(Objects::nonNull)
                .filter(state -> state.getScenario() == ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .filter(state -> state.getStatus() == ScheduledMessageStateStatus.ACTIVE)
                .filter(state -> currentTargetKey == null || !currentTargetKey.equals(state.getTargetKey()))
                .forEach(state -> markDone(
                        state,
                        nowStorage,
                        "client_text_cycle_changed",
                        currentTargetKey == null
                                ? "Ожидание текста клиента снято"
                                : "Заказ перешел в новый цикл ожидания текста клиента"
                ));
    }

    private void closeObsoleteClientTextReminderStates(Map<Long, String> currentTargetKeys) {
        if (currentTargetKeys == null || currentTargetKeys.isEmpty()) {
            return;
        }
        LocalDateTime nowStorage = messageTime.nowStorage();
        stateRepository.findByOrderIdIn(currentTargetKeys.keySet()).stream()
                .filter(Objects::nonNull)
                .filter(state -> state.getScenario() == ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .filter(state -> state.getStatus() == ScheduledMessageStateStatus.ACTIVE)
                .filter(state -> currentTargetKeys.containsKey(state.getOrderId()))
                .filter(state -> !Objects.equals(
                        currentTargetKeys.get(state.getOrderId()),
                        state.getTargetKey()
                ))
                .forEach(state -> markDone(
                        state,
                        nowStorage,
                        "client_text_cycle_changed",
                        "Заказ перешел в новый цикл ожидания текста клиента"
                ));
    }

    @Transactional
    public Optional<ClientMessageScenario> ensureOrderAutomationForOrder(Order order) {
        Optional<ClientMessageScenario> scenario = orderAutomationScenario(order);
        if (scenario.isEmpty()) {
            return Optional.empty();
        }
        ensureOrderStateNow(scenario.get(), order);
        return scenario;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean ensureClientMessageStateForOrderId(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return false;
        }
        Order order = orderRepository.findByIdForMutation(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        if (ensureClientTextReminderForOrder(order)) {
            return true;
        }
        Optional<ClientMessageScenario> scenario = ensureOrderAutomationForOrder(order);
        return scenario.isPresent();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverMissingClientMessageStateForOrderId(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return false;
        }
        Order order = orderRepository.findByIdForMutation(orderId).orElse(null);
        if (order == null) {
            return false;
        }

        Optional<ScheduledClientMessageState> recoveredState = Optional.empty();
        if (ensureClientTextReminderForOrder(order)) {
            recoveredState = findClientTextReminderState(order);
        }

        if (recoveredState.isEmpty()) {
            Optional<ClientMessageScenario> scenario = ensureOrderAutomationForOrder(order);
            if (scenario.isPresent()) {
                recoveredState = findOrderAutomationState(scenario.get(), order);
            }
        }

        recoveredState.ifPresent(this::recordAutoRecoveredState);
        return recoveredState.isPresent();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean ensureClientMessageStateAfterOrderStatusChange(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return false;
        }
        Order order = orderRepository.findByIdForMutation(orderId).orElse(null);
        if (order == null) {
            return false;
        }

        boolean eligible = synchronizeClientTextReminderForOrder(order);
        return synchronizeOrderAutomationStates(order) || eligible;
    }

    /**
     * Enqueues a one-shot payment reminder for an order reopened after a
     * provider refund/reversal. The order intentionally remains in "Напоминание";
     * this durable state is consumed by the normal retrying client-message worker.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean enqueuePaymentReminderAfterFullReturn(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return false;
        }
        Order order = orderRepository.findByIdForMutation(orderId).orElse(null);
        if (order == null || (!STATUS_REMINDER.equals(statusTitle(order))
                && !STATUS_TO_PAY.equals(statusTitle(order))
                && !STATUS_NOT_PAID.equals(statusTitle(order)))) {
            return false;
        }

        LocalDateTime nowStorage = messageTime.databaseTimestamp(messageTime.nowStorage());
        String targetKey = orderTargetKey(order.getId(), orderStatusChangedAt(order));
        Optional<ScheduledClientMessageState> existing = stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.PAYMENT_REMINDER,
                targetKey
        );
        if (existing.isPresent()) {
            ScheduledClientMessageState state = existing.get();
            if (state.getStatus() == ScheduledMessageStateStatus.DONE) {
                return true;
            }
            state.setStatus(ScheduledMessageStateStatus.ACTIVE);
            state.setTargetType(ClientMessageTargetType.ORDER);
            state.setCompanyId(order.getCompany() == null ? null : order.getCompany().getId());
            state.setOrderId(order.getId());
            state.setNextAttemptAt(nowStorage);
            state.setLockedUntil(null);
            state.setLastErrorCode(PAYMENT_RETURN_REOPENED_ERROR_CODE);
            state.setLastErrorMessage("Заказ снова ожидает оплату после возврата платежа");
            stateRepository.save(state);
            return true;
        }

        stateRepository.save(ScheduledClientMessageState.builder()
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey(targetKey)
                .companyId(order.getCompany() == null ? null : order.getCompany().getId())
                .orderId(order.getId())
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(nowStorage)
                .lastErrorCode(PAYMENT_RETURN_REOPENED_ERROR_CODE)
                .lastErrorMessage("Заказ снова ожидает оплату после возврата платежа")
                .build());
        return true;
    }

    public Optional<ClientMessageScenario> orderAutomationScenario(Order order) {
        if (order == null || order.getId() == null) {
            return Optional.empty();
        }
        String status = statusTitle(order);
        if (STATUS_PUBLIC.equals(status)) {
            return Optional.of(ClientMessageScenario.PAYMENT_INVOICE_RETRY);
        }
        if (STATUS_TO_CHECK.equals(status)) {
            return Optional.of(ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY);
        }
        if (listSetting(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES, DEFAULT_REVIEW_CHECK_STATUSES)
                .contains(status)) {
            return Optional.of(ClientMessageScenario.REVIEW_CHECK_REMINDER);
        }
        if (listSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_STATUSES, DEFAULT_PAYMENT_REMINDER_STATUSES)
                .contains(status)) {
            return Optional.of(ClientMessageScenario.PAYMENT_REMINDER);
        }
        return Optional.empty();
    }

    private Optional<ScheduledClientMessageState> findClientTextReminderState(Order order) {
        if (order == null || order.getId() == null) {
            return Optional.empty();
        }
        return stateRepository.findByScenarioAndTargetKey(
                ClientMessageScenario.CLIENT_TEXT_REMINDER,
                clientTextWaitingTargetKey(order.getId(), clientTextWaitingChangedAt(order))
        );
    }

    private Optional<ScheduledClientMessageState> findOrderAutomationState(ClientMessageScenario scenario, Order order) {
        if (scenario == null || order == null || order.getId() == null) {
            return Optional.empty();
        }
        return stateRepository.findByScenarioAndTargetKey(
                scenario,
                orderTargetKey(order.getId(), orderStatusChangedAt(order))
        );
    }

    private void recordAutoRecoveredState(ScheduledClientMessageState state) {
        if (ClientMessageStateSafety.blocksAutomaticRearm(state)) {
            return;
        }
        recordAttempt(
                state,
                ScheduledMessageAttemptStatus.SKIPPED,
                "system",
                AUTO_RECOVERED_ERROR_CODE,
                "Очередь автоответчика восстановлена автоматически перед показом контроля",
                "Автовосстановление очереди автоответчика",
                0
        );
    }

    private boolean ensureClientTextReminderForOrderOnSchedule(Order order) {
        if (order == null || !order.isWaitingForClient()) {
            return false;
        }
        String status = statusTitle(order);
        if (!listSetting(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES, DEFAULT_CLIENT_TEXT_REMINDER_STATUSES)
                .contains(status)) {
            return false;
        }

        LocalDateTime waitingChangedAt = clientTextWaitingChangedAt(order);
        return ensureState(
                ClientMessageScenario.CLIENT_TEXT_REMINDER,
                ClientMessageTargetType.ORDER,
                clientTextWaitingTargetKey(order.getId(), waitingChangedAt),
                order.getCompany() == null ? null : order.getCompany().getId(),
                order.getId(),
                null,
                scheduleAtStorage(waitingChangedAt.plusDays(clientTextReminderIntervalDays()))
        );
    }

    private boolean ensureOrderStateOnSchedule(ClientMessageScenario scenario, Order order) {
        LocalDateTime statusChangedAt = orderStatusChangedAt(order);
        String targetKey = orderTargetKey(order.getId(), statusChangedAt);
        return ensureState(
                scenario,
                ClientMessageTargetType.ORDER,
                targetKey,
                order.getCompany() == null ? null : order.getCompany().getId(),
                order.getId(),
                null,
                nextAttemptAfterStatusChange(scenario, statusChangedAt)
        );
    }

    private LocalDateTime nextAttemptAfterStatusChange(ClientMessageScenario scenario, LocalDateTime statusChangedAt) {
        LocalDateTime nowStorage = messageTime.nowStorage();
        return switch (scenario) {
            case REVIEW_CHECK_DELIVERY_RETRY -> scheduleAtStorage(nowStorage.plusHours(reviewCheckRetryDelayHours()));
            case PAYMENT_INVOICE_RETRY -> scheduleAtStorage(nowStorage.plusHours(paymentInvoiceRetryDelayHours()));
            case REVIEW_CHECK_REMINDER -> scheduleAtStorage(statusChangedAt.plusDays(reviewCheckIntervalDays()));
            case REVIEW_CHECK_AUTO_ARCHIVE -> scheduleAtStorage(statusChangedAt.plusDays(reviewCheckAutoArchiveDays()));
            case PAYMENT_REMINDER -> scheduleAtStorage(statusChangedAt.plusDays(paymentReminderIntervalDays()));
            case PAYMENT_OVERDUE_ESCALATION -> scheduleAtStorage(statusChangedAt.plusDays(
                    intSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS, DEFAULT_PAYMENT_OVERDUE_DAYS, 1, 365)
            ));
            default -> scheduleAtStorage(nowStorage.plusDays(DEFAULT_REMINDER_INTERVAL_DAYS));
        };
    }

    /**
     * Immediately aligns every status-driven state with the current order cycle.
     * This is deliberately called from the AFTER_COMMIT status listener so a
     * manager's manual transition cancels obsolete notifications without waiting
     * for the next scheduled attempt.
     */
    private boolean synchronizeOrderAutomationStates(Order order) {
        Set<ClientMessageScenario> expectedScenarios = orderAutomationScenarios(order);
        String currentTargetKey = orderTargetKey(order.getId(), orderStatusChangedAt(order));
        LocalDateTime nowStorage = messageTime.nowStorage();
        boolean changed = false;

        for (ScheduledClientMessageState state : stateRepository.findByOrderIdIn(List.of(order.getId()))) {
            if (state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                    || !ORDER_STATUS_AUTOMATION_SCENARIOS.contains(state.getScenario())) {
                continue;
            }
            if (!expectedScenarios.contains(state.getScenario())
                    || !Objects.equals(currentTargetKey, state.getTargetKey())) {
                markDone(
                        state,
                        nowStorage,
                        "order_status_synchronized",
                        "Задача автоответчика закрыта после смены статуса заказа на \"" + statusTitle(order) + "\""
                );
                changed = true;
            }
        }

        for (ClientMessageScenario scenario : expectedScenarios) {
            ensureOrderStateOnSchedule(scenario, order);
            changed = true;
        }
        return changed;
    }

    private Set<ClientMessageScenario> orderAutomationScenarios(Order order) {
        LinkedHashSet<ClientMessageScenario> scenarios = new LinkedHashSet<>();
        String status = statusTitle(order);
        if (STATUS_PUBLIC.equals(status)) {
            scenarios.add(ClientMessageScenario.PAYMENT_INVOICE_RETRY);
        } else if (STATUS_TO_CHECK.equals(status)) {
            if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_ENABLED, true)) {
                scenarios.add(ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY);
            }
        } else if (listSetting(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES, DEFAULT_REVIEW_CHECK_STATUSES)
                .contains(status)) {
            if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_ENABLED, true)) {
                scenarios.add(ClientMessageScenario.REVIEW_CHECK_REMINDER);
            }
            if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_AUTO_ARCHIVE_ENABLED, true)) {
                scenarios.add(ClientMessageScenario.REVIEW_CHECK_AUTO_ARCHIVE);
            }
        } else if (listSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_STATUSES, DEFAULT_PAYMENT_REMINDER_STATUSES)
                .contains(status)) {
            if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, true)) {
                scenarios.add(ClientMessageScenario.PAYMENT_REMINDER);
            }
            if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED, true)) {
                scenarios.add(ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION);
            }
        }

        boolean hasPaymentAutomation = scenarios.contains(ClientMessageScenario.PAYMENT_INVOICE_RETRY)
                || scenarios.contains(ClientMessageScenario.PAYMENT_REMINDER)
                || scenarios.contains(ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION);
        if (hasPaymentAutomation && isManagedByActiveCommonInvoice(order)) {
            scenarios.remove(ClientMessageScenario.PAYMENT_INVOICE_RETRY);
            scenarios.remove(ClientMessageScenario.PAYMENT_REMINDER);
            scenarios.remove(ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION);
        }
        return scenarios;
    }

    private void ensureOrderStateNow(ClientMessageScenario scenario, Order order) {
        LocalDateTime nowStorage = messageTime.nowStorage();
        String targetKey = orderTargetKey(order.getId(), orderStatusChangedAt(order));
        LocalDateTime nextAttemptAt = scheduleAtStorage(nowStorage);
        Optional<ScheduledClientMessageState> existing = stateRepository.findByScenarioAndTargetKeyForUpdate(
                scenario,
                targetKey
        );
        if (existing.isPresent()) {
            ScheduledClientMessageState state = existing.get();
            if (ClientMessageStateSafety.blocksAutomaticRearm(state)) {
                log.warn(
                        "Automatic order state rearm blocked pending manual review stateId={} code={}",
                        state.getId(),
                        state.getLastErrorCode()
                );
                return;
            }
            state.setStatus(ScheduledMessageStateStatus.ACTIVE);
            state.setTargetType(ClientMessageTargetType.ORDER);
            state.setCompanyId(order.getCompany() == null ? null : order.getCompany().getId());
            state.setOrderId(order.getId());
            state.setArchiveOrderId(null);
            state.setNextAttemptAt(nextAttemptAt);
            state.setLockedUntil(null);
            state.setLastErrorCode(null);
            state.setLastErrorMessage(null);
            state.setConsecutiveFailures(0);
            stateRepository.save(state);
            return;
        }

        stateRepository.save(ScheduledClientMessageState.builder()
                .scenario(scenario)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey(targetKey)
                .companyId(order.getCompany() == null ? null : order.getCompany().getId())
                .orderId(order.getId())
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(nextAttemptAt)
                .build());
    }

    private ClientMessageReconcileSummary reconcileCandidates(LocalDateTime nowStorage) {
        int clientTextReminderCandidates = 0;
        int reviewCheckCandidates = 0;
        int reviewCheckAutoArchiveCandidates = 0;
        int paymentInvoiceCandidates = 0;
        int paymentReminderCandidates = 0;
        int paymentOverdueCandidates = 0;
        int closedPaymentAutomationStates = closeInactivePaymentAutomationStates(nowStorage);
        int archiveReorderCandidates = 0;
        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_ENABLED, true)) {
            int intervalDays = clientTextReminderIntervalDays();
            clientTextReminderCandidates = ensureClientTextWaitingStates(
                    listSetting(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES, DEFAULT_CLIENT_TEXT_REMINDER_STATUSES),
                    nowStorage,
                    intervalDays
            );
        }

        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_ENABLED, true)) {
            int intervalDays = reviewCheckIntervalDays();
            reviewCheckCandidates = ensureOrderStates(
                    ClientMessageScenario.REVIEW_CHECK_REMINDER,
                    listSetting(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES, DEFAULT_REVIEW_CHECK_STATUSES),
                    nowStorage,
                    intervalDays
            );
        }
        // Seven confirmed reminders now govern archival; legacy 30-day jobs are retired on their next run.

        paymentInvoiceCandidates = ensurePaymentInvoiceStates(nowStorage);

        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, true)) {
            int intervalDays = paymentReminderIntervalDays();
            paymentReminderCandidates = ensureOrderStates(
                    ClientMessageScenario.PAYMENT_REMINDER,
                    listSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_STATUSES, DEFAULT_PAYMENT_REMINDER_STATUSES),
                    nowStorage,
                    intervalDays
            );
        }

        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED, true)) {
            int overdueDays = intSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS, DEFAULT_PAYMENT_OVERDUE_DAYS, 1, 365);
            paymentOverdueCandidates = ensureOrderStates(
                    ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION,
                    listSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_STATUSES, DEFAULT_PAYMENT_OVERDUE_STATUSES),
                    nowStorage,
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
                paymentInvoiceCandidates,
                paymentReminderCandidates,
                paymentOverdueCandidates,
                closedPaymentAutomationStates,
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

        List<StateSeed> seeds = new ArrayList<>(candidates.size());
        Map<Long, String> currentTargetKeys = new LinkedHashMap<>();
        for (OrderRepository.ClientMessageCandidate order : candidates) {
            LocalDateTime waitingChangedAt = order.getStatusChangedAt();
            LocalDateTime baseDueAt = waitingChangedAt.plusDays(intervalDays);
            String targetKey = clientTextWaitingTargetKey(order.getId(), waitingChangedAt);
            currentTargetKeys.put(order.getId(), targetKey);
            seeds.add(new StateSeed(
                    ClientMessageScenario.CLIENT_TEXT_REMINDER,
                    ClientMessageTargetType.ORDER,
                    targetKey,
                    order.getCompanyId(),
                    order.getId(),
                    null,
                    scheduleAtStorage(baseDueAt)
            ));
        }
        closeObsoleteClientTextReminderStates(currentTargetKeys);
        int affected = stateBatchRepository.upsertAll(seeds);
        if (affected > 0) {
            log.info("Client messages reconciled client-text states affectedRows={} candidates={}",
                    affected, candidates.size());
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
        candidates = excludeActiveCommonInvoiceOrders(scenario, candidates);

        List<StateSeed> seeds = new ArrayList<>(candidates.size());
        for (OrderRepository.ClientMessageCandidate order : candidates) {
            LocalDateTime statusChangedAt = order.getStatusChangedAt();
            LocalDateTime baseDueAt = statusChangedAt.plusDays(intervalDays);
            seeds.add(new StateSeed(
                    scenario,
                    ClientMessageTargetType.ORDER,
                    orderTargetKey(order.getId(), statusChangedAt),
                    order.getCompanyId(),
                    order.getId(),
                    null,
                    scheduleAtStorage(baseDueAt)
            ));
        }
        int affected = ensureStates(seeds);
        if (affected > 0) {
            log.info("Client messages reconciled order states scenario={} affectedRows={} candidates={}",
                    scenario, affected, candidates.size());
        }
        return candidates.size();
    }

    private int closeInactivePaymentAutomationStates(LocalDateTime nowStorage) {
        List<ScheduledClientMessageState> states = stateRepository.findActiveOrderAutomationStatesByOrderStatuses(
                PAYMENT_AUTOMATION_SCENARIOS,
                ScheduledMessageStateStatus.ACTIVE,
                listSetting(AppSettingService.CLIENT_MESSAGES_CLOSED_ORDER_STATUSES, DEFAULT_CLOSED_ORDER_STATUSES),
                PageRequest.of(0, candidateLimit())
        );
        for (ScheduledClientMessageState state : states) {
            markDone(
                    state,
                    nowStorage,
                    "order_closed",
                    "Платежная задача закрыта сверкой: заказ уже не требует платежной авторассылки"
            );
        }
        if (!states.isEmpty()) {
            log.info("Client messages closed inactive payment automation states count={}", states.size());
        }
        return states.size();
    }

    private int ensureArchiveCompanyStates(LocalDateTime nowStorage) {
        int archiveReorderMonths = archiveReorderMonths();
        LocalDateTime cutoff = nowStorage.minusMonths(archiveReorderMonths);
        int pageSize = candidateLimit();
        String companyStatus = archiveCompanyStatus();
        List<String> inactiveOrderStatuses = listSetting(
                AppSettingService.CLIENT_MESSAGES_ARCHIVE_INACTIVE_ORDER_STATUSES,
                DEFAULT_ARCHIVE_INACTIVE_ORDER_STATUSES
        );
        List<String> openNextOrderStatuses = listSetting(
                AppSettingService.CLIENT_MESSAGES_OPEN_NEXT_ORDER_REQUEST_STATUSES,
                DEFAULT_OPEN_NEXT_ORDER_REQUEST_STATUSES
        );
        LocalDateTime afterStatusChangedAt = null;
        Long afterCompanyId = null;
        int affected = 0;
        int candidateCount = 0;

        while (true) {
            List<ArchiveCompanyMessageCandidate> candidates = archiveCandidateRepository.findUnsynchronizedCandidatesAfter(
                    cutoff,
                    pageSize,
                    companyStatus,
                    inactiveOrderStatuses,
                    openNextOrderStatuses,
                    afterStatusChangedAt,
                    afterCompanyId
            );
            if (candidates.isEmpty()) {
                break;
            }

            List<StateSeed> seeds = new ArrayList<>(candidates.size());
            for (ArchiveCompanyMessageCandidate candidate : candidates) {
                String targetKey = archiveCompanyTargetKey(candidate.companyId(), candidate.statusChangedAt());
                seeds.add(new StateSeed(
                        ClientMessageScenario.ARCHIVE_REORDER_OFFER,
                        ClientMessageTargetType.ARCHIVE_COMPANY,
                        targetKey,
                        candidate.companyId(),
                        null,
                        candidate.archiveOrderId(),
                        archiveReorderAttemptAt(candidate.statusChangedAt().plusMonths(archiveReorderMonths), targetKey)
                ));
            }
            affected += stateBatchRepository.upsertAll(seeds);
            candidateCount += candidates.size();

            ArchiveCompanyMessageCandidate lastCandidate = candidates.getLast();
            afterStatusChangedAt = lastCandidate.statusChangedAt();
            afterCompanyId = lastCandidate.companyId();
            if (candidates.size() < pageSize) {
                break;
            }
        }

        if (candidateCount > 0) {
            log.info("Client messages reconciled archive states affectedRows={} candidates={}",
                    affected, candidateCount);
        }
        return candidateCount;
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
        Optional<ScheduledClientMessageState> existing = stateRepository.findByScenarioAndTargetKeyForUpdate(
                scenario,
                targetKey
        );
        return applyStateSeed(existing, new StateSeed(scenario, targetType, targetKey,
                companyId, orderId, archiveOrderId, nextAttemptAt));
    }

    private int ensureStates(List<StateSeed> seeds) {
        if (seeds.isEmpty()) return 0;
        // Same domain transitions as ensureState, with one current read per bounded batch.
        Map<String, StateSeed> unique = new LinkedHashMap<>();
        seeds.forEach(seed -> unique.putIfAbsent(seed.targetKey(), seed));
        List<StateSeed> rows = new ArrayList<>(unique.values());
        int affected = 0;
        for (int offset = 0; offset < rows.size(); offset += 500) {
            List<StateSeed> batch = rows.subList(offset, Math.min(rows.size(), offset + 500));
            Map<String, ScheduledClientMessageState> existing = stateRepository
                    .findByScenarioAndTargetKeyInForUpdate(batch.getFirst().scenario(),
                            batch.stream().map(StateSeed::targetKey).toList()).stream()
                    .collect(java.util.stream.Collectors.toMap(ScheduledClientMessageState::getTargetKey, value -> value));
            for (StateSeed seed : batch) {
                if (applyStateSeed(Optional.ofNullable(existing.get(seed.targetKey())), seed)) affected++;
            }
        }
        return affected;
    }

    private boolean applyStateSeed(Optional<ScheduledClientMessageState> existing, StateSeed seed) {
        ClientMessageScenario scenario = seed.scenario();
        ClientMessageTargetType targetType = seed.targetType();
        String targetKey = seed.targetKey();
        Long companyId = seed.companyId();
        Long orderId = seed.orderId();
        Long archiveOrderId = seed.archiveOrderId();
        LocalDateTime nextAttemptAt = seed.nextAttemptAt();
        if (existing.isPresent()) {
            ScheduledClientMessageState state = existing.get();
            if (state.getStatus() == ScheduledMessageStateStatus.DONE
                    && state.getSentCount() == 0
                    && state.getLastSuccessAt() == null
                    && !ClientMessageStateSafety.blocksAutomaticRearm(state)) {
                state.setStatus(ScheduledMessageStateStatus.ACTIVE);
                state.setTargetType(targetType);
                state.setCompanyId(companyId);
                state.setOrderId(orderId);
                state.setArchiveOrderId(archiveOrderId);
                state.setNextAttemptAt(nextAttemptAt);
                state.setLockedUntil(null);
                state.setLastErrorCode(null);
                state.setLastErrorMessage(null);
                state.setConsecutiveFailures(0);
                stateRepository.save(state);
                return true;
            }
            if (state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                    && state.getNextAttemptAt() == null
                    && !ClientMessageStateSafety.blocksAutomaticRearm(state)) {
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

    private boolean lockDueState(
            Long stateId,
            LocalDateTime nowStorage,
            LocalDateTime claimedUntil
    ) {
        return stateRepository.lockDueState(
                stateId,
                nowStorage,
                claimedUntil,
                ClientMessageStateSafety.TRANSACTION_IN_PROGRESS,
                "Задача захвачена воркером; автоматический повтор при незавершенной транзакции запрещен"
        ) > 0;
    }

    private boolean lockActiveState(
            Long stateId,
            LocalDateTime nowStorage,
            LocalDateTime claimedUntil
    ) {
        return stateRepository.lockActiveState(
                stateId,
                nowStorage,
                claimedUntil,
                ClientMessageStateSafety.TRANSACTION_IN_PROGRESS,
                "Задача захвачена для ручной попытки; повтор до завершения транзакции запрещен"
        ) > 0;
    }

    private boolean isBadReviewInvoiceState(Long stateId) {
        return stateId != null
                && stateRepository.findById(stateId)
                .map(ScheduledClientMessageState::getScenario)
                .filter(ClientMessageScenario.BAD_REVIEW_INVOICE::equals)
                .isPresent();
    }

    /**
     * Immediate completion path. The durable state was inserted in the same
     * transaction as DONE; preparation and finalization below each use their
     * own transaction, while the messenger call runs with no database lock.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliverBadReviewInvoiceImmediately(Long taskId, Long orderId) {
        if (orderId == null || orderId <= 0
                || !appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true)
                || !appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true)
                || !liveSendingEnabled()) {
            return;
        }
        ScheduledClientMessageState state = stateRepository.findByScenarioAndTargetKey(
                ClientMessageScenario.BAD_REVIEW_INVOICE,
                "bad-review-invoice:order:" + orderId
        ).orElse(null);
        if (state == null
                || state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                || ClientMessageStateSafety.blocksAutomaticRearm(state)) {
            return;
        }
        LocalDateTime nowStorage = messageTime.databaseTimestamp(messageTime.nowStorage());
        LocalDateTime claimedUntil = nowStorage.plus(Duration.ofMinutes(DEFAULT_LOCK_MINUTES));
        boolean claimed = transactionRunner.callInNewTransaction(
                () -> lockActiveState(state.getId(), nowStorage, claimedUntil)
        );
        if (claimed) {
            processClaimedBadReviewInvoiceOutsideTransaction(
                    state.getId(), nowStorage, claimedUntil, taskId
            );
        }
    }

    private void processClaimedBadReviewInvoiceOutsideTransaction(
            Long stateId,
            LocalDateTime nowStorage,
            LocalDateTime expectedLockedUntil,
            Long taskId
    ) {
        PreparedBadReviewDelivery prepared = transactionRunner.callInPreparationTransaction(
                () -> prepareBadReviewDelivery(stateId, nowStorage, expectedLockedUntil, taskId)
        );
        if (prepared == null) {
            return;
        }
        requireNoBusinessTransaction();
        if (!liveSendingEnabled()) {
            transactionRunner.runInNewTransaction(() -> pausePreparedWithoutDispatch(prepared.stateId(),
                    prepared.orderId(), prepared.deliveryToken(), ClientMessageScenario.BAD_REVIEW_INVOICE,
                    "bad-review-invoice:order:" + prepared.orderId(), messageTime.databaseTimestamp(messageTime.nowStorage())));
            return;
        }
        ClientMessageSendResult result;
        boolean outcomeUnknown = false;
        long startedAt = System.currentTimeMillis();
        try {
            TelegramTransferCopyButton copyButton = TelegramTransferCopyButton
                    .fromFrozenTransferNumber(prepared.telegramCopyTransferNumber())
                    .orElse(null);
            requireNoBusinessTransaction();
            result = messageSender.deliverWithOperationId(
                    prepared.target(), prepared.managerClientId(), prepared.groupId(),
                    prepared.message(), copyButton,
                    WhatsAppOperationKey.of("bad-review-invoice-v1", prepared.stateId(), prepared.deliveryToken())
            );
            if (result == null) {
                outcomeUnknown = true;
                result = ClientMessageSendResult.failed(
                        ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN,
                        "Канал отправки не вернул результат"
                );
            } else if (!result.sent() && !ClientChatMessageSender.isKnownUnsent(result)) {
                outcomeUnknown = true;
            }
        } catch (RuntimeException e) {
            outcomeUnknown = true;
            result = ClientMessageSendResult.failed(
                    ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN,
                    readableException(e)
            );
        }
        ClientMessageSendResult finalResult = result;
        boolean finalOutcomeUnknown = outcomeUnknown;
        long durationMs = System.currentTimeMillis() - startedAt;
        Long deliveredOrderId = transactionRunner.callInNewTransaction(() -> finalizeBadReviewDelivery(
                prepared,
                finalResult,
                finalOutcomeUnknown,
                messageTime.databaseTimestamp(messageTime.nowStorage()),
                durationMs
        ));
        if (deliveredOrderId != null) {
            transactionRunner.runInNewTransaction(() -> scheduleBadReviewAutoBanAfterDelivery(deliveredOrderId));
        }
    }

    private boolean isRecoverableUncertainBadReviewDelivery(ScheduledClientMessageState state) {
        return state != null && state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                && state.getScenario() == ClientMessageScenario.BAD_REVIEW_INVOICE
                && (ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN.equals(state.getLastErrorCode())
                    || ClientMessageStateSafety.MANUAL_DELIVERY_HANDOFF_PENDING.equals(state.getLastErrorCode()));
    }

    private PreparedBadReviewDelivery prepareBadReviewDelivery(
            Long stateId,
            LocalDateTime nowStorage,
            LocalDateTime expectedLockedUntil,
            Long taskId
    ) {
        ScheduledClientMessageState snapshot = stateRepository.findById(stateId).orElse(null);
        boolean scenarioEnabled = appSettingService.getBoolean(
                AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true);
        Order order = snapshot != null && snapshot.getOrderId() != null
                && scenarioEnabled && !hasText(snapshot.getDeliveryStatus())
                ? orderRepository.findByIdForMutation(snapshot.getOrderId()).orElse(null)
                : null;
        ScheduledClientMessageState state = stateRepository.findByIdForUpdate(stateId).orElse(null);
        if (!ownsClaim(state, expectedLockedUntil)) {
            return null;
        }
        if (hasText(state.getDeliveryStatus())) {
            quarantinePreparedDelivery(state, nowStorage);
            return null;
        }
        if (!scenarioEnabled) {
            postponeScenarioDisabled(state, nowStorage, "bad_review_invoice_disabled",
                    "Отправка счета после дополнительной задачи выключена настройкой");
            return null;
        }
        if (snapshot == null || !Objects.equals(snapshot.getOrderId(), state.getOrderId())) {
            postpone(state, nextNoSendAttemptAt(nowStorage), "delivery_state_changed",
                    "Заказ задания изменился до подготовки отправки");
            return null;
        }
        if (order == null) {
            disable(state, nowStorage, "order_missing",
                    "Заказ для повторной отправки счета после дополнительной задачи не найден");
            return null;
        }
        if (orderPaymentIntegrityService.hasSettledPaymentEvidence(order)) {
            markDone(state, nowStorage, OrderPaymentIntegrityService.SUPPRESSED_ERROR_CODE,
                    "Отправка счета остановлена: по заказу уже есть подтвержденная оплата");
            return null;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return null;
        }
        if (isManagedByActiveCommonInvoice(order)) {
            markDone(state, nowStorage, "common_billing_linked",
                    "Заказ входит в общий счет; одиночный счет не отправляется");
            return null;
        }
        String status = statusTitle(order);
        if (badReviewInvoiceTerminalStatuses().contains(status) || !STATUS_NOT_PAID.equals(status)) {
            markDone(state, nowStorage, "order_status_changed",
                    "Заказ уже не ожидает счет после дополнительной задачи: " + status);
            return null;
        }
        Company company = resolveCompany(state);
        if (company == null) {
            disable(state, nowStorage, "company_missing", "Компания для отправки счета не найдена");
            return null;
        }
        if (taskId == null && !reserveBackgroundDeliverySlot(state, company, nowStorage)) {
            return null;
        }
        PreparedBadReviewDelivery prepared;
        if (hasText(state.getDeliveryEnvelope())) {
            // A pause before dispatch retains the exact invoice/route/token. It
            // must not render a changed payment instruction when live resumes.
            try { prepared = DELIVERY_JSON.readValue(state.getDeliveryEnvelope(), PreparedBadReviewDelivery.class); }
            catch (Exception corrupt) { throw new IllegalStateException("Saved bad-review delivery is unreadable; replay is blocked", corrupt); }
            if (!Objects.equals(prepared.stateId(), state.getId()) || !Objects.equals(prepared.orderId(), state.getOrderId())) {
                throw new IllegalStateException("Saved bad-review delivery belongs to another occurrence");
            }
        } else {
            try {
                OrderPaymentMessageBuilder.PreparedPaymentMessage paymentMessage =
                        orderPaymentMessageBuilder.publishedOrderPaymentMessageWithTransfer(order);
                Manager manager = manager(order);
                prepared = new PreparedBadReviewDelivery(state.getId(), state.getOrderId(), UUID.randomUUID().toString(),
                        companyTarget(company), manager == null ? null : manager.getClientId(), company.getGroupId(),
                        paymentMessage.message(), paymentMessage.telegramCopyTransferNumber());
            } catch (RuntimeException e) {
                registerFailure(state, nowStorage, "payment_instruction_failed", readableException(e), null, 0);
                return null;
            }
        }
        if (!liveSendingEnabled()) {
            registerDryRun(state, nowStorage, prepared.message(), null);
            return null;
        }
        try { state.setDeliveryEnvelope(DELIVERY_JSON.writeValueAsString(prepared)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Bad-review snapshot is not serializable", failure); }
        state.setDeliveryToken(prepared.deliveryToken());
        state.setDeliveryStatus("PREPARED");
        state.setDeliveryMessage(prepared.message());
        state.setDeliveryChannel(expectedChannel(prepared.target()));
        state.setDeliveryTaskId(taskId);
        state.setDeliveryPreparedAt(nowStorage);
        state.setLastErrorCode("delivery_prepared");
        state.setLastErrorMessage("Сообщение подготовлено; внешний вызов выполняется после фиксации транзакции");
        stateRepository.save(state);
        return prepared;
    }

    private Long finalizeBadReviewDelivery(
            PreparedBadReviewDelivery prepared,
            ClientMessageSendResult result,
            boolean outcomeUnknown,
            LocalDateTime nowStorage,
            long durationMs
    ) {
        if (outcomeUnknown) {
            ScheduledClientMessageState state = stateRepository.findByIdForUpdate(prepared.stateId()).orElse(null);
            if (!isPreparedDeliveryState(state, prepared)) {
                return null;
            }
            stateRepository.lockDispatchBudget();
            state.setDeliveryStatus("UNKNOWN");
            state.setLastAttemptAt(nowStorage);
            state.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN);
            state.setLastErrorMessage(limit(
                    "Исход внешней отправки не определен; автоматический повтор остановлен до ручной проверки: "
                            + result.errorMessage(),
                    1000
            ));
            state.setNextAttemptAt(null);
            state.setLockedUntil(null);
            recordAttempt(
                    state,
                    ScheduledMessageAttemptStatus.FAILED,
                    null,
                    ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN,
                    state.getLastErrorMessage(),
                    prepared.message(),
                    durationMs
            );
            stateRepository.save(state);
            return null;
        }

        ScheduledClientMessageState state = stateRepository.findByIdForUpdate(prepared.stateId()).orElse(null);
        if (!isPreparedDeliveryState(state, prepared)) {
            return null;
        }
        stateRepository.lockDispatchBudget();
        if (result.sent()) {
            state.setDeliveryStatus("SENT");
            registerSuccess(
                    state,
                    nowStorage,
                    hasText(result.channel()) ? result.channel() : expectedChannel(prepared.target()),
                    prepared.message(),
                    durationMs,
                    null
            );
            markDone(state, nowStorage, null, null);
            return prepared.orderId();
        }
        clearDeliveryPreparation(state);
        registerFailure(
                state,
                nowStorage,
                hasText(result.errorCode()) ? result.errorCode() : "client_chat_send_failed",
                hasText(result.errorMessage())
                        ? result.errorMessage()
                        : "Счет после дополнительной задачи не отправлен",
                prepared.message(),
                durationMs
        );
        return null;
    }

    private void scheduleBadReviewAutoBanAfterDelivery(Long orderId) {
        if (orderId == null) {
            return;
        }
        Order order = orderRepository.findByIdForMutation(orderId).orElse(null);
        if (order == null || orderPaymentIntegrityService.hasSettledPaymentEvidence(order)) {
            return;
        }
        scheduleBadReviewAutoBanIfReady(order);
    }

    private boolean isPreparedDeliveryState(
            ScheduledClientMessageState state,
            PreparedBadReviewDelivery prepared
    ) {
        return state != null
                && state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                && "PREPARED".equals(state.getDeliveryStatus())
                && Objects.equals(prepared.deliveryToken(), state.getDeliveryToken());
    }

    private boolean ownsClaim(ScheduledClientMessageState state, LocalDateTime expectedLockedUntil) {
        if (state == null
                || state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                || state.getScenario() != ClientMessageScenario.BAD_REVIEW_INVOICE
                || expectedLockedUntil == null) {
            return false;
        }
        LocalDateTime actual = messageTime.databaseTimestamp(state.getLockedUntil());
        return ClientMessageStateSafety.isTransactionInProgress(state)
                && expectedLockedUntil.equals(actual)
                && actual.isAfter(messageTime.databaseTimestamp(messageTime.nowStorage()));
    }

    private void quarantinePreparedDelivery(ScheduledClientMessageState state, LocalDateTime nowStorage) {
        String message = "Ранее подготовленная отправка не была финализирована; проверьте чат клиента вручную";
        state.setDeliveryStatus("UNKNOWN");
        state.setLastAttemptAt(nowStorage);
        state.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN);
        state.setLastErrorMessage(message);
        state.setNextAttemptAt(null);
        state.setLockedUntil(null);
        stateRepository.save(state);
    }

    private void postponeScenarioDisabled(
            ScheduledClientMessageState state,
            LocalDateTime nowStorage,
            String code,
            String message
    ) {
        if (!code.equals(state.getLastErrorCode())) {
            recordAttempt(state, ScheduledMessageAttemptStatus.SKIPPED, "system", code, message, message, 0);
        }
        state.setLastAttemptAt(nowStorage);
        state.setLastErrorCode(code);
        state.setLastErrorMessage(message);
        state.setLockedUntil(null);
        state.setNextAttemptAt(nextNoSendAttemptAt(nowStorage));
        stateRepository.save(state);
    }

    private void clearDeliveryPreparation(ScheduledClientMessageState state) {
        state.setDeliveryToken(null);
        state.setDeliveryStatus(null);
        state.setDeliveryMessage(null);
        state.setDeliveryEnvelope(null);
        state.setDeliveryChannel(null);
        state.setDeliveryTaskId(null);
        state.setDeliveryPreparedAt(null);
        state.setDeliveryRecoveryCheckedAt(null);
    }

    private boolean reserveBackgroundDeliverySlot(
            ScheduledClientMessageState state,
            Company company,
            LocalDateTime nowStorage
    ) {
        stateRepository.lockDispatchBudget();
        if (!withinDailyLimit(nowStorage)) {
            postpone(state, nextBusinessDayStartStorage(messageTime.nowIrkutsk()), "daily_limit",
                    "Дневной лимит авторассылки исчерпан");
            return false;
        }
        String channel = expectedChannel(company);
        LocalDateTime nowIrkutsk = messageTime.nowIrkutsk();
        LocalDateTime allowed = slotPlanner.afterGap(
                nowIrkutsk,
                lastDispatchAtIrkutsk(channel),
                gapSeconds(channel),
                businessWindows()
        );
        if (allowed.isAfter(nowIrkutsk.plusSeconds(1))) {
            postpone(state, messageTime.toStorageTime(allowed), "rate_limited", "Следующий слот отправки: " + allowed);
            return false;
        }
        return true;
    }

    private void recoverExpiredBadReviewDeliveries(LocalDateTime nowStorage) {
        int uncertain = stateRepository.quarantineExpiredPreparedBadReviewDeliveries(nowStorage);
        int released = stateRepository.releaseExpiredUnpreparedBadReviewClaims(nowStorage);
        if (uncertain > 0 || released > 0) {
            log.warn("Bad-review delivery recovery: uncertain={}, returnedToQueue={}", uncertain, released);
        }
    }

    private void releaseReenabledBadReviewScenarios(LocalDateTime nowStorage) {
        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true)) {
            stateRepository.releaseReenabledScenario(
                    ClientMessageScenario.BAD_REVIEW_INVOICE.name(),
                    "bad_review_invoice_disabled",
                    nowStorage
            );
        }
        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED, true)) {
            stateRepository.releaseReenabledScenario(
                    ClientMessageScenario.BAD_REVIEW_AUTO_BAN.name(),
                    "bad_review_auto_ban_disabled",
                    nowStorage
            );
        }
    }

    private record PreparedBadReviewDelivery(
            Long stateId,
            Long orderId,
            String deliveryToken,
            ClientMessageDelivery.Target target,
            String managerClientId,
            String groupId,
            String message,
            String telegramCopyTransferNumber
    ) {
    }

    /** Versioned scalar snapshot persisted before any client transport is called. */
    public record PreparedScheduledDelivery(
            Long stateId, ClientMessageScenario scenario, String targetKey, Long orderId, String token,
            String operationId, ClientMessageDelivery.Target target, String clientId, String groupId,
            String message, String copy, Integer nextIntervalDays, String successEffect, Long recoveryBatchId,
            long orderGeneration, String orderStatus, OrderStatusNotificationService.PreparedAction orderAction,
            List<WhatsAppAuthAlertService.Recipient> recipients) {}

    private void processClaimedStateOutsideTransaction(Long stateId, LocalDateTime nowStorage,
            LocalDateTime claimedUntil, boolean reconcilePaymentBeforeRetry) {
        requireNoBusinessTransaction();
        if (reconcilePaymentBeforeRetry) {
            ScheduledClientMessageState snapshot = stateRepository.findById(stateId).orElse(null);
            if (snapshot != null && snapshot.getOrderId() != null) paymentLinkService.reconcileActiveLinkForOrder(snapshot.getOrderId());
        }
        PreparedScheduledDelivery prepared;
        try {
            prepared = transactionRunner.callInPreparationTransaction(
                    () -> processClaimedState(stateId, nowStorage, claimedUntil, false));
        } catch (PaymentInstructionException failure) {
            // No provider call has occurred. The joined payment preparation has
            // rolled back, including any newly reserved route/link and envelope.
            transactionRunner.runInNewTransaction(() -> recordUnsentPaymentPreparationFailure(
                    stateId, claimedUntil, messageTime.databaseTimestamp(messageTime.nowStorage()), failure));
            return;
        }
        if (prepared == null) return;
        dispatchScheduledDelivery(prepared, nowStorage);
    }

    private void recordUnsentPaymentPreparationFailure(Long stateId, LocalDateTime claimedUntil,
            LocalDateTime nowStorage, PaymentInstructionException failure) {
        Long orderId = stateRepository.findOrderIdByStateId(stateId).orElse(null);
        if (orderId != null) orderDeliveryContext.lock(orderId);
        ScheduledClientMessageState state = stateRepository.findByIdForUpdate(stateId).orElse(null);
        if (state == null || state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                || !Objects.equals(orderId, state.getOrderId())
                || !ClientMessageStateSafety.isTransactionInProgress(state)
                || !"CLAIMED".equals(state.getDeliveryStatus())
                || !claimedUntil.equals(messageTime.databaseTimestamp(state.getLockedUntil()))
                || !claimedUntil.isAfter(nowStorage)) return;
        stateRepository.lockDispatchBudget();
        // Retain a previously frozen, definitely-unsent operation verbatim.
        state.setDeliveryStatus(hasText(state.getDeliveryEnvelope()) ? "RETRYABLE" : null);
        registerFailure(state, nowStorage, "payment_instruction_failed", failure.getMessage(), null, 0);
    }

    void dispatchScheduledDelivery(PreparedScheduledDelivery prepared, LocalDateTime nowStorage) {
        requireNoBusinessTransaction();
        if (!liveSendingEnabled()) {
            transactionRunner.runInNewTransaction(() -> pausePreparedWithoutDispatch(prepared.stateId(),
                    prepared.orderId(), prepared.token(), prepared.scenario(), prepared.targetKey(),
                    messageTime.databaseTimestamp(messageTime.nowStorage())));
            return;
        }
        long startedAt = System.currentTimeMillis();
        ClientMessageSendResult result;
        try {
            requireNoBusinessTransaction();
            result = prepared.orderAction() == null
                    ? messageSender.deliverWithOperationId(prepared.target(), prepared.clientId(), prepared.groupId(), prepared.message(),
                            TelegramTransferCopyButton.fromFrozenTransferNumber(prepared.copy()).orElse(null), prepared.operationId())
                    : orderStatusNotificationService.dispatchPreparedAction(prepared.orderAction());
            if (result == null) result = ClientMessageOperationFence.unknown();
        } catch (RuntimeException ambiguous) {
            result = ClientMessageOperationFence.unknown();
        }
        ClientMessageSendResult outcome = result;
        boolean finalized = transactionRunner.callInNewTransaction(() -> finalizeScheduledDelivery(prepared, outcome,
                messageTime.databaseTimestamp(messageTime.nowStorage()), System.currentTimeMillis() - startedAt));
        if (finalized) notifyScheduledDeliveryOutcome(prepared, outcome, nowStorage);
    }

    private void requireNoBusinessTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Scheduled provider I/O must run outside the business transaction");
        }
    }

    private boolean liveSendingEnabled() {
        try {
            return appSettingService.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true);
        } catch (RuntimeException unavailable) {
            log.warn("Client delivery safety switch unavailable; provider dispatch is paused");
            return false;
        }
    }

    /** This branch executes only before any provider call, so non-delivery is known. */
    private void pausePreparedWithoutDispatch(Long stateId, Long orderId, String token,
            ClientMessageScenario scenario, String targetKey, LocalDateTime nowStorage) {
        if (orderId != null) {
            orderDeliveryContext.lock(orderId);
            orderRepository.findByIdForMutation(orderId);
        }
        ScheduledClientMessageState state = stateRepository.findByIdForUpdate(stateId).orElse(null);
        if (state == null || state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                || !"PREPARED".equals(state.getDeliveryStatus()) || !Objects.equals(state.getDeliveryToken(), token)
                || state.getScenario() != scenario || !Objects.equals(state.getTargetKey(), targetKey)
                || !Objects.equals(state.getOrderId(), orderId)) return;
        stateRepository.lockDispatchBudget();
        state.setDeliveryStatus("RETRYABLE");
        // Existing SKIPPED/dry-run release resumes promptly after live is enabled.
        // Keep the saved envelope/token/copy payload instead of inventing a retry.
        registerDryRun(state, nowStorage, state.getDeliveryMessage(), null);
    }

    private PreparedScheduledDelivery persistScheduledDelivery(ScheduledClientMessageState state, Company company,
            Manager manager, String message, String copy, Integer nextIntervalDays, String successEffect,
            Long recoveryBatchId, Order order, OrderStatusNotificationService.PreparedAction orderAction, LocalDateTime nowStorage) {
        PreparedScheduledDelivery prepared;
        if (hasText(state.getDeliveryEnvelope())) {
            prepared = decodeScheduledDelivery(state);
            if (!Objects.equals(prepared.stateId(), state.getId()) || prepared.scenario() != state.getScenario()
                    || !Objects.equals(prepared.targetKey(), state.getTargetKey())) {
                throw new IllegalStateException("Saved scheduled delivery belongs to another business occurrence");
            }
        } else {
            String operation = orderAction == null ? ScheduledDeliveryOperationKey.of(state) : orderAction.operationId();
            prepared = new PreparedScheduledDelivery(state.getId(), state.getScenario(), state.getTargetKey(), state.getOrderId(),
                    UUID.randomUUID().toString(), operation, companyTarget(company), manager == null ? null : manager.getClientId(),
                    company.getGroupId(), message, copy, nextIntervalDays, successEffect, recoveryBatchId,
                    order == null ? 0 : order.getClientMessageGeneration(), order == null ? null : statusTitle(order), orderAction,
                    WhatsAppAuthAlertService.captureRecipients(managerCandidates(company, manager)));
        }
        try { state.setDeliveryEnvelope(DELIVERY_JSON.writeValueAsString(prepared)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Scheduled delivery snapshot is not serializable", failure); }
        state.setDeliveryToken(prepared.token());
        state.setDeliveryMessage(prepared.message());
        state.setDeliveryStatus("PREPARED");
        if (!hasText(state.getDeliveryChannel())) state.setDeliveryChannel(expectedChannel(company));
        state.setDeliveryPreparedAt(nowStorage);
        state.setLastErrorCode("delivery_prepared");
        state.setLastErrorMessage("Сообщение сохранено; отправка выполняется после commit");
        state.setNextAttemptAt(null);
        stateRepository.save(state);
        return prepared;
    }

    private ClientMessageDelivery.Target companyTarget(Company company) {
        return company == null ? null : new ClientMessageDelivery.Target(company.getId(), company.getTitle(), company.getUrlChat(),
                company.getTelegramGroupChatId(), company.getMaxGroupChatId());
    }

    private PreparedScheduledDelivery decodeScheduledDelivery(ScheduledClientMessageState state) {
        return decodeScheduledDelivery(state.getDeliveryEnvelope());
    }

    private PreparedScheduledDelivery decodeScheduledDelivery(String envelope) {
        try { return DELIVERY_JSON.readValue(envelope, PreparedScheduledDelivery.class); }
        catch (Exception corrupt) { throw new IllegalStateException("Scheduled delivery snapshot is unreadable; replay is blocked", corrupt); }
    }

    private boolean finalizeScheduledDelivery(PreparedScheduledDelivery prepared, ClientMessageSendResult result,
            LocalDateTime nowStorage, long durationMs) {
        if (prepared.orderId() != null) orderDeliveryContext.lock(prepared.orderId());
        Order order = prepared.orderId() == null ? null : orderRepository.findByIdForMutation(prepared.orderId()).orElse(null);
        ScheduledClientMessageState state = stateRepository.findByIdForUpdate(prepared.stateId()).orElse(null);
        if (state == null
                || !Objects.equals(state.getDeliveryToken(), prepared.token()) || state.getScenario() != prepared.scenario()
                || !Objects.equals(state.getTargetKey(), prepared.targetKey())
                || !Objects.equals(state.getOrderId(), prepared.orderId())
                || !("PREPARED".equals(state.getDeliveryStatus()) || "UNKNOWN".equals(state.getDeliveryStatus()))) return false;
        if (state.getStatus() != ScheduledMessageStateStatus.ACTIVE) {
            // The task may close while delivery is in flight. Retain the immutable
            // envelope and the reason for closure; only record the late receipt.
            // In particular never reschedule or repeat a completed business action.
            if (result.sent()) {
                stateRepository.lockDispatchBudget();
                state.setDeliveryStatus("SENT");
                state.setDeliveryChannel(result.channel());
                state.setSentCount(state.getSentCount() + 1);
                state.setLastSuccessAt(nowStorage);
                recordAttempt(state, ScheduledMessageAttemptStatus.SENT, result.channel(),
                        "late_delivery_confirmed", "Подтверждение получено после завершения задачи", prepared.message(), durationMs);
                stateRepository.save(state);
            }
            return false;
        }
        stateRepository.lockDispatchBudget();
        if (!result.sent() && !ClientChatMessageSender.isKnownUnsent(result)) {
            state.setDeliveryStatus("UNKNOWN");
            state.setLastAttemptAt(nowStorage);
            state.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN);
            state.setLastErrorMessage("Исход отправки не подтверждён; сохранённый operation ID требует сверки без новой отправки");
            state.setNextAttemptAt(null);
            state.setLockedUntil(null);
            recordAttempt(state, ScheduledMessageAttemptStatus.FAILED, result.channel(), state.getLastErrorCode(),
                    state.getLastErrorMessage(), prepared.message(), durationMs);
            stateRepository.save(state);
            return true;
        }
        if (!result.sent()) {
            // Retain exact target/content/key for a proven unsent attempt. A changed
            // template or company route must not change an already frozen envelope.
            state.setDeliveryStatus("RETRYABLE");
            registerFailure(state, nowStorage, result.errorCode(), result.errorMessage(), prepared.message(), durationMs);
            return true;
        }
        clearDeliveryPreparation(state);
        registerSuccess(state, nowStorage, result.channel(), prepared.message(), durationMs, prepared.nextIntervalDays());
        if (prepared.orderAction() != null) {
            orderStatusNotificationService.applyPreparedAction(order, prepared.orderAction());
            markDone(state, nowStorage, null, null);
        } else if ("PAYMENT_REMINDER".equals(prepared.successEffect()) && order != null
                && order.getClientMessageGeneration() == prepared.orderGeneration()
                && Objects.equals(statusTitle(order), prepared.orderStatus()) && STATUS_TO_PAY.equals(statusTitle(order))) {
            movePaymentReminderToReminderStatus(state, order, nowStorage);
        } else if ("REVIEW_RECOVERY_NOTICE".equals(prepared.successEffect())) {
            reviewRecoveryTaskService.markClientNotifiedAutomatically(prepared.recoveryBatchId());
            markDone(state, nowStorage, null, null);
        }
        return true;
    }

    private void notifyScheduledDeliveryOutcome(PreparedScheduledDelivery prepared, ClientMessageSendResult result, LocalDateTime nowStorage) {
        requireNoBusinessTransaction();
        try {
            if (prepared.orderAction() != null) {
                orderStatusNotificationService.notifyPreparedActionOutcome(prepared.orderAction(), result);
            } else if (result.sent() && isWhatsAppChannel(result.channel())) {
                whatsAppAuthAlertService.notifyRecoveredSnapshot(prepared.clientId(), "успешная отправка автоответчика", nowStorage, prepared.recipients());
            } else if (!result.sent() && isWhatsAppAuthUnavailable(result.errorCode(), result.errorMessage())) {
                whatsAppAuthAlertService.notifyAuthIssueSnapshot(prepared.clientId(), prepared.target() == null ? null : prepared.target().title(),
                        "фоновый автоответчик", result.errorCode(), result.errorMessage(), nowStorage,
                        messageTime.toIrkutskTime(nextWhatsAppAuthAttemptAt(nowStorage)), prepared.recipients());
            }
        } catch (RuntimeException notificationFailed) {
            log.warn("Scheduled delivery ancillary notification failed: stateId={}", prepared.stateId(), notificationFailed);
        }
    }

    private void recoverOrdinaryDeliveries(LocalDateTime nowStorage) {
        deliveryRecovery.recover(nowStorage, nowStorage.minusMinutes(DEFAULT_LOCK_MINUTES),
                envelope -> decodeScheduledDelivery(envelope).operationId(), (envelope, receipt) -> {
                    PreparedScheduledDelivery prepared = decodeScheduledDelivery(envelope);
                    boolean finalized = transactionRunner.callInNewTransaction(
                            () -> finalizeScheduledDelivery(prepared, receipt, nowStorage, 0));
                    if (finalized) notifyScheduledDeliveryOutcome(prepared, receipt, nowStorage);
                });
    }

    private PreparedScheduledDelivery processClaimedState(
            Long stateId,
            LocalDateTime nowStorage,
            LocalDateTime expectedLockedUntil
    ) {
        return processClaimedState(stateId, nowStorage, expectedLockedUntil, false);
    }

    private PreparedScheduledDelivery processClaimedState(
            Long stateId,
            LocalDateTime nowStorage,
            LocalDateTime expectedLockedUntil,
            boolean reconcilePaymentBeforeRetry
    ) {
        // A real current order lock serializes the durable reservations of both send workflows.
        Long reservedOrderId = stateRepository.findOrderIdByStateId(stateId).orElse(null);
        boolean manualContactReserved = reservedOrderId != null
                && manualContactReservation.blocksScheduledContact(reservedOrderId);
        ScheduledClientMessageState state = stateRepository.findByIdForUpdate(stateId).orElse(null);
        if (state == null || state.getStatus() != ScheduledMessageStateStatus.ACTIVE) {
            return null;
        }
        if (!Objects.equals(reservedOrderId, state.getOrderId()))
            throw new IllegalStateException("Scheduled delivery order changed while acquiring its reservation");
        LocalDateTime currentLockedUntil = messageTime.databaseTimestamp(state.getLockedUntil());
        LocalDateTime currentTime = messageTime.databaseTimestamp(messageTime.nowStorage());
        if (!ClientMessageStateSafety.isTransactionInProgress(state)
                || !expectedLockedUntil.equals(currentLockedUntil)) {
            log.warn(
                    "Scheduled client message claim ownership changed before processing stateId={} expected={} actual={}",
                    stateId,
                    expectedLockedUntil,
                    currentLockedUntil
            );
            return null;
        }
        if (!currentLockedUntil.isAfter(currentTime)) {
            log.warn("Scheduled client message claim expired before processing stateId={}", stateId);
            return null;
        }
        if (requiresClientMessageSlot(state.getScenario())) stateRepository.lockDispatchBudget();
        PreparedScheduledDelivery prepared;
        if (manualContactReserved && requiresClientMessageSlot(state.getScenario())) {
            postpone(state, currentTime.plusMinutes(5), "manual_contact_reserved",
                    "Ручная отправка текущего цикла уже сохранена или подтверждена; автоматическое сообщение отложено без отправки");
            prepared = null;
        } else {
            prepared = processState(stateId, messageTime.databaseTimestamp(messageTime.nowStorage()));
        }
        if (prepared == null && "CLAIMED".equals(state.getDeliveryStatus())) {
            state.setDeliveryStatus(hasText(state.getDeliveryEnvelope()) ? "RETRYABLE" : null);
            stateRepository.save(state);
        }
        return prepared;
    }

    private void quarantineRolledBackState(
            Long stateId,
            LocalDateTime expectedLockedUntil,
            LocalDateTime nowStorage,
            RuntimeException failure
    ) {
        try {
            transactionRunner.runInNewTransaction(() -> {
                ScheduledClientMessageState state = stateRepository.findByIdForUpdate(stateId).orElse(null);
                if (state == null || state.getStatus() != ScheduledMessageStateStatus.ACTIVE) {
                    return;
                }
                if (!ClientMessageStateSafety.isTransactionInProgress(state)
                        || !expectedLockedUntil.equals(messageTime.databaseTimestamp(state.getLockedUntil()))) {
                    log.warn(
                            "Scheduled client message rollback quarantine skipped: claim ownership changed "
                                    + "stateId={} expected={} actual={}",
                            stateId,
                            expectedLockedUntil,
                            state.getLockedUntil()
                    );
                    return;
                }
                boolean preparationFailure = failure instanceof com.hunt.otziv.p_products.api.LegacyOrderNotificationException
                        && LegacyOrderMessagePreparationRecovery.hasNoDeliveryEvidence(state);
                String errorCode = preparationFailure ? ClientMessageStateSafety.LEGACY_PREPARATION_UNVERIFIED
                        : ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN;
                String message = preparationFailure
                        ? "Сообщение не отправлялось: требуется подтвердить новый цикл старого заказа перед подготовкой."
                        : "Транзакция обработки откатилась. Результат внешней отправки не определен; "
                        + "автоматический повтор остановлен до ручной проверки. Причина: "
                        + readableException(failure);
                recordAttempt(
                        state,
                        ScheduledMessageAttemptStatus.FAILED,
                        null,
                        errorCode,
                        message,
                        message,
                        0
                );
                state.setLastAttemptAt(nowStorage);
                state.setLastErrorCode(errorCode);
                state.setLastErrorMessage(limit(message, 1000));
                state.setConsecutiveFailures(state.getConsecutiveFailures() + 1);
                state.setNextAttemptAt(null);
                state.setLockedUntil(null);
                stateRepository.save(state);
            });
        } catch (RuntimeException recoveryFailure) {
            log.error(
                    "Scheduled client message rollback quarantine failed stateId={} originalFailure={}",
                    stateId,
                    readableException(failure),
                    recoveryFailure
            );
        }
    }

    private PreparedScheduledDelivery processState(Long stateId, LocalDateTime nowStorage) {
        ScheduledClientMessageState state = stateRepository.findById(stateId).orElse(null);
        if (state == null || state.getStatus() != ScheduledMessageStateStatus.ACTIVE) {
            return null;
        }
        if (state.getScenario() == ClientMessageScenario.BAD_REVIEW_AUTO_BAN
                && !appSettingService.getBoolean(
                AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED, true)) {
            postponeScenarioDisabled(
                    state,
                    nowStorage,
                    "bad_review_auto_ban_disabled",
                    "Автобан после дополнительных задач выключен настройкой"
            );
            return null;
        }
        if (isPaymentScenario(state.getScenario()) && suppressDuplicatePaymentMessage(state, nowStorage)) {
            return null;
        }

        LocalDateTime nowIrkutsk = messageTime.nowIrkutsk();
        boolean requiresMessageSlot = requiresClientMessageSlot(state.getScenario());
        if (requiresMessageSlot && !withinDailyLimit(nowStorage)) {
            postpone(state, nextBusinessDayStartStorage(nowIrkutsk), "daily_limit", "Дневной лимит авторассылки исчерпан");
            return null;
        }

        Company company = resolveCompany(state);
        if (company == null) {
            disable(state, nowStorage, "company_missing", "Компания для авторассылки не найдена");
            return null;
        }

        if (requiresMessageSlot) {
            String expectedChannel = hasText(state.getDeliveryEnvelope()) && hasText(state.getDeliveryChannel())
                    ? state.getDeliveryChannel() : expectedChannel(company);
            LocalDateTime allowedByGap = slotPlanner.afterGap(
                    nowIrkutsk,
                    lastDispatchAtIrkutsk(expectedChannel),
                    gapSeconds(expectedChannel),
                    businessWindows()
            );
            if (allowedByGap.isAfter(nowIrkutsk.plusSeconds(1))) {
                postpone(state, messageTime.toStorageTime(allowedByGap), "rate_limited", "Следующий слот отправки: " + allowedByGap);
                return null;
            }
        }

        return switch (state.getScenario()) {
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
            case REVIEW_CHECK_AUTO_ARCHIVE -> { autoArchiveStaleReviewCheck(state, nowStorage); yield null; }
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
            case PAYMENT_OVERDUE_ESCALATION -> { escalateOverduePayment(state, nowStorage); yield null; }
            case BAD_REVIEW_INVOICE -> throw new IllegalStateException(
                    "BAD_REVIEW_INVOICE must use prepare/commit/send/finalize delivery"
            );
            case BAD_REVIEW_AUTO_BAN -> { autoBanAfterBadReviews(state, nowStorage); yield null; }
            case REVIEW_RECOVERY_NOTICE -> sendReviewRecoveryNotice(state, company, nowStorage);
        };
    }

    private PreparedScheduledDelivery sendClientTextReminder(
            ScheduledClientMessageState state,
            Company company,
            LocalDateTime nowStorage
    ) {
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null) {
            disable(state, nowStorage, "order_missing", "Заказ для напоминания о тексте клиента не найден");
            return null;
        }

        if (!order.isWaitingForClient()) {
            markDone(state, nowStorage, "client_text_received", "Заказ уже не ждет текст клиента");
            return null;
        }
        if (shouldAutoClearClientTextWaiting(order, nowStorage)) {
            order.setWaitingForClient(false);
            order.setWaitingForClientChangedAt(null);
            orderRepository.save(order);
            markDone(state, nowStorage, "client_text_waiting_expired", "Ожидание текста клиента снято автоматически: заказ без изменений 7 дней");
            log.info("Client text waiting auto-cleared orderId={} stateId={} after {} days",
                    order.getId(), state.getId(), DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS);
            return null;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return null;
        }

        String status = statusTitle(order);
        if (!listSetting(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES, DEFAULT_CLIENT_TEXT_REMINDER_STATUSES).contains(status)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже не в статусе ожидания текста клиента");
            return null;
        }
        if (!isCurrentClientTextWaitingCycle(state, order)) {
            markDone(state, nowStorage, "client_text_cycle_changed", "Заказ уже перешел в новый цикл ожидания текста клиента");
            return null;
        }

        String message = clientTextReminderText(order);
        return sendMessage(state, company, manager(order), message, nowStorage, clientTextReminderIntervalDays());
    }

    private PreparedScheduledDelivery sendOrderReminder(
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
            return null;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return null;
        }
        if (!reviewCheck && completePaymentMessageForCommonBillingLinkedOrder(
                state,
                order,
                nowStorage,
                "Заказ входит в общий счет; одиночное платежное напоминание не отправляется"
        )) {
            return null;
        }

        String status = statusTitle(order);
        boolean paymentReturnReminder = !reviewCheck && isPaymentReturnReminder(state);
        if (!expectedStatuses.contains(status)
                && !(paymentReturnReminder && isPaymentReturnEligibleStatus(status))) {
            markDone(state, nowStorage, "order_status_changed", changedMessage);
            return null;
        }
        if (paymentReturnReminder
                ? !isCurrentPaymentReturnCycle(state, order)
                : !isCurrentOrderCycle(state, order)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже перешел в новый цикл статуса");
            return null;
        }
        if (reviewCheck && state.getSentCount() >= 7) {
            // Archive policy runs independently of the message budget for ordinary and common-invoice orders.
            // No eighth reminder while waiting for the response window or a blocking reply to be resolved.
            postpone(state, nextNoSendAttemptAt(nowStorage), null, null);
            return null;
        }

        PaymentMessageWithTransfer paymentMessage;
        try {
            paymentMessage = reviewCheck
                    ? new PaymentMessageWithTransfer(reviewCheckReminderText(order), null)
                    : paymentReminderText(order);
        } catch (PaperInvoicePendingException pending) {
            // Successful preparation intentionally awaits the owner's issued mark.
            // Commit that source so the owner can confirm it, without an envelope/send.
            registerFailure(state, nowStorage, "payment_instruction_failed", pending.getMessage(), null, 0);
            return null;
        }
        return prepareMessage(state, company, manager(order), paymentMessage.message(), nowStorage,
                nextIntervalDays, paymentMessage.telegramCopyTransferNumber(),
                !reviewCheck && STATUS_TO_PAY.equals(status) ? "PAYMENT_REMINDER" : null, order, null, null);
    }

    private PreparedScheduledDelivery retryReviewCheckDelivery(ScheduledClientMessageState state, Company company, LocalDateTime nowStorage) {
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null) {
            disable(state, nowStorage, "order_missing", "Заказ для повторной отправки проверки отзывов не найден");
            return null;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return null;
        }

        String status = statusTitle(order);
        if (!STATUS_TO_CHECK.equals(status)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже вышел из статуса ожидания отправки проверки");
            return null;
        }
        if (!isCurrentOrderCycle(state, order)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже перешел в новый цикл статуса");
            return null;
        }

        String message = reviewCheckReminderText(order);
        if (!liveSendingEnabled()) {
            registerDryRun(state, nowStorage, message, null);
            return null;
        }

        if (order.getClientMessageGeneration() == 0) legacyPreparationRecovery.establishCurrentCycle(order.getId(), state);
        var action = orderStatusNotificationService.prepareAction(STATUS_TO_CHECK, order,
                order.getManager() == null ? null : order.getManager().getClientId(),
                order.getCompany() == null ? null : order.getCompany().getGroupId(), message, STATUS_IN_CHECK, null);
        return prepareMessage(state, company, manager(order), message, nowStorage, null, null,
                "ORDER_ACTION", order, null, action);
    }

    private PreparedScheduledDelivery retryPaymentInvoice(ScheduledClientMessageState state, Company company, LocalDateTime nowStorage) {
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null) {
            disable(state, nowStorage, "order_missing", "Заказ для повторной отправки счета не найден");
            return null;
        }
        if (postponeForReviewRecoveryIfNeeded(state, order, nowStorage)) {
            return null;
        }
        if (completePaymentMessageForCommonBillingLinkedOrder(
                state,
                order,
                nowStorage,
                "Заказ входит в общий счет; одиночный финальный счет не отправляется"
        )) {
            return null;
        }

        String status = statusTitle(order);
        if (!STATUS_PUBLIC.equals(status)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже вышел из статуса опубликованного счета");
            return null;
        }
        if (!isCurrentOrderCycle(state, order)) {
            markDone(state, nowStorage, "order_status_changed", "Заказ уже перешел в новый цикл статуса");
            return null;
        }

        OrderPaymentMessageBuilder.PreparedPaymentMessage paymentMessage;
        try {
            paymentMessage = orderPaymentMessageBuilder.publishedOrderPaymentMessageInCurrentTransaction(order);
        } catch (Exception e) {
            throw new PaymentInstructionException(readableException(e), e);
        }

        if (!liveSendingEnabled()) {
            registerDryRun(state, nowStorage, paymentMessage.message(), null);
            return null;
        }

        if (order.getClientMessageGeneration() == 0) legacyPreparationRecovery.establishCurrentCycle(order.getId(), state);
        var action = orderStatusNotificationService.prepareAction(STATUS_PUBLIC, order,
                order.getManager() == null ? null : order.getManager().getClientId(),
                order.getCompany() == null ? null : order.getCompany().getGroupId(),
                paymentMessage.message(), STATUS_TO_PAY, paymentMessage.telegramCopyTransferNumber());
        return prepareMessage(state, company, manager(order), paymentMessage.message(), nowStorage, null,
                paymentMessage.telegramCopyTransferNumber(), "ORDER_ACTION", order, null, action);
    }

    private void autoArchiveStaleReviewCheck(ScheduledClientMessageState state, LocalDateTime nowStorage) {
        // Retire the former day-only decision. ReviewReminderClosure rechecks receipts, reply window and history.
        markDone(state, nowStorage, "archive_policy_replaced", "Автоархив выполняется после семи подтверждённых напоминаний и проверки ответов клиента");
    }

    private void scheduleBadReviewAutoBanIfReady(Order order) {
        var summary = badReviewTaskService.getSummaryForOrder(order.getId());
        if (summary != null && summary.pending() == 0 && summary.done() > 0) {
            paymentInvoiceRetryScheduler.scheduleBadReviewAutoBan(order);
        }
    }

    private int ensurePaymentInvoiceStates(LocalDateTime nowStorage) {
        int delayHours = paymentInvoiceRetryDelayHours();
        List<OrderRepository.ClientMessageCandidate> candidates = orderRepository.findClientMessageCandidates(
                List.of(STATUS_PUBLIC),
                nowStorage,
                PageRequest.of(0, candidateLimit())
        );
        candidates = excludeActiveCommonInvoiceOrders(ClientMessageScenario.PAYMENT_INVOICE_RETRY, candidates);
        List<StateSeed> seeds = new ArrayList<>(candidates.size());
        for (OrderRepository.ClientMessageCandidate order : candidates) {
            LocalDateTime statusChangedAt = order.getStatusChangedAt();
            seeds.add(new StateSeed(
                    ClientMessageScenario.PAYMENT_INVOICE_RETRY,
                    ClientMessageTargetType.ORDER,
                    orderTargetKey(order.getId(), statusChangedAt),
                    order.getCompanyId(),
                    order.getId(),
                    null,
                    scheduleAtStorage(statusChangedAt.plusHours(delayHours))
            ));
        }
        int affected = ensureStates(seeds);
        if (affected > 0) {
            log.info("Client messages reconciled payment-invoice states affectedRows={} candidates={}",
                    affected, candidates.size());
        }
        return candidates.size();
    }

    private List<OrderRepository.ClientMessageCandidate> excludeActiveCommonInvoiceOrders(
            ClientMessageScenario scenario,
            List<OrderRepository.ClientMessageCandidate> candidates
    ) {
        if (!PAYMENT_AUTOMATION_SCENARIOS.contains(scenario) || candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
        if (commonBillingService == null) {
            return candidates;
        }
        Set<Long> activeCommonInvoiceOrderIds = commonBillingService.findOrderIdsInActiveCommonInvoices(
                candidates.stream().map(OrderRepository.ClientMessageCandidate::getId).toList()
        );
        if (activeCommonInvoiceOrderIds.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> !activeCommonInvoiceOrderIds.contains(candidate.getId()))
                .toList();
    }

    private ManualRetryResult manualRetryResult(ScheduledClientMessageState state, boolean attempted) {
        return new ManualRetryResult(
                state.getId(),
                attempted,
                state.getStatus(),
                state.getLastErrorCode(),
                state.getLastErrorMessage(),
                state.getConsecutiveFailures(),
                state.getNextAttemptAt()
        );
    }

    private boolean suppressDuplicatePaymentMessage(
            ScheduledClientMessageState state,
            LocalDateTime nowStorage
    ) {
        if (state.getOrderId() == null) {
            return false;
        }
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (!orderPaymentIntegrityService.hasSettledPaymentEvidence(order)) {
            return false;
        }
        markDone(
                state,
                nowStorage,
                OrderPaymentIntegrityService.SUPPRESSED_ERROR_CODE,
                "Отправка повторного счета остановлена: заказ уже полностью оплачен"
        );
        log.warn("Duplicate payment message suppressed: orderId={}, stateId={}, scenario={}",
                state.getOrderId(), state.getId(), state.getScenario());
        return true;
    }

    private boolean isPaymentScenario(ClientMessageScenario scenario) {
        return scenario == ClientMessageScenario.PAYMENT_INVOICE_RETRY
                || scenario == ClientMessageScenario.PAYMENT_REMINDER
                || scenario == ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION
                || scenario == ClientMessageScenario.BAD_REVIEW_INVOICE
                || scenario == ClientMessageScenario.BAD_REVIEW_AUTO_BAN;
    }

    private boolean isManagedByActiveCommonInvoice(Order order) {
        if (order == null || order.getId() == null) {
            return false;
        }
        CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
        return commonBillingService != null && commonBillingService.isOrderInActiveCommonInvoice(order.getId());
    }

    private boolean completePaymentMessageForCommonBillingLinkedOrder(
            ScheduledClientMessageState state,
            Order order,
            LocalDateTime nowStorage,
            String message
    ) {
        if (!isManagedByActiveCommonInvoice(order)) {
            return false;
        }
        markDone(state, nowStorage, "common_billing_linked", message);
        return true;
    }

    private void autoBanAfterBadReviews(ScheduledClientMessageState state, LocalDateTime nowStorage) {
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED, true)) {
            postponeScenarioDisabled(
                    state,
                    nowStorage,
                    "bad_review_auto_ban_disabled",
                    "Автобан после дополнительных задач выключен настройкой"
            );
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
        if (orderPaymentIntegrityService.hasSettledPaymentEvidence(order)) {
            markDone(state, nowStorage, OrderPaymentIntegrityService.SUPPRESSED_ERROR_CODE,
                    "Автобан отменен: по заказу уже есть подтвержденная оплата");
            return;
        }
        if (isManagedByActiveCommonInvoice(order)) {
            markDone(state, nowStorage, "common_billing_linked",
                    "Автобан одиночного заказа отменен: заказ входит в общий счет");
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

        BadReviewTaskSummary summary;
        try {
            summary = badReviewTaskService.getSummaryForOrder(order.getId());
            if (summary == null) {
                throw new IllegalStateException("Статистика дополнительных задач не определена");
            }
        } catch (RuntimeException e) {
            registerFailure(
                    state,
                    nowStorage,
                    "bad_review_summary_failed",
                    readableException(e),
                    null,
                    0
            );
            return;
        }
        if (summary.pending() > 0 || summary.done() <= 0) {
            markDone(state, nowStorage, "bad_reviews_not_ready", "Плохие задачи уже не готовы к автобану");
            return;
        }

        String message = "Заказ #" + order.getId() + " автоматически переведен в Бан: после финального счета за плохие отзывы прошло "
                + badReviewAutoBanDelayDays() + " дн., оплаты нет.";
        try {
            // The status transition cancels active auto-ban jobs. Complete our own claim
            // in this same transaction so that cancellation does not reject it as in flight.
            state.setStatus(ScheduledMessageStateStatus.DONE);
            stateRepository.save(state);
            boolean changed = orderStatusTransitionService.changeStatusForOrder(order.getId(), STATUS_BAN);
            if (changed) {
                recordAttempt(state, ScheduledMessageAttemptStatus.SENT, "system", null, null, message, 0);
                markDone(state, nowStorage, null, null);
                log.info("Bad review auto-ban applied orderId={} stateId={}", order.getId(), state.getId());
                return;
            }
            state.setStatus(ScheduledMessageStateStatus.ACTIVE);
            registerFailure(state, nowStorage, "status_change_failed", "Статус заказа не изменен", message, 0);
        } catch (Exception e) {
            state.setStatus(ScheduledMessageStateStatus.ACTIVE);
            registerFailure(state, nowStorage, "bad_review_auto_ban_exception", readableException(e), message, 0);
        }
    }

    private PreparedScheduledDelivery sendArchiveOffer(ScheduledClientMessageState state, Company company, LocalDateTime nowStorage) {
        String expectedStatus = archiveCompanyStatus();
        if (!expectedStatus.equals(companyStatusTitle(company))) {
            markDone(state, nowStorage, "company_status_changed", "Компания уже не в статусе \"" + expectedStatus + "\"");
            return null;
        }
        if (!isCurrentArchiveCompanyCycle(state, company)) {
            markDone(state, nowStorage, "company_status_changed", "Компания уже перешла в новый цикл статуса");
            return null;
        }
        if (archiveCandidateRepository.hasArchiveReorderBlocker(
                company == null ? null : company.getId(),
                listSetting(AppSettingService.CLIENT_MESSAGES_ARCHIVE_INACTIVE_ORDER_STATUSES, DEFAULT_ARCHIVE_INACTIVE_ORDER_STATUSES),
                listSetting(AppSettingService.CLIENT_MESSAGES_OPEN_NEXT_ORDER_REQUEST_STATUSES, DEFAULT_OPEN_NEXT_ORDER_REQUEST_STATUSES)
        )) {
            markDone(state, nowStorage, "archive_reorder_blocked", "У компании уже есть активный заказ или открытая заявка на следующий заказ");
            return null;
        }

        String message = archiveOfferText(company);
        return sendMessage(state, company, company.getManager(), message, nowStorage, null);
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
        if (isManagedByActiveCommonInvoice(order)) {
            markDone(
                    state,
                    nowStorage,
                    "common_billing_managed",
                    "Просрочка оплаты этого заказа управляется общим счетом"
            );
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

    private PreparedScheduledDelivery sendReviewRecoveryNotice(ScheduledClientMessageState state, Company company, LocalDateTime nowStorage) {
        Long batchId = ReviewRecoveryNoticeScheduler.batchIdFromTargetKey(state.getTargetKey());
        if (batchId == null) {
            disable(state, nowStorage, "review_recovery_batch_missing", "Не удалось определить пачку восстановления");
            return null;
        }

        ReviewRecoveryBatch batch = reviewRecoveryBatchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            disable(state, nowStorage, "review_recovery_batch_missing", "Пачка восстановления не найдена");
            return null;
        }
        if (batch.getStatus() == ReviewRecoveryBatchStatus.CLIENT_NOTIFIED
                || batch.getStatus() == ReviewRecoveryBatchStatus.ARCHIVED) {
            markDone(state, nowStorage, "review_recovery_already_notified", "Клиент уже отмечен уведомленным");
            return null;
        }
        if (batch.getStatus() == ReviewRecoveryBatchStatus.OPEN) {
            postpone(state, scheduleAtStorage(nowStorage.plusHours(reviewRecoveryNoticeRetryDelayHours())),
                    "review_recovery_still_open", "Восстановление еще не завершено");
            return null;
        }
        if (batch.getStatus() != ReviewRecoveryBatchStatus.COMPLETED) {
            markDone(state, nowStorage, "review_recovery_status_changed", "Пачка восстановления уже не ждет уведомления");
            return null;
        }

        Order order = batch.getOrder();
        if (order == null || order.getId() == null) {
            disable(state, nowStorage, "order_missing", "Заказ для уведомления о восстановлении не найден");
            return null;
        }
        if (reviewRecoveryHoldService.shouldSkipClientRecoveryNotice(order)) {
            reviewRecoveryTaskService.markClientNotifiedAutomatically(batch.getId());
            markDone(state, nowStorage, "order_closed", "Заказ закрыт, клиентское уведомление о восстановлении не требуется");
            return null;
        }

        String message = reviewRecoveryNoticeText(order);
        return prepareMessage(state, company, manager(order), message, nowStorage, null, null,
                "REVIEW_RECOVERY_NOTICE", order, batch.getId(), null);
    }

    private PreparedScheduledDelivery sendMessage(
            ScheduledClientMessageState state,
            Company company,
            Manager manager,
            String message,
            LocalDateTime nowStorage,
            Integer nextIntervalDays
    ) {
        return sendMessage(state, company, manager, message, nowStorage, nextIntervalDays, null);
    }

    private PreparedScheduledDelivery sendMessage(
            ScheduledClientMessageState state,
            Company company,
            Manager manager,
            String message,
            LocalDateTime nowStorage,
            Integer nextIntervalDays,
            String frozenTransferNumber
    ) {
        return prepareMessage(state, company, manager, message, nowStorage, nextIntervalDays, frozenTransferNumber,
                null, null, null, null);
    }

    private PreparedScheduledDelivery prepareMessage(ScheduledClientMessageState state, Company company, Manager manager,
            String message, LocalDateTime nowStorage, Integer nextIntervalDays, String frozenTransferNumber,
            String successEffect, Order order, Long recoveryBatchId, OrderStatusNotificationService.PreparedAction action) {
        if (!liveSendingEnabled()) {
            registerDryRun(state, nowStorage, message, nextIntervalDays);
            return null;
        }
        return persistScheduledDelivery(state, company, manager, message, frozenTransferNumber, nextIntervalDays,
                successEffect, recoveryBatchId, order, action, nowStorage);
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
        return releaseDryRunMessagesIfLiveEnabled(messageTime.nowStorage());
    }

    private int releaseDryRunMessagesIfLiveEnabled(LocalDateTime nowStorage) {
        if (!liveSendingEnabled()) {
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

        String sentAt = messageTime.nowIrkutsk().toString();
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
        String message = "У заказа идет восстановление отзывов. Отправка продолжится автоматически после завершения восстановления";
        if (!"review_recovery_active".equalsIgnoreCase(state.getLastErrorCode())) {
            recordAttempt(state, ScheduledMessageAttemptStatus.SKIPPED, null, "review_recovery_active", message, message, 0);
        }
        state.setLastAttemptAt(nowStorage);
        state.setLastErrorCode("review_recovery_active");
        state.setLastErrorMessage(message);
        state.setLockedUntil(null);
        state.setNextAttemptAt(scheduleAtStorage(nowStorage.plusMinutes(REVIEW_RECOVERY_RECHECK_MINUTES)));
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
        notifyPaymentIssueIfNeeded(state, code, readable);
        // Transport/auth notifications run after this transaction through notifyScheduledDeliveryOutcome.
        if (policy.countForMassProtection()) {
            applyMassErrorProtection(nowStorage, code, readable);
        }
    }

    private void notifyPaymentIssueIfNeeded(ScheduledClientMessageState state, String code, String readable) {
        if (!isPaymentFailClosedIssue(state, code)) {
            return;
        }
        Long orderId = state.getOrderId();
        String scenario = state.getScenario() == null ? "не указан" : state.getScenario().name();
        paymentIssueReminderService.notifyOrderIssueAfterCommit(
                orderId,
                PaymentIssueReminderService.SOURCE_PAYMENT_FAIL_CLOSED,
                orderId,
                "Платёж требует внимания: заказ №" + orderId,
                limit(
                        "Автоответчик не смог выставить или сверить оплату."
                                + "\nЗаказ: №" + orderId
                                + "\nСценарий: " + scenario
                                + "\nКод: " + code
                                + "\nПричина: " + readable
                                + "\nПроверьте карточку заказа и платёжную инструкцию; система не отправила клиенту сомнительный счет автоматически.",
                        1000
                )
        );
    }

    private boolean isPaymentFailClosedIssue(ScheduledClientMessageState state, String code) {
        if (state == null || state.getOrderId() == null || state.getOrderId() <= 0) {
            return false;
        }
        ClientMessageScenario scenario = state.getScenario();
        if (PAYMENT_AUTOMATION_SCENARIOS.contains(scenario)
                || scenario == ClientMessageScenario.BAD_REVIEW_INVOICE) {
            return true;
        }
        String normalized = code == null ? "" : code.toLowerCase(Locale.ROOT);
        return normalized.contains("payment");
    }

    private FailureRetryPolicy failureRetryPolicy(
            String code,
            String readable,
            int consecutiveFailures,
            LocalDateTime nowStorage
    ) {
        if ("operation_ledger_full".equals(code)) {
            return new FailureRetryPolicy(nextTransientAttemptAt(nowStorage), false, false, false);
        }
        if (code.startsWith("operation_") || "invalid_operation_id".equals(code)) {
            return new FailureRetryPolicy(null, true, false, false);
        }
        if (isWhatsAppAuthUnavailable(code, readable)) {
            return new FailureRetryPolicy(nextWhatsAppAuthAttemptAt(nowStorage), false, true, false);
        }
        if (isWhatsAppFastRetryError(code, readable)) {
            return new FailureRetryPolicy(nextTransientAttemptAt(nowStorage), false, false, false);
        }
        if (isManualFixError(code)) {
            boolean disable = consecutiveFailures >= DEFAULT_NO_SEND_MAX_FAILURES;
            return new FailureRetryPolicy(disable ? null : nextNoSendAttemptAt(nowStorage), disable, false, false);
        }
        if (isOperationalNoSendError(code)) {
            return new FailureRetryPolicy(nextNoSendAttemptAt(nowStorage), false, false, false);
        }
        return new FailureRetryPolicy(nextTransientAttemptAt(nowStorage), false, false, true);
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
        applicationEvents.publishEvent(new com.hunt.otziv.client_messages.api.ClientMessageAttemptRecorded(state.getId()));
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

    public String clientTextReminderText(Order order) {
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

    private PaymentMessageWithTransfer paymentReminderText(Order order) {
        String template = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_TEXT,
                DEFAULT_PAYMENT_REMINDER_TEXT
        );
        String managerPayText = managerPayText(order);
        String paymentInstruction = managerPayText;
        String paymentLink = "";
        String tbankPaymentCopyText = "";
        String frozenTransferNumber = null;
        if (requiresBankPaymentLink(template)) {
            ManagerPaymentLinkResponse link = createBankPaymentLink(order);
            if ("OWNER_PAPER_INVOICE".equals(link.paymentMethod())
                    && "CREATED".equals(link.status())) {
                throw new PaperInvoicePendingException();
            }
            paymentLink = link.url();
            tbankPaymentCopyText = link.copyText();
            frozenTransferNumber = link.telegramCopyTransferNumber();
            if (usesBankPaymentInstructionSource() && isDefaultPaymentReminderTemplate(template)) {
                return new PaymentMessageWithTransfer(
                        tbankPaymentCopyText,
                        link.telegramCopyTransferNumber()
                );
            }
            if (usesBankPaymentInstructionSource()) {
                paymentInstruction = link.instructionText();
            }
        }

        return new PaymentMessageWithTransfer(renderOrderTemplate(
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
        ), frozenTransferNumber);
    }

    private record PaymentMessageWithTransfer(String message, String telegramCopyTransferNumber) {
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

    private boolean requiresBankPaymentLink(String template) {
        return usesBankPaymentInstructionSource()
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

    private ManagerPaymentLinkResponse createBankPaymentLink(Order order) {
        try {
            return paymentLinkService.createForOrderInCurrentTransaction(order.getId());
        } catch (Exception e) {
            throw new PaymentInstructionException("Не удалось подготовить банковскую ссылку для заказа #"
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

    private LocalDateTime nextTransientAttemptAt(LocalDateTime nowStorage) {
        int minutes = intSetting(
                AppSettingService.CLIENT_MESSAGES_TRANSIENT_RETRY_MINUTES,
                DEFAULT_TRANSIENT_RETRY_MINUTES,
                1,
                1440
        );
        return scheduleAtStorage(nowStorage.plusMinutes(minutes));
    }

    private boolean withinDailyLimit(LocalDateTime nowStorage) {
        int limit = intSetting(AppSettingService.CLIENT_MESSAGES_DAILY_LIMIT, DEFAULT_DAILY_LIMIT, 1, 5000);
        LocalDate irkutskToday = messageTime.nowIrkutsk().toLocalDate();
        LocalDateTime dayStart = messageTime.toStorageTime(irkutskToday.atStartOfDay());
        return attemptRepository.countClientSentSince(ScheduledMessageAttemptStatus.SENT, dayStart)
                + stateRepository.countReservedDeliveriesSince(dayStart) < limit;
    }

    private LocalDateTime lastDispatchAtIrkutsk(String channel) {
        LocalDateTime sentAt = lastSentAtIrkutsk(channel);
        LocalDateTime reservedAt = stateRepository.latestReservedDeliveryAt(channel)
                .map(messageTime::toIrkutskTime).orElse(null);
        return sentAt == null ? reservedAt : reservedAt == null || sentAt.isAfter(reservedAt) ? sentAt : reservedAt;
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
        return expectedChannelUrl(company == null ? null : company.getUrlChat());
    }

    private String expectedChannel(ClientMessageDelivery.Target target) {
        return expectedChannelUrl(target == null ? null : target.urlChat());
    }

    private String expectedChannelUrl(String value) {
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
        LocalDateTime channelValue = parseLocalDateTime(appSettingService.getStringFresh(lastSentSettingKey(channel), null));
        return channelValue != null
                ? channelValue
                : parseLocalDateTime(appSettingService.getStringFresh(lastSentSettingKey("ANY"), null));
    }

    private String lastSentSettingKey(String channel) {
        return "client.messages.last-sent-at." + (hasText(channel) ? channel : "ANY");
    }

    private LocalDateTime scheduleAtStorage(LocalDateTime desiredStorageTime) {
        LocalDateTime desiredIrkutsk = messageTime.toIrkutskTime(desiredStorageTime);
        LocalDateTime allowedIrkutsk = slotPlanner.nextAllowedAt(desiredIrkutsk, businessWindows());
        return messageTime.toStorageTime(allowedIrkutsk);
    }

    private LocalDateTime nextBusinessDayStartStorage(LocalDateTime nowIrkutsk) {
        LocalDateTime tomorrowStart = nowIrkutsk.toLocalDate().plusDays(1).atTime(10, 0);
        return messageTime.toStorageTime(slotPlanner.nextAllowedAt(tomorrowStart, businessWindows()));
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

    private boolean usesBankPaymentInstructionSource() {
        return BankPaymentInstructionSource.isBankLink(paymentInstructionSource());
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
        return BankPaymentInstructionSource.normalize(raw, DEFAULT_PAYMENT_INSTRUCTION_SOURCE);
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
        if (order == null || order.getId() == null || order.getSum() == null) {
            throw new PaymentInstructionException(
                    "Не удалось достоверно определить сумму заказа для платежного сообщения",
                    null
            );
        }
        try {
            BigDecimal payable = badReviewTaskService.getPayableSum(order);
            if (payable == null || payable.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalStateException("Некорректная итоговая сумма заказа");
            }
            return payable;
        } catch (RuntimeException e) {
            if (e instanceof PaymentInstructionException paymentInstructionException) {
                throw paymentInstructionException;
            }
            throw new PaymentInstructionException(
                    "Не удалось учесть выполненные дополнительные задачи в сумме заказа #" + order.getId(),
                    e
            );
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

    private String paymentReturnTargetKey(Long orderId, LocalDateTime statusChangedAt) {
        return "payment-return:" + orderId + ":" + statusChangedAt.withNano(0);
    }

    private String clientTextWaitingTargetKey(Long orderId, LocalDateTime waitingChangedAt) {
        return "client-text:" + orderId + ":" + waitingChangedAt.withNano(0);
    }

    private boolean isCurrentClientTextWaitingCycle(ScheduledClientMessageState state, Order order) {
        return clientTextWaitingTargetKey(order.getId(), clientTextWaitingChangedAt(order)).equals(state.getTargetKey());
    }

    private boolean shouldAutoClearClientTextWaiting(Order order, LocalDateTime nowStorage) {
        LocalDate changed = order.getChanged();
        if (changed != null) {
            return !changed.plusDays(DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS).isAfter(nowStorage.toLocalDate());
        }
        return !clientTextWaitingChangedAt(order).plusDays(DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS).isAfter(nowStorage);
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

    private boolean isCurrentPaymentReturnCycle(ScheduledClientMessageState state, Order order) {
        return paymentReturnTargetKey(order.getId(), orderStatusChangedAt(order)).equals(state.getTargetKey());
    }

    private boolean isPaymentReturnReminder(ScheduledClientMessageState state) {
        return state != null
                && state.getTargetKey() != null
                && state.getTargetKey().startsWith("payment-return:");
    }

    private boolean isPaymentReturnEligibleStatus(String status) {
        return STATUS_REMINDER.equals(status)
                || STATUS_TO_PAY.equals(status)
                || STATUS_NOT_PAID.equals(status);
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
        return messageTime.nowStorage();
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
        return messageTime.nowStorage();
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

    private boolean shouldCleanupOldAttempts(LocalDateTime nowStorage) {
        return lastCleanupAt == null || !lastCleanupAt.plusHours(1).isAfter(nowStorage);
    }

    private void cleanupOldAttempts(LocalDateTime nowStorage) {
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
                "Client messages tick: live={} nowIrkutsk={} windowAllowed={} windows=\"{}\" candidates clientText={} reviewCheck={} reviewCheckAutoArchive={} paymentInvoice={} paymentReminder={} paymentOverdue={} closedPaymentStates={} archiveReorder={} states active={} disabled={} due={} processed={}",
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true),
                nowIrkutsk,
                windowAllowed,
                windowsSpec,
                reconcileSummary.clientTextReminderCandidates(),
                reconcileSummary.reviewCheckCandidates(),
                reconcileSummary.reviewCheckAutoArchiveCandidates(),
                reconcileSummary.paymentInvoiceCandidates(),
                reconcileSummary.paymentReminderCandidates(),
                reconcileSummary.paymentOverdueCandidates(),
                reconcileSummary.closedPaymentAutomationStates(),
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

    public record ManualRetryResult(
            Long stateId,
            boolean attempted,
            ScheduledMessageStateStatus status,
            String errorCode,
            String errorMessage,
            int consecutiveFailures,
            LocalDateTime nextAttemptAt
    ) {
    }

    public record RecoveredBadReviewDeliveryResult(
            Long stateId,
            Long orderId,
            boolean retryScheduled,
            String message
    ) {
    }
    private record ClientMessageReconcileSummary(
            int clientTextReminderCandidates,
            int reviewCheckCandidates,
            int reviewCheckAutoArchiveCandidates,
            int paymentInvoiceCandidates,
            int paymentReminderCandidates,
            int paymentOverdueCandidates,
            int closedPaymentAutomationStates,
            int archiveReorderCandidates
    ) {
        private static ClientMessageReconcileSummary empty() {
            return new ClientMessageReconcileSummary(0, 0, 0, 0, 0, 0, 0, 0);
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

    private static final class PaperInvoicePendingException extends RuntimeException {
        private PaperInvoicePendingException() {
            super("Бумажный счёт ещё не отмечен как отправленный клиенту");
        }
    }
}
