package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.dto.ArchiveCompanyMessageCandidate;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
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
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderPaymentMessageBuilder;
import com.hunt.otziv.p_products.status.service.OrderReviewCheckMessageBuilder;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentIssueReminderService;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.service.OrderPaymentIntegrityService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryHoldService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.whatsapp.service.WhatsAppAuthAlertService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledClientMessageServiceTest {

    @Mock
    private ScheduledClientMessageStateRepository stateRepository;
    @Mock
    private ScheduledClientMessageStateBatchRepository stateBatchRepository;
    @Mock
    private ScheduledClientMessageAttemptRepository attemptRepository;
    @Mock
    private ArchiveCompanyMessageCandidateRepository archiveCandidateRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ClientChatMessageSender messageSender;
    @Mock
    private ClientMessageSlotPlanner slotPlanner;
    @Mock
    private OrderStatusTransitionService orderStatusTransitionService;
    @Mock
    private OrderStatusNotificationService orderStatusNotificationService;
    @Mock
    private OrderPaymentMessageBuilder orderPaymentMessageBuilder;
    @Mock
    private PaymentLinkService paymentLinkService;
    @Mock
    private PaymentIssueReminderService paymentIssueReminderService;
    @Mock
    private OrderReviewCheckMessageBuilder reviewCheckMessageBuilder;
    @Mock
    private BadReviewTaskService badReviewTaskService;
    @Mock
    private PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    @Mock
    private WhatsAppAuthAlertService whatsAppAuthAlertService;
    @Mock
    private ReviewRecoveryHoldService reviewRecoveryHoldService;
    @Mock
    private ReviewRecoveryTaskService reviewRecoveryTaskService;
    @Mock
    private ReviewRecoveryBatchRepository reviewRecoveryBatchRepository;
    @Mock
    private ObjectProvider<CommonBillingService> commonBillingServiceProvider;
    @Mock
    private CommonBillingService commonBillingService;
    @Mock
    private OrderPaymentIntegrityService orderPaymentIntegrityService;
    @Mock
    private ClientMessageTransactionRunner transactionRunner;
    @Mock
    private SchedulerLeaseService schedulerLeaseService;

    @InjectMocks
    private ScheduledClientMessageService service;

    @BeforeEach
    void setUpProviders() {
        ReflectionTestUtils.setField(service, "commonBillingServiceProvider", commonBillingServiceProvider);
        ReflectionTestUtils.setField(service, "reconcileLeaseDuration", Duration.ofMinutes(10));
        org.mockito.Mockito.lenient().when(slotPlanner.nextAllowedAt(
                        any(LocalDateTime.class),
                        org.mockito.ArgumentMatchers.nullable(String.class)
                ))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void manualReconciliationSkipsWorkWhenAnotherInstanceOwnsLease() {
        when(schedulerLeaseService.tryAcquire(anyString(), any(Duration.class)))
                .thenReturn(Optional.empty());

        service.reconcileCandidatesNow();

        verify(transactionRunner, never()).callInNewTransaction(any());
        verify(schedulerLeaseService, never()).release(any());
    }

    @Test
    void liveDisabledRecordsDryRunSkipWithoutFailureOrSenderCall() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(77L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:10:2026-05-20T10:00")
                .companyId(20L)
                .orderId(10L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .consecutiveFailures(3)
                .build();
        Company company = new Company();
        Manager manager = new Manager();
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 0, 20);

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(false);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        )).thenReturn(ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(service, "sendMessage", state, company, manager, "message", now, 2);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        ScheduledClientMessageAttempt attempt = attemptCaptor.getValue();
        assertEquals(ScheduledMessageAttemptStatus.SKIPPED, attempt.getStatus());
        assertEquals("client_messages_dry_run", attempt.getErrorCode());
        assertEquals("Live-отправка выключена настройкой; сообщение не отправлено", attempt.getErrorMessage());
        assertEquals("message", attempt.getMessagePreview());

        verify(messageSender, never()).send(any(), any(), any(), any());
        verify(attemptRepository, never()).countByStatusAndAttemptedAtGreaterThanEqual(any(), any());
        verify(appSettingService, never()).setString(eq(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL), anyString());

        assertEquals(now, state.getLastAttemptAt());
        assertNull(state.getLastErrorCode());
        assertNull(state.getLastErrorMessage());
        assertEquals(0, state.getConsecutiveFailures());
        assertNull(state.getLockedUntil());
        assertEquals(now.plusDays(ScheduledClientMessageService.DEFAULT_NO_SEND_RETRY_DAYS), state.getNextAttemptAt());
        verify(stateRepository).save(state);
    }

    @Test
    void failedSendRetriesTomorrowInsteadOfDisablingState() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(177L)
                .scenario(ClientMessageScenario.ARCHIVE_REORDER_OFFER)
                .targetType(ClientMessageTargetType.ARCHIVE_COMPANY)
                .targetKey("archive-company:20:2026-02-20T10:00")
                .companyId(20L)
                .archiveOrderId(2L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(20L);
        Manager manager = new Manager();
        manager.setClientId("client-20");
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 10, 20);

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(messageSender.send(eq(company), eq("client-20"), any(), eq("message")))
                .thenReturn(ClientMessageSendResult.failed("whatsapp_group_missing", "Для WhatsApp-группы не задан groupId"));
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        )).thenReturn(ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(service, "sendMessage", state, company, manager, "message", now, null);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.FAILED, attemptCaptor.getValue().getStatus());
        assertEquals("whatsapp_group_missing", attemptCaptor.getValue().getErrorCode());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertEquals("whatsapp_group_missing", state.getLastErrorCode());
        assertEquals(1, state.getConsecutiveFailures());
        assertEquals(now.plusDays(ScheduledClientMessageService.DEFAULT_NO_SEND_RETRY_DAYS), state.getNextAttemptAt());
        verify(stateRepository).save(state);
    }

    @Test
    void archiveOfferDoesNotSendWhenActiveOrderAppearedAfterQueueing() {
        LocalDateTime statusChangedAt = LocalDateTime.of(2026, 2, 20, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 10, 20);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(180L)
                .scenario(ClientMessageScenario.ARCHIVE_REORDER_OFFER)
                .targetType(ClientMessageTargetType.ARCHIVE_COMPANY)
                .targetKey("archive-company:20:2026-02-20T10:00")
                .companyId(20L)
                .archiveOrderId(2L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        CompanyStatus stop = new CompanyStatus();
        stop.setTitle("На стопе");
        Company company = new Company();
        company.setId(20L);
        company.setStatus(stop);
        company.setStatusChangedAt(statusChangedAt);

        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_ARCHIVE_COMPANY_STATUS,
                ScheduledClientMessageService.DEFAULT_ARCHIVE_COMPANY_STATUS
        )).thenReturn(ScheduledClientMessageService.DEFAULT_ARCHIVE_COMPANY_STATUS);
        when(archiveCandidateRepository.hasArchiveReorderBlocker(
                eq(20L),
                eq(List.of("Оплачено", "Архив", "Бан")),
                eq(List.of("PENDING", "FAILED"))
        )).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "sendArchiveOffer", state, company, now);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SKIPPED, attemptCaptor.getValue().getStatus());
        assertEquals("archive_reorder_blocked", attemptCaptor.getValue().getErrorCode());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(messageSender, never()).send(any(), any(), any(), any());
        verify(stateRepository).save(state);
    }

    @Test
    void archiveReorderAttemptAddsStableForwardJitter() {
        LocalDateTime baseAt = LocalDateTime.of(2026, 8, 29, 10, 20);

        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_JITTER_DAYS,
                ScheduledClientMessageService.DEFAULT_ARCHIVE_REORDER_JITTER_DAYS
        )).thenReturn(10);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LocalDateTime first = service.archiveReorderAttemptAt(baseAt, "archive-company:20:2026-02-20T10:00");
        LocalDateTime second = service.archiveReorderAttemptAt(baseAt, "archive-company:20:2026-02-20T10:00");

        assertEquals(first, second);
        assertNotNull(first);
        assertTrue(!first.isBefore(baseAt));
        assertTrue(!first.isAfter(baseAt.plusDays(10)));
    }

    @Test
    void archiveReorderAttemptKeepsBaseDateWhenJitterDisabled() {
        LocalDateTime baseAt = LocalDateTime.of(2026, 8, 29, 10, 20);

        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_JITTER_DAYS,
                ScheduledClientMessageService.DEFAULT_ARCHIVE_REORDER_JITTER_DAYS
        )).thenReturn(0);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LocalDateTime result = service.archiveReorderAttemptAt(baseAt, "archive-company:20:2026-02-20T10:00");

        assertEquals(baseAt, result);
    }

    @Test
    void archiveReconciliationCoversEveryCandidateBeyondThePageLimit() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 12, 1, 30);
        LocalDateTime firstStatusChangedAt = LocalDateTime.of(2024, 1, 1, 0, 0);
        List<ArchiveCompanyMessageCandidate> firstPage = IntStream.rangeClosed(1, 200)
                .mapToObj(index -> new ArchiveCompanyMessageCandidate(
                        (long) index,
                        null,
                        firstStatusChangedAt.plusMinutes(index - 1L)
                ))
                .toList();
        ArchiveCompanyMessageCandidate finalCandidate = new ArchiveCompanyMessageCandidate(
                201L,
                null,
                firstStatusChangedAt.plusMinutes(200)
        );
        List<Integer> batchSizes = new ArrayList<>();

        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_MONTHS,
                ScheduledClientMessageService.DEFAULT_ARCHIVE_REORDER_MONTHS
        )).thenReturn(ScheduledClientMessageService.DEFAULT_ARCHIVE_REORDER_MONTHS);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_CANDIDATE_LIMIT,
                ScheduledClientMessageService.DEFAULT_CANDIDATE_LIMIT
        )).thenReturn(ScheduledClientMessageService.DEFAULT_CANDIDATE_LIMIT);
        when(appSettingService.getString(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(archiveCandidateRepository.findUnsynchronizedCandidatesAfter(
                any(LocalDateTime.class),
                eq(200),
                eq(ScheduledClientMessageService.DEFAULT_ARCHIVE_COMPANY_STATUS),
                any(Collection.class),
                any(Collection.class),
                nullable(LocalDateTime.class),
                nullable(Long.class)
        )).thenReturn(firstPage, List.of(finalCandidate));
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stateBatchRepository.upsertAll(any(Collection.class))).thenAnswer(invocation -> {
            Collection<?> seeds = invocation.getArgument(0);
            batchSizes.add(seeds.size());
            return seeds.size();
        });

        Integer candidateCount = ReflectionTestUtils.invokeMethod(service, "ensureArchiveCompanyStates", now);

        assertEquals(201, candidateCount);
        assertEquals(List.of(200, 1), batchSizes);
        verify(archiveCandidateRepository).findUnsynchronizedCandidatesAfter(
                eq(now.minusMonths(ScheduledClientMessageService.DEFAULT_ARCHIVE_REORDER_MONTHS)),
                eq(200),
                eq(ScheduledClientMessageService.DEFAULT_ARCHIVE_COMPANY_STATUS),
                any(Collection.class),
                any(Collection.class),
                isNull(),
                isNull()
        );
        verify(archiveCandidateRepository).findUnsynchronizedCandidatesAfter(
                eq(now.minusMonths(ScheduledClientMessageService.DEFAULT_ARCHIVE_REORDER_MONTHS)),
                eq(200),
                eq(ScheduledClientMessageService.DEFAULT_ARCHIVE_COMPANY_STATUS),
                any(Collection.class),
                any(Collection.class),
                eq(firstPage.getLast().statusChangedAt()),
                eq(firstPage.getLast().companyId())
        );
        verify(stateBatchRepository, times(2)).upsertAll(any(Collection.class));
    }

    @Test
    void liveEnabledReleasesDryRunStatesBackToQueue() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 29, 12, 30);

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(stateRepository.releaseDryRunStates(now)).thenReturn(202);

        Integer released = ReflectionTestUtils.invokeMethod(service, "releaseDryRunMessagesIfLiveEnabled", now);

        assertEquals(202, released);
        verify(stateRepository).releaseDryRunStates(now);
    }

    @Test
    void whatsappNotReadyRetriesSoonAndAlertsManager() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(178L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:20:2026-05-25T10:00")
                .companyId(30L)
                .orderId(20L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(30L);
        company.setTitle("Тестовая компания");
        company.setGroupId("group-20");
        Manager manager = new Manager();
        manager.setId(4L);
        manager.setClientId("whatsapp_vika");
        manager.setUser(User.builder().telegramChatId(12345L).build());
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 10, 20);

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_WHATSAPP_AUTH_RETRY_HOURS,
                ScheduledClientMessageService.DEFAULT_WHATSAPP_AUTH_RETRY_HOURS
        )).thenReturn(ScheduledClientMessageService.DEFAULT_WHATSAPP_AUTH_RETRY_HOURS);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        )).thenReturn(ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageSender.send(eq(company), eq("whatsapp_vika"), eq("group-20"), eq("message")))
                .thenReturn(ClientMessageSendResult.failed(
                        "whatsapp_not_ready",
                        "WhatsApp API вернул HTTP 503. Ответ: {\"status\":\"not_ready\",\"authenticated\":false,\"state\":\"qr\"}"
                ));
        ReflectionTestUtils.invokeMethod(service, "sendMessage", state, company, manager, "message", now, null);

        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertEquals("whatsapp_not_ready", state.getLastErrorCode());
        assertEquals(1, state.getConsecutiveFailures());
        assertEquals(now.plusHours(ScheduledClientMessageService.DEFAULT_WHATSAPP_AUTH_RETRY_HOURS), state.getNextAttemptAt());
        verify(whatsAppAuthAlertService).notifyAuthIssue(
                eq("whatsapp_vika"),
                eq("Тестовая компания"),
                eq("фоновый автоответчик"),
                eq("whatsapp_not_ready"),
                anyString(),
                eq(now),
                any(LocalDateTime.class),
                any()
        );
    }

    @Test
    void whatsappWarmupRetriesSoonWithoutAuthAlert() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(179L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:21:2026-05-25T10:00")
                .companyId(31L)
                .orderId(21L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(31L);
        company.setTitle("Тестовая компания");
        company.setGroupId("group-21");
        Manager manager = new Manager();
        manager.setId(5L);
        manager.setClientId("whatsapp_lika");
        manager.setUser(User.builder().telegramChatId(12345L).build());
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 10, 20);

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_TRANSIENT_RETRY_MINUTES,
                ScheduledClientMessageService.DEFAULT_TRANSIENT_RETRY_MINUTES
        )).thenReturn(ScheduledClientMessageService.DEFAULT_TRANSIENT_RETRY_MINUTES);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        )).thenReturn(ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageSender.send(eq(company), eq("whatsapp_lika"), eq("group-21"), eq("message")))
                .thenReturn(ClientMessageSendResult.failed(
                        "not_ready",
                        "WhatsApp API вернул HTTP 503. Ответ: {\"status\":\"not_ready\",\"authenticated\":true,\"state\":\"authenticated\",\"hasQr\":false,\"message\":\"WhatsApp client is not ready\"}"
                ));
        ReflectionTestUtils.invokeMethod(service, "sendMessage", state, company, manager, "message", now, null);

        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertEquals("not_ready", state.getLastErrorCode());
        assertEquals(1, state.getConsecutiveFailures());
        assertEquals(now.plusMinutes(ScheduledClientMessageService.DEFAULT_TRANSIENT_RETRY_MINUTES), state.getNextAttemptAt());
        verify(whatsAppAuthAlertService, never()).notifyAuthIssue(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void paymentReminderMovesFirstInvoiceToReminderStatusAfterSend() throws Exception {
        LocalDateTime statusChangedAt = LocalDateTime.of(2026, 5, 20, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 22, 10, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(78L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:15:2026-05-20T10:00")
                .companyId(25L)
                .orderId(15L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(25L);
        company.setGroupId("group-15");
        Manager manager = new Manager();
        manager.setClientId("client-15");
        manager.setPayText("Оплатите, пожалуйста.");
        Order order = new Order();
        order.setId(15L);
        order.setCompany(company);
        order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("Выставлен счет").build());
        order.setStatusChangedAt(statusChangedAt);
        order.setSum(BigDecimal.valueOf(1300));

        when(orderRepository.findByIdForMutation(15L)).thenReturn(java.util.Optional.of(order));
        String customTemplate = "Напоминание: {paymentInstruction}";
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_TEXT,
                ScheduledClientMessageService.DEFAULT_PAYMENT_REMINDER_TEXT
        )).thenReturn(customTemplate);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                ScheduledClientMessageService.DEFAULT_PAYMENT_INSTRUCTION_SOURCE
        )).thenReturn("TBANK_LINK");
        when(paymentLinkService.createForOrderInNewTransaction(15L)).thenReturn(new ManagerPaymentLinkResponse(
                "token", "", 15L, BigDecimal.valueOf(1300), 130000, "CREATED", "MANUAL_MOBILE_BANK",
                LocalDateTime.now().plusDays(90),
                "Перевод по номеру телефона: 89001234567",
                "Напоминание: Перевод по номеру телефона: 89001234567",
                "89001234567"
        ));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        TelegramTransferCopyButton copyButton = TelegramTransferCopyButton
                .fromFrozenTransferNumber("89001234567")
                .orElseThrow();
        when(messageSender.send(eq(company), eq("client-15"), eq("group-15"), anyString(), eq(copyButton)))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp"));
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1300));
        when(orderStatusTransitionService.changeStatusForOrder(15L, "Напоминание")).thenReturn(true);

        ReflectionTestUtils.invokeMethod(
                service,
                "sendOrderReminder",
                state,
                company,
                java.util.List.of("Выставлен счет", "Напоминание"),
                "Заказ уже не ожидает оплату",
                2,
                false,
                now
        );

        verify(messageSender).send(
                eq(company), eq("client-15"), eq("group-15"),
                org.mockito.ArgumentMatchers.contains("89001234567"), eq(copyButton)
        );
        verify(orderStatusTransitionService).changeStatusForOrder(15L, "Напоминание");
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
    }

    @Test
    void paymentReminderIsPausedWhileReviewRecoveryIsActive() {
        LocalDateTime statusChangedAt = LocalDateTime.of(2026, 5, 20, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 22, 10, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(180L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:15:2026-05-20T10:00")
                .companyId(25L)
                .orderId(15L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(25L);
        company.setGroupId("group-15");
        Order order = new Order();
        order.setId(15L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Выставлен счет").build());
        order.setStatusChangedAt(statusChangedAt);

        when(orderRepository.findByIdForMutation(15L)).thenReturn(java.util.Optional.of(order));
        when(reviewRecoveryHoldService.shouldPauseClientMessages(order)).thenReturn(true);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        )).thenReturn(ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(
                service,
                "sendOrderReminder",
                state,
                company,
                java.util.List.of("Выставлен счет", "Напоминание"),
                "Заказ уже не ожидает оплату",
                2,
                false,
                now
        );

        verify(messageSender, never()).send(any(), any(), any(), anyString());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertEquals("review_recovery_active", state.getLastErrorCode());
        assertEquals(now.plusMinutes(10), state.getNextAttemptAt());
    }

    @Test
    void recoveryNoticeSendsClientMessageAndMarksBatchNotified() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 22, 10, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(181L)
                .scenario(ClientMessageScenario.REVIEW_RECOVERY_NOTICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey(ReviewRecoveryNoticeScheduler.targetKey(55L))
                .companyId(25L)
                .orderId(15L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(25L);
        company.setGroupId("group-15");
        company.setPublicationProgressReportsEnabled(false);
        Manager manager = new Manager();
        manager.setClientId("client-15");
        Order order = new Order();
        order.setId(15L);
        order.setCompany(company);
        order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("Оплачено").build());
        order.setSum(BigDecimal.valueOf(1300));
        ReviewRecoveryBatch batch = ReviewRecoveryBatch.builder()
                .id(55L)
                .order(order)
                .status(ReviewRecoveryBatchStatus.COMPLETED)
                .build();

        when(reviewRecoveryBatchRepository.findById(55L)).thenReturn(java.util.Optional.of(batch));
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_TEXT,
                ScheduledClientMessageService.DEFAULT_REVIEW_RECOVERY_NOTICE_TEXT
        )).thenReturn(ScheduledClientMessageService.DEFAULT_REVIEW_RECOVERY_NOTICE_TEXT);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1300));
        when(messageSender.send(eq(company), eq("client-15"), eq("group-15"), anyString()))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp"));

        ReflectionTestUtils.invokeMethod(service, "sendReviewRecoveryNotice", state, company, now);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageSender).send(eq(company), eq("client-15"), eq("group-15"), messageCaptor.capture());
        assertTrue(messageCaptor.getValue().contains("Все отзывы по заказу №15 восстановлены"));
        verify(reviewRecoveryTaskService).markClientNotifiedAutomatically(55L);
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
    }

    @Test
    void clientTextReminderSendsOnlyForCurrentWaitingCycle() {
        LocalDateTime waitingChangedAt = LocalDateTime.of(2026, 5, 20, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 23, 10, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(79L)
                .scenario(ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("client-text:16:2026-05-20T10:00")
                .companyId(26L)
                .orderId(16L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(26L);
        company.setGroupId("group-16");
        Manager manager = new Manager();
        manager.setClientId("client-16");
        Order order = new Order();
        order.setId(16L);
        order.setCompany(company);
        order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("Новый").build());
        order.setWaitingForClient(true);
        order.setWaitingForClientChangedAt(waitingChangedAt);
        order.setSum(BigDecimal.valueOf(1300));

        when(orderRepository.findByIdForMutation(16L)).thenReturn(java.util.Optional.of(order));
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES,
                ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_STATUSES
        )).thenReturn(ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_STATUSES);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_TEXT,
                ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_TEXT
        )).thenReturn(ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_TEXT);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1300));
        when(messageSender.send(eq(company), eq("client-16"), eq("group-16"), anyString()))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp"));
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(service, "sendClientTextReminder", state, company, now);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageSender).send(eq(company), eq("client-16"), eq("group-16"), messageCaptor.capture());
        assertTrue(messageCaptor.getValue().contains("заказу №16"));
        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertNotNull(state.getNextAttemptAt());
    }

    @Test
    void synchronizingClientWaitingCreatesCurrentCycleAndClosesPreviousCycle() {
        LocalDateTime waitingChangedAt = LocalDateTime.of(2026, 7, 24, 20, 37, 52);
        ScheduledClientMessageState staleState = ScheduledClientMessageState.builder()
                .id(4_200L)
                .scenario(ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("client-text:25442:2026-07-18T14:50:19")
                .orderId(25_442L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(LocalDateTime.of(2026, 7, 24, 20, 54, 6))
                .build();
        Company company = new Company();
        company.setId(2_155L);
        Order order = new Order();
        order.setId(25_442L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Новый").build());
        order.setWaitingForClient(true);
        order.setWaitingForClientChangedAt(waitingChangedAt);

        when(stateRepository.findByOrderIdIn(List.of(25_442L))).thenReturn(List.of(staleState));
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.CLIENT_TEXT_REMINDER,
                "client-text:25442:2026-07-24T20:37:52"
        )).thenReturn(java.util.Optional.empty());
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES,
                ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_STATUSES
        )).thenReturn(ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_STATUSES);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_INTERVAL_DAYS,
                ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_INTERVAL_DAYS
        )).thenReturn(2);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertTrue(service.synchronizeClientTextReminderForOrder(order));

        ArgumentCaptor<ScheduledClientMessageState> states = ArgumentCaptor.forClass(ScheduledClientMessageState.class);
        verify(stateRepository, times(2)).save(states.capture());
        assertEquals(ScheduledMessageStateStatus.DONE, staleState.getStatus());
        ScheduledClientMessageState currentState = states.getAllValues().get(1);
        assertEquals("client-text:25442:2026-07-24T20:37:52", currentState.getTargetKey());
        assertEquals(waitingChangedAt.plusDays(2), currentState.getNextAttemptAt());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, currentState.getStatus());
    }

    @Test
    void paymentQueueIsSuppressedBeforeSendingWhenOrderIsAlreadySettled() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(901L)
                .scenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:24572:payment")
                .companyId(20L)
                .orderId(24572L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Order order = new Order();
        order.setId(24572L);
        LocalDateTime now = LocalDateTime.of(2026, 7, 24, 20, 30);
        when(stateRepository.findById(901L)).thenReturn(java.util.Optional.of(state));
        when(orderRepository.findByIdForMutation(24572L)).thenReturn(java.util.Optional.of(order));
        when(orderPaymentIntegrityService.hasSettledPaymentEvidence(order)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "processState", 901L, now);

        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(stateRepository).save(state);
        verify(messageSender, never()).send(any(), any(), any(), any());
        verify(paymentLinkService, never()).createForOrderInNewTransaction(any());
    }

    @Test
    void manualInvoiceStatusImmediatelyReplacesObsoleteInvoiceRetryWithPaymentTasks() {
        LocalDateTime previousChangedAt = LocalDateTime.of(2026, 8, 1, 0, 50);
        LocalDateTime currentChangedAt = LocalDateTime.of(2026, 8, 1, 0, 59, 38);
        ScheduledClientMessageState obsoleteInvoiceRetry = ScheduledClientMessageState.builder()
                .id(944L)
                .scenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:24466:" + previousChangedAt)
                .companyId(866L)
                .orderId(24_466L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(previousChangedAt.plusHours(2))
                .build();
        Company company = new Company();
        company.setId(866L);
        Order order = new Order();
        order.setId(24_466L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Выставлен счет").build());
        order.setStatusChangedAt(currentChangedAt);

        when(orderRepository.findByIdForMutation(24_466L)).thenReturn(Optional.of(order));
        when(stateRepository.findByOrderIdIn(List.of(24_466L))).thenReturn(List.of(obsoleteInvoiceRetry));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, true)).thenReturn(true);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED, true)).thenReturn(true);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_INTERVAL_DAYS,
                ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS
        )).thenReturn(2);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS,
                ScheduledClientMessageService.DEFAULT_PAYMENT_OVERDUE_DAYS
        )).thenReturn(30);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertTrue(service.ensureClientMessageStateAfterOrderStatusChange(24_466L));

        assertEquals(ScheduledMessageStateStatus.DONE, obsoleteInvoiceRetry.getStatus());
        assertNull(obsoleteInvoiceRetry.getNextAttemptAt());
        ArgumentCaptor<ScheduledClientMessageState> states = ArgumentCaptor.forClass(ScheduledClientMessageState.class);
        verify(stateRepository, times(3)).save(states.capture());
        List<ScheduledClientMessageState> saved = states.getAllValues();
        ScheduledClientMessageState reminder = saved.stream()
                .filter(state -> state.getScenario() == ClientMessageScenario.PAYMENT_REMINDER)
                .findFirst()
                .orElseThrow();
        ScheduledClientMessageState overdue = saved.stream()
                .filter(state -> state.getScenario() == ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION)
                .findFirst()
                .orElseThrow();
        assertEquals("order:24466:2026-08-01T00:59:38", reminder.getTargetKey());
        assertEquals(currentChangedAt.plusDays(2), reminder.getNextAttemptAt());
        assertEquals(currentChangedAt.plusDays(30), overdue.getNextAttemptAt());
        verify(attemptRepository).save(any(ScheduledClientMessageAttempt.class));
    }

    @Test
    void clientTextReminderAutoClearsWaitingFlagAfterSevenUnchangedDays() {
        LocalDateTime waitingChangedAt = LocalDateTime.of(2026, 5, 20, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 27, 10, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(80L)
                .scenario(ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("client-text:17:2026-05-20T10:00")
                .companyId(27L)
                .orderId(17L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(27L);
        company.setGroupId("group-17");
        Order order = new Order();
        order.setId(17L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Новый").build());
        order.setWaitingForClient(true);
        order.setWaitingForClientChangedAt(waitingChangedAt);
        order.setChanged(now.toLocalDate().minusDays(ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS));

        when(orderRepository.findByIdForMutation(17L)).thenReturn(java.util.Optional.of(order));

        ReflectionTestUtils.invokeMethod(service, "sendClientTextReminder", state, company, now);

        assertEquals(false, order.isWaitingForClient());
        assertNull(order.getWaitingForClientChangedAt());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(orderRepository).save(order);
        verify(messageSender, never()).send(any(), any(), any(), anyString());
    }

    @Test
    void liveDisabledClearsLegacyPauseCreatedByDryRunFailures() {
        LocalDateTime pausedUntil = LocalDateTime.of(2026, 5, 25, 1, 2);
        String reason = "За 10 мин. накоплено 20 ошибок. Последняя: client_messages_live_disabled - Авторассылка выключена настройкой";

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(false);
        when(appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAUSE_REASON, "")).thenReturn(reason);

        Boolean cleared = ReflectionTestUtils.invokeMethod(service, "clearLegacyDryRunPause", pausedUntil);

        assertTrue(Boolean.TRUE.equals(cleared));
        verify(appSettingService).setString(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL, "");
        verify(appSettingService).setString(AppSettingService.CLIENT_MESSAGES_PAUSE_REASON, "");
    }

    @Test
    void retryPaymentInvoiceSendsInvoiceAndCompletesStateWhenClientMessageSucceeds() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 5, 25, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(90L)
                .scenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:10:2026-05-25T10:00")
                .companyId(20L)
                .orderId(10L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(20L);
        company.setGroupId("group-10");
        Manager manager = new Manager();
        manager.setClientId("client-10");
        Order order = new Order();
        order.setId(10L);
        order.setCompany(company);
        order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("Опубликовано").build());
        order.setStatusChangedAt(changedAt);

        when(orderRepository.findByIdForMutation(10L)).thenReturn(java.util.Optional.of(order));
        when(orderPaymentMessageBuilder.publishedOrderPaymentMessageWithTransfer(order)).thenReturn(
                new OrderPaymentMessageBuilder.PreparedPaymentMessage("финальный счет", "2202208238396676")
        );
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderStatusNotificationService.sendMessageToClientChat(
                any(),
                eq(order),
                any(),
                any(),
                any(),
                any(),
                eq("2202208238396676")
        )).thenReturn("Выставлен счет");

        ReflectionTestUtils.invokeMethod(service, "retryPaymentInvoice", state, company, now);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SENT, attemptCaptor.getValue().getStatus());
        assertEquals(ClientMessageScenario.PAYMENT_INVOICE_RETRY, attemptCaptor.getValue().getScenario());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
        verify(orderStatusNotificationService).sendMessageToClientChat(
                eq("Опубликовано"), eq(order), eq("client-10"), eq("group-10"),
                eq("финальный счет"), eq("Выставлен счет"), eq("2202208238396676")
        );
    }

    @Test
    void retryPaymentInvoiceCompletesWithoutSingleInvoiceWhenOrderIsLinkedToCommonBilling() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(901L)
                .scenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:10:2026-05-25T10:00")
                .companyId(20L)
                .orderId(10L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(20L);
        Order order = new Order();
        order.setId(10L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Опубликовано").build());

        when(orderRepository.findByIdForMutation(10L)).thenReturn(java.util.Optional.of(order));
        when(commonBillingServiceProvider.getIfAvailable()).thenReturn(commonBillingService);
        when(commonBillingService.isOrderInActiveCommonInvoice(10L)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "retryPaymentInvoice", state, company, now);

        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SKIPPED, attemptCaptor.getValue().getStatus());
        assertEquals("common_billing_linked", attemptCaptor.getValue().getErrorCode());
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
        verify(orderPaymentMessageBuilder, never()).publishedOrderPaymentMessageWithTransfer(any());
        verify(orderStatusNotificationService, never()).sendMessageToClientChat(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void paymentReminderCompletesWithoutSingleReminderWhenOrderIsLinkedToCommonBilling() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(902L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:10:2026-05-25T10:00")
                .companyId(20L)
                .orderId(10L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(20L);
        Order order = new Order();
        order.setId(10L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Выставлен счет").build());

        when(orderRepository.findByIdForMutation(10L)).thenReturn(java.util.Optional.of(order));
        when(commonBillingServiceProvider.getIfAvailable()).thenReturn(commonBillingService);
        when(commonBillingService.isOrderInActiveCommonInvoice(10L)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(
                service,
                "sendOrderReminder",
                state,
                company,
                List.of("Выставлен счет", "Напоминание"),
                "Заказ уже не ожидает оплату",
                1,
                false,
                now
        );

        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SKIPPED, attemptCaptor.getValue().getStatus());
        assertEquals("common_billing_linked", attemptCaptor.getValue().getErrorCode());
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
        verify(messageSender, never()).send(any(), any(), any(), any());
        verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), any());
    }

    @Test
    void retryReviewCheckDeliverySendsReviewLinkAndCompletesStateWhenClientMessageSucceeds() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 5, 25, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(91L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:11:2026-05-25T10:00")
                .companyId(21L)
                .orderId(11L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(21L);
        company.setGroupId("group-11");
        Manager manager = new Manager();
        manager.setClientId("client-11");
        Order order = new Order();
        order.setId(11L);
        order.setCompany(company);
        order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("В проверку").build());
        order.setStatusChangedAt(changedAt);

        when(orderRepository.findByIdForMutation(11L)).thenReturn(java.util.Optional.of(order));
        when(reviewCheckMessageBuilder.reviewCheckMessage(order)).thenReturn("ссылка на проверку");
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderStatusNotificationService.sendMessageToClientChat(
                eq("В проверку"),
                eq(order),
                eq("client-11"),
                eq("group-11"),
                eq("ссылка на проверку"),
                eq("На проверке")
        )).thenReturn("На проверке");

        ReflectionTestUtils.invokeMethod(service, "retryReviewCheckDelivery", state, company, now);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SENT, attemptCaptor.getValue().getStatus());
        assertEquals(ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY, attemptCaptor.getValue().getScenario());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
    }

    @Test
    void retryBadReviewInvoiceCompletesWithoutSingleInvoiceWhenOrderIsLinkedToCommonBilling() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime lockedUntil = now.plusMinutes(5);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(921L)
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-invoice:order:121")
                .companyId(221L)
                .orderId(121L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode(ClientMessageStateSafety.TRANSACTION_IN_PROGRESS)
                .lockedUntil(lockedUntil)
                .build();
        Company company = new Company();
        company.setId(221L);
        Order order = new Order();
        order.setId(121L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());

        when(orderRepository.findByIdForMutation(121L)).thenReturn(java.util.Optional.of(order));
        when(stateRepository.findByIdForUpdate(921L)).thenReturn(Optional.of(state));
        when(stateRepository.findById(921L)).thenReturn(Optional.of(state));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true))
                .thenReturn(true);
        when(commonBillingServiceProvider.getIfAvailable()).thenReturn(commonBillingService);
        when(commonBillingService.isOrderInActiveCommonInvoice(121L)).thenReturn(true);

        Object prepared = ReflectionTestUtils.invokeMethod(
                service, "prepareBadReviewDelivery", 921L, now, lockedUntil, null
        );

        assertNull(prepared);
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SKIPPED, attemptCaptor.getValue().getStatus());
        assertEquals("common_billing_linked", attemptCaptor.getValue().getErrorCode());
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
        verify(orderPaymentMessageBuilder, never()).publishedOrderPaymentMessageWithTransfer(any());
        verify(messageSender, never()).send(any(), any(), any(), any());
        verify(paymentInvoiceRetryScheduler, never()).scheduleBadReviewAutoBan(any());
    }

    @Test
    void paymentOverdueAutomationDefersToActiveCommonInvoice() throws Exception {
        LocalDateTime changedAt = LocalDateTime.of(2026, 4, 20, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(922L)
                .scenario(ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:122:2026-04-20T10:00")
                .companyId(222L)
                .orderId(122L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Order order = new Order();
        order.setId(122L);
        order.setStatus(OrderStatus.builder().title("Выставлен счет").build());
        order.setStatusChangedAt(changedAt);

        when(orderRepository.findByIdForMutation(122L)).thenReturn(java.util.Optional.of(order));
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_CLOSED_ORDER_STATUSES,
                ScheduledClientMessageService.DEFAULT_CLOSED_ORDER_STATUSES
        )).thenReturn(ScheduledClientMessageService.DEFAULT_CLOSED_ORDER_STATUSES);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_STATUSES,
                ScheduledClientMessageService.DEFAULT_PAYMENT_OVERDUE_STATUSES
        )).thenReturn(ScheduledClientMessageService.DEFAULT_PAYMENT_OVERDUE_STATUSES);
        when(commonBillingServiceProvider.getIfAvailable()).thenReturn(commonBillingService);
        when(commonBillingService.isOrderInActiveCommonInvoice(122L)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "escalateOverduePayment", state, now);

        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor =
                ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SKIPPED, attemptCaptor.getValue().getStatus());
        assertEquals("common_billing_managed", attemptCaptor.getValue().getErrorCode());
        verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), anyString());
        verify(stateRepository).save(state);
    }

    @Test
    void paymentReturnReminderReusesCanonicalOrderCycle() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 8, 17, 13, 15, 56);
        Company company = new Company();
        company.setId(797L);
        Order order = new Order();
        order.setId(22382L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Напоминание").build());
        order.setStatusChangedAt(changedAt);

        ScheduledClientMessageState existing = ScheduledClientMessageState.builder()
                .id(1669786L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:22382:2026-08-17T13:15:56")
                .orderId(22382L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();

        when(orderRepository.findByIdForMutation(22382L)).thenReturn(Optional.of(order));
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.PAYMENT_REMINDER,
                "order:22382:2026-08-17T13:15:56"
        )).thenReturn(Optional.of(existing));

        assertTrue(service.enqueuePaymentReminderAfterFullReturn(22382L));

        assertEquals("order:22382:2026-08-17T13:15:56", existing.getTargetKey());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, existing.getStatus());
        assertEquals(ClientMessageTargetType.ORDER, existing.getTargetType());
        assertEquals(797L, existing.getCompanyId());
        assertEquals(22382L, existing.getOrderId());
        assertEquals("payment_return_reopened", existing.getLastErrorCode());
        assertNotNull(existing.getNextAttemptAt());
        assertNull(existing.getLockedUntil());
        verify(stateRepository).save(existing);
        verify(stateRepository, never()).findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.PAYMENT_REMINDER,
                "payment-return:22382:2026-08-17T13:15:56"
        );
    }

    @Test
    void paymentReturnReminderCreatesCanonicalOrderCycleWhenMissing() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 8, 17, 13, 15, 56);
        Company company = new Company();
        company.setId(797L);
        Order order = new Order();
        order.setId(22382L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Напоминание").build());
        order.setStatusChangedAt(changedAt);

        when(orderRepository.findByIdForMutation(22382L)).thenReturn(Optional.of(order));
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.PAYMENT_REMINDER,
                "order:22382:2026-08-17T13:15:56"
        )).thenReturn(Optional.empty());

        assertTrue(service.enqueuePaymentReminderAfterFullReturn(22382L));

        ArgumentCaptor<ScheduledClientMessageState> stateCaptor =
                ArgumentCaptor.forClass(ScheduledClientMessageState.class);
        verify(stateRepository).save(stateCaptor.capture());
        ScheduledClientMessageState saved = stateCaptor.getValue();
        assertEquals(ClientMessageScenario.PAYMENT_REMINDER, saved.getScenario());
        assertEquals("order:22382:2026-08-17T13:15:56", saved.getTargetKey());
        assertEquals(ClientMessageTargetType.ORDER, saved.getTargetType());
        assertEquals(797L, saved.getCompanyId());
        assertEquals(22382L, saved.getOrderId());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, saved.getStatus());
        assertEquals("payment_return_reopened", saved.getLastErrorCode());
        assertNotNull(saved.getNextAttemptAt());
        verify(stateRepository, never()).findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.PAYMENT_REMINDER,
                "payment-return:22382:2026-08-17T13:15:56"
        );
    }

    @Test
    void retryNowReconcilesPaymentBeforeProcessingFailedPaymentTask() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(501L)
                .scenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:77:2026-07-20T10:00")
                .orderId(77L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode("payment_instruction_failed")
                .lastErrorMessage("Старая платежная ссылка")
                .consecutiveFailures(3)
                .build();

        when(stateRepository.findById(501L)).thenReturn(Optional.of(state));
        when(transactionRunner.callInNewTransaction(
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<Boolean>>any()
        )).thenAnswer(invocation -> invocation.<java.util.function.Supplier<Boolean>>getArgument(0).get());
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(transactionRunner).runInNewTransaction(any(Runnable.class));
        when(stateRepository.lockActiveState(
                eq(501L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("state_transaction_in_progress"),
                anyString()
        )).thenAnswer(invocation -> {
            state.setLockedUntil(invocation.getArgument(2));
            state.setNextAttemptAt(null);
            state.setLastErrorCode(invocation.getArgument(3));
            return 1;
        });
        when(stateRepository.findByIdForUpdate(501L)).thenReturn(Optional.of(state));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED, true))
                .thenReturn(true);
        when(appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL, null))
                .thenReturn(null);
        when(orderRepository.findByIdForMutation(77L)).thenReturn(Optional.empty());

        ScheduledClientMessageService.ManualRetryResult result = service.retryNow(501L);

        assertTrue(result.attempted());
        assertEquals(ScheduledMessageStateStatus.DISABLED, result.status());
        assertEquals("company_missing", result.errorCode());
        verify(paymentLinkService).reconcileActiveLinkForOrder(77L);
        verify(stateRepository).save(state);
    }

    @Test
    void rolledBackStateIsQuarantinedUntilManualReview() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 16, 30);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(5819L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:24753:2026-08-01T14:00")
                .companyId(100L)
                .orderId(24753L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode("state_transaction_in_progress")
                .nextAttemptAt(now.minusMinutes(1))
                .lockedUntil(now.plusMinutes(5))
                .build();

        when(stateRepository.findByIdForUpdate(5819L)).thenReturn(Optional.of(state));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(transactionRunner).runInNewTransaction(any(Runnable.class));

        ReflectionTestUtils.invokeMethod(
                service,
                "quarantineRolledBackState",
                5819L,
                state.getLockedUntil(),
                now,
                new RuntimeException("transaction marked rollback-only")
        );

        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertEquals("state_transaction_outcome_uncertain", state.getLastErrorCode());
        assertTrue(state.getLastErrorMessage().contains("автоматический повтор остановлен"));
        assertEquals(1, state.getConsecutiveFailures());
        assertNull(state.getNextAttemptAt());
        assertNull(state.getLockedUntil());
        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor =
                ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.FAILED, attemptCaptor.getValue().getStatus());
        assertEquals("state_transaction_outcome_uncertain", attemptCaptor.getValue().getErrorCode());
        verify(stateRepository).save(state);
    }

    @Test
    void reconciliationDoesNotRearmStateWithUncertainTransactionOutcome() {
        LocalDateTime nextAttempt = LocalDateTime.of(2026, 8, 2, 10, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(5819L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:24753:2026-08-01T14:00")
                .companyId(100L)
                .orderId(24753L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode("state_transaction_outcome_uncertain")
                .nextAttemptAt(null)
                .build();
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.PAYMENT_REMINDER,
                state.getTargetKey()
        )).thenReturn(Optional.of(state));

        Boolean created = ReflectionTestUtils.invokeMethod(
                service,
                "ensureState",
                ClientMessageScenario.PAYMENT_REMINDER,
                ClientMessageTargetType.ORDER,
                state.getTargetKey(),
                state.getCompanyId(),
                state.getOrderId(),
                null,
                nextAttempt
        );

        assertEquals(Boolean.FALSE, created);
        assertNull(state.getNextAttemptAt());
    }

    @Test
    void reconciliationDoesNotRearmStateWhoseTransactionIsStillMarkedInProgress() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(5820L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:24754:2026-08-01T14:00")
                .companyId(100L)
                .orderId(24754L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode("state_transaction_in_progress")
                .nextAttemptAt(null)
                .build();
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.PAYMENT_REMINDER,
                state.getTargetKey()
        )).thenReturn(Optional.of(state));

        Boolean created = ReflectionTestUtils.invokeMethod(
                service,
                "ensureState",
                ClientMessageScenario.PAYMENT_REMINDER,
                ClientMessageTargetType.ORDER,
                state.getTargetKey(),
                state.getCompanyId(),
                state.getOrderId(),
                null,
                LocalDateTime.of(2026, 8, 2, 10, 0)
        );

        assertEquals(Boolean.FALSE, created);
        assertNull(state.getNextAttemptAt());
    }

    @Test
    void reconciliationRearmsDoneStateThatNeverSentForCurrentCycle() {
        LocalDateTime nextAttempt = LocalDateTime.of(2026, 8, 20, 14, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(5823L)
                .scenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:24888:2026-07-23T11:23:13")
                .companyId(100L)
                .orderId(24_888L)
                .status(ScheduledMessageStateStatus.DONE)
                .sentCount(0)
                .lastSuccessAt(null)
                .lastErrorCode(null)
                .build();
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.PAYMENT_INVOICE_RETRY,
                state.getTargetKey()
        )).thenReturn(Optional.of(state));

        Boolean created = ReflectionTestUtils.invokeMethod(
                service,
                "ensureState",
                ClientMessageScenario.PAYMENT_INVOICE_RETRY,
                ClientMessageTargetType.ORDER,
                state.getTargetKey(),
                state.getCompanyId(),
                state.getOrderId(),
                null,
                nextAttempt
        );

        assertEquals(Boolean.TRUE, created);
        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertEquals(nextAttempt, state.getNextAttemptAt());
        assertNull(state.getLockedUntil());
        verify(stateRepository).save(state);
    }

    @Test
    void reconciliationClosesActivePaymentAutomationForClosedOrders() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 13, 40);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(5824L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:25430:2026-08-18T10:00")
                .companyId(100L)
                .orderId(25_430L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(now.plusDays(1))
                .build();
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_CLOSED_ORDER_STATUSES,
                ScheduledClientMessageService.DEFAULT_CLOSED_ORDER_STATUSES
        )).thenReturn(ScheduledClientMessageService.DEFAULT_CLOSED_ORDER_STATUSES);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_CANDIDATE_LIMIT,
                ScheduledClientMessageService.DEFAULT_CANDIDATE_LIMIT
        )).thenReturn(ScheduledClientMessageService.DEFAULT_CANDIDATE_LIMIT);
        when(stateRepository.findActiveOrderAutomationStatesByOrderStatuses(
                any(),
                eq(ScheduledMessageStateStatus.ACTIVE),
                any(),
                any()
        )).thenReturn(List.of(state));

        Integer closed = ReflectionTestUtils.invokeMethod(service, "closeInactivePaymentAutomationStates", now);

        assertEquals(1, closed);
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        assertNull(state.getLockedUntil());
        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor =
                ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SKIPPED, attemptCaptor.getValue().getStatus());
        assertEquals("order_closed", attemptCaptor.getValue().getErrorCode());
        verify(stateRepository).save(state);
    }

    @Test
    void workerDoesNotProcessStateOwnedByAnotherClaim() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expectedLockedUntil = now.plusMinutes(5).withNano(123_456_000);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(5821L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:24755:2026-08-01T14:00")
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lockedUntil(expectedLockedUntil.plusMinutes(1))
                .build();
        when(stateRepository.findByIdForUpdate(5821L)).thenReturn(Optional.of(state));

        ReflectionTestUtils.invokeMethod(
                service,
                "processClaimedState",
                5821L,
                now,
                expectedLockedUntil
        );

        verify(stateRepository, never()).findById(5821L);
        verify(stateRepository, never()).save(any(ScheduledClientMessageState.class));
        verify(attemptRepository, never()).save(any(ScheduledClientMessageAttempt.class));
    }

    @Test
    void rollbackQuarantineDoesNotOverwriteAnotherClaim() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 16, 30);
        LocalDateTime expectedLockedUntil = now.plusMinutes(5);
        LocalDateTime otherLockedUntil = expectedLockedUntil.plusMinutes(1);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(5822L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:24756:2026-08-01T14:00")
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode("state_transaction_in_progress")
                .nextAttemptAt(null)
                .lockedUntil(otherLockedUntil)
                .build();
        when(stateRepository.findByIdForUpdate(5822L)).thenReturn(Optional.of(state));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(transactionRunner).runInNewTransaction(any(Runnable.class));

        ReflectionTestUtils.invokeMethod(
                service,
                "quarantineRolledBackState",
                5822L,
                expectedLockedUntil,
                now,
                new RuntimeException("old worker failed")
        );

        assertEquals("state_transaction_in_progress", state.getLastErrorCode());
        assertEquals(otherLockedUntil, state.getLockedUntil());
        verify(stateRepository, never()).save(any(ScheduledClientMessageState.class));
        verify(attemptRepository, never()).save(any(ScheduledClientMessageAttempt.class));
    }

    @Test
    void autoArchiveStaleReviewCheckIsPostponedWhileCommonInvoiceIsActive() throws Exception {
        LocalDateTime changedAt = LocalDateTime.of(2026, 4, 20, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(92L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_AUTO_ARCHIVE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:13:2026-04-20T10:00")
                .companyId(23L)
                .orderId(13L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lockedUntil(now.plusMinutes(5))
                .build();
        Order order = new Order();
        order.setId(13L);
        order.setStatus(OrderStatus.builder().title("На проверке").build());
        order.setStatusChangedAt(changedAt);

        when(orderRepository.findByIdForMutation(13L)).thenReturn(Optional.of(order));
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES,
                ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_STATUSES
        )).thenReturn(ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_STATUSES);
        when(commonBillingServiceProvider.getIfAvailable()).thenReturn(commonBillingService);
        when(commonBillingService.isOrderInActiveCommonInvoice(13L)).thenReturn(true);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        )).thenReturn(ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(service, "autoArchiveStaleReviewCheck", state, now);

        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertNotNull(state.getNextAttemptAt());
        assertNull(state.getLastErrorCode());
        assertNull(state.getLockedUntil());
        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor =
                ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SKIPPED, attemptCaptor.getValue().getStatus());
        assertEquals("common_billing_linked", attemptCaptor.getValue().getErrorCode());
        verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), anyString());
        verify(stateRepository).save(state);
    }

    @Test
    void autoArchiveStaleReviewCheckChangesOrderStatusAndCompletesState() throws Exception {
        LocalDateTime changedAt = LocalDateTime.of(2026, 4, 20, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(93L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_AUTO_ARCHIVE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:13:2026-04-20T10:00")
                .companyId(23L)
                .orderId(13L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Order order = new Order();
        order.setId(13L);
        order.setStatus(OrderStatus.builder().title("На проверке").build());
        order.setStatusChangedAt(changedAt);

        when(orderRepository.findByIdForMutation(13L)).thenReturn(java.util.Optional.of(order));
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES,
                ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_STATUSES
        )).thenReturn(ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_STATUSES);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_AUTO_ARCHIVE_DAYS,
                ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_AUTO_ARCHIVE_DAYS
        )).thenReturn(30);
        when(orderStatusTransitionService.changeStatusForOrder(13L, "Архив")).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "autoArchiveStaleReviewCheck", state, now);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SENT, attemptCaptor.getValue().getStatus());
        assertEquals(ClientMessageScenario.REVIEW_CHECK_AUTO_ARCHIVE, attemptCaptor.getValue().getScenario());
        assertEquals("system", attemptCaptor.getValue().getChannel());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
    }

    @Test
    void autoBanAfterBadReviewsMovesOrderToBanWhenFinalInvoiceStillUnpaid() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 28, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(94L)
                .scenario(ClientMessageScenario.BAD_REVIEW_AUTO_BAN)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-auto-ban:order:14")
                .companyId(24L)
                .orderId(14L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Order order = new Order();
        order.setId(14L);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED, true)).thenReturn(true);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_DELAY_DAYS,
                ScheduledClientMessageService.DEFAULT_BAD_REVIEW_AUTO_BAN_DELAY_DAYS
        )).thenReturn(2);
        when(orderRepository.findByIdForMutation(14L)).thenReturn(java.util.Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(14L))
                .thenReturn(new BadReviewTaskSummary(2, 0, 2, 0, BigDecimal.valueOf(600), BigDecimal.ZERO));
        when(orderStatusTransitionService.changeStatusForOrder(14L, "Бан")).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "autoBanAfterBadReviews", state, now);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SENT, attemptCaptor.getValue().getStatus());
        assertEquals(ClientMessageScenario.BAD_REVIEW_AUTO_BAN, attemptCaptor.getValue().getScenario());
        assertEquals("system", attemptCaptor.getValue().getChannel());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(orderStatusTransitionService).changeStatusForOrder(14L, "Бан");
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
    }

    @Test
    void badReviewDeliveryPersistsTokenAndFinalizesAfterExternalSend() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(501L)
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-invoice:order:50")
                .companyId(20L)
                .orderId(50L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(LocalDateTime.now().plusHours(1))
                .build();
        Company company = new Company();
        company.setId(20L);
        company.setGroupId("group-20");
        Manager manager = new Manager();
        manager.setClientId("client-20");
        Order order = new Order();
        order.setId(50L);
        order.setCompany(company);
        order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true)).thenReturn(true);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true)).thenReturn(true);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(stateRepository.findByScenarioAndTargetKey(
                ClientMessageScenario.BAD_REVIEW_INVOICE,
                "bad-review-invoice:order:50"
        )).thenReturn(Optional.of(state));
        when(stateRepository.lockActiveState(eq(501L), any(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    state.setLockedUntil(invocation.getArgument(2));
                    state.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_IN_PROGRESS);
                    state.setNextAttemptAt(null);
                    return 1;
                });
        when(transactionRunner.callInNewTransaction(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(transactionRunner).runInNewTransaction(any(Runnable.class));
        when(stateRepository.findByIdForUpdate(501L)).thenReturn(Optional.of(state));
        when(stateRepository.findById(501L)).thenReturn(Optional.of(state));
        when(orderRepository.findByIdForMutation(50L)).thenReturn(Optional.of(order));
        when(companyRepository.findByIdForCompanyDto(20L)).thenReturn(Optional.of(company));
        when(orderPaymentMessageBuilder.publishedOrderPaymentMessageWithTransfer(order)).thenReturn(
                new OrderPaymentMessageBuilder.PreparedPaymentMessage("К оплате: 1300 руб.", "2202208238396676")
        );
        TelegramTransferCopyButton copyButton = TelegramTransferCopyButton
                .fromFrozenTransferNumber("2202208238396676")
                .orElseThrow();
        when(messageSender.send(company, "client-20", "group-20", "К оплате: 1300 руб.", copyButton))
                .thenReturn(ClientMessageSendResult.sent("whatsapp"));

        service.deliverBadReviewInvoiceImmediately(7L, 50L);

        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertEquals("SENT", state.getDeliveryStatus());
        assertNotNull(state.getDeliveryToken());
        assertEquals(7L, state.getDeliveryTaskId());
        org.mockito.InOrder deliveryOrder = inOrder(transactionRunner, messageSender);
        deliveryOrder.verify(transactionRunner, times(2)).callInNewTransaction(any());
        deliveryOrder.verify(messageSender).send(
                company, "client-20", "group-20", "К оплате: 1300 руб.", copyButton
        );
        deliveryOrder.verify(transactionRunner).callInNewTransaction(any());
        deliveryOrder.verify(transactionRunner).runInNewTransaction(any(Runnable.class));
    }

    @Test
    void disabledBadReviewScenarioPostponesDurableStateInsteadOfCompletingIt() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime lockedUntil = now.plusMinutes(5);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(502L)
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-invoice:order:51")
                .orderId(51L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode(ClientMessageStateSafety.TRANSACTION_IN_PROGRESS)
                .lockedUntil(lockedUntil)
                .build();
        when(stateRepository.findByIdForUpdate(502L)).thenReturn(Optional.of(state));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true))
                .thenReturn(false);

        Object prepared = ReflectionTestUtils.invokeMethod(
                service, "prepareBadReviewDelivery", 502L, now, lockedUntil, null
        );

        assertNull(prepared);
        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertEquals("bad_review_invoice_disabled", state.getLastErrorCode());
        assertNotNull(state.getNextAttemptAt());
        assertNull(state.getLockedUntil());
        verify(messageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void liveOffSkipsImmediateBadReviewDeliveryWithoutConsumingState() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(507L)
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-invoice:order:56")
                .orderId(56L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(LocalDateTime.now().plusHours(1))
                .build();
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true)).thenReturn(true);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true)).thenReturn(true);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(false);

        service.deliverBadReviewInvoiceImmediately(11L, 56L);

        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertNotNull(state.getNextAttemptAt());
        verify(stateRepository, never()).findByScenarioAndTargetKey(any(), anyString());
        verify(messageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void settledPaymentSuppressesBadReviewInvoiceBeforeMessageRendering() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime lockedUntil = now.plusMinutes(5);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(503L)
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-invoice:order:52")
                .orderId(52L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode(ClientMessageStateSafety.TRANSACTION_IN_PROGRESS)
                .lockedUntil(lockedUntil)
                .build();
        Order order = new Order();
        order.setId(52L);
        when(stateRepository.findByIdForUpdate(503L)).thenReturn(Optional.of(state));
        when(stateRepository.findById(503L)).thenReturn(Optional.of(state));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true))
                .thenReturn(true);
        when(orderRepository.findByIdForMutation(52L)).thenReturn(Optional.of(order));
        when(orderPaymentIntegrityService.hasSettledPaymentEvidence(order)).thenReturn(true);

        Object prepared = ReflectionTestUtils.invokeMethod(
                service, "prepareBadReviewDelivery", 503L, now, lockedUntil, null
        );

        assertNull(prepared);
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        verify(orderPaymentMessageBuilder, never()).publishedOrderPaymentMessageWithTransfer(any());
        verify(messageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void badReviewAmountFailureKeepsDurableStateForRetry() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime lockedUntil = now.plusMinutes(5);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(505L)
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-invoice:order:54")
                .companyId(24L)
                .orderId(54L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode(ClientMessageStateSafety.TRANSACTION_IN_PROGRESS)
                .lockedUntil(lockedUntil)
                .build();
        Company company = new Company();
        company.setId(24L);
        Order order = new Order();
        order.setId(54L);
        order.setCompany(company);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());
        when(stateRepository.findByIdForUpdate(505L)).thenReturn(Optional.of(state));
        when(stateRepository.findById(505L)).thenReturn(Optional.of(state));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true))
                .thenReturn(true);
        when(orderRepository.findByIdForMutation(54L)).thenReturn(Optional.of(order));
        when(companyRepository.findByIdForCompanyDto(24L)).thenReturn(Optional.of(company));
        when(orderPaymentMessageBuilder.publishedOrderPaymentMessageWithTransfer(order))
                .thenThrow(new IllegalStateException("payable summary unavailable"));

        Object prepared = ReflectionTestUtils.invokeMethod(
                service, "prepareBadReviewDelivery", 505L, now, lockedUntil, 9L
        );

        assertNull(prepared);
        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertEquals("payment_instruction_failed", state.getLastErrorCode());
        assertNotNull(state.getNextAttemptAt());
        assertNull(state.getLockedUntil());
        verify(messageSender, never()).send(any(), any(), any(), any());
        verify(paymentIssueReminderService).notifyOrderIssueAfterCommit(
                eq(54L),
                eq(PaymentIssueReminderService.SOURCE_PAYMENT_FAIL_CLOSED),
                eq(54L),
                eq("Платёж требует внимания: заказ №54"),
                contains("payment_instruction_failed")
        );
    }

    @Test
    void unknownExternalOutcomeStopsAutomaticReplayWithTokenRetained() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 30);
        Company company = new Company();
        company.setId(25L);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(506L)
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-invoice:order:55")
                .companyId(25L)
                .orderId(55L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .deliveryToken("delivery-506")
                .deliveryStatus("PREPARED")
                .deliveryMessage("К оплате: 1300 руб.")
                .deliveryTaskId(10L)
                .deliveryPreparedAt(now.minusSeconds(1))
                .build();
        Class<?> preparedType = java.util.Arrays.stream(ScheduledClientMessageService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("PreparedBadReviewDelivery"))
                .findFirst()
                .orElseThrow();
        java.lang.reflect.Constructor<?> constructor = preparedType.getDeclaredConstructor(
                Long.class, Long.class, String.class, Company.class,
                String.class, String.class, String.class, String.class
        );
        constructor.setAccessible(true);
        Object prepared = constructor.newInstance(
                506L, 55L, "delivery-506", company,
                "client-25", "group-25", "К оплате: 1300 руб.", null
        );
        when(stateRepository.findByIdForUpdate(506L)).thenReturn(Optional.of(state));

        ReflectionTestUtils.invokeMethod(
                service,
                "finalizeBadReviewDelivery",
                prepared,
                ClientMessageSendResult.failed("transport_exception", "timeout"),
                true,
                now,
                50L
        );

        assertEquals("UNKNOWN", state.getDeliveryStatus());
        assertEquals("delivery-506", state.getDeliveryToken());
        assertEquals(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN, state.getLastErrorCode());
        assertNull(state.getNextAttemptAt());
        assertNull(state.getLockedUntil());
        verify(stateRepository).save(state);
    }

    @Test
    void recoversUncertainBadReviewDeliveryAndSchedulesFreshInvoiceWhenOldLinkExpired() {
        LocalDateTime preparedAt = LocalDateTime.of(2026, 8, 16, 14, 20);
        String message = "Ссылка на оплату: https://o-ogo.ru/pay/JrSKZEp7DdcPEwatdp7RQp8vk5Jemu6J";
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(510L)
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-invoice:order:59")
                .companyId(25L)
                .orderId(59L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN)
                .lastErrorMessage("Исход внешней отправки не определен")
                .deliveryStatus("UNKNOWN")
                .deliveryMessage(message)
                .deliveryPreparedAt(preparedAt)
                .build();
        ScheduledClientMessageAttempt sentAttempt = ScheduledClientMessageAttempt.builder()
                .stateId(510L)
                .status(ScheduledMessageAttemptStatus.SENT)
                .messagePreview(message)
                .attemptedAt(preparedAt.plusMinutes(1))
                .build();
        Order order = new Order();
        order.setId(59L);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());

        when(stateRepository.findById(510L)).thenReturn(Optional.of(state));
        when(paymentLinkService.reconcileActiveLinkForOrder(59L)).thenReturn(
                new PaymentLinkService.PaymentLinkReconcileResult(
                        900L,
                        PaymentLinkStatus.CREATED,
                        PaymentLinkStatus.EXPIRED,
                        true
                )
        );
        when(transactionRunner.callInNewTransaction(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        when(stateRepository.findByIdForUpdate(510L)).thenReturn(Optional.of(state));
        when(attemptRepository.findFirstByStateIdAndStatusOrderByAttemptedAtDescIdDesc(
                510L,
                ScheduledMessageAttemptStatus.SENT
        )).thenReturn(Optional.of(sentAttempt));
        when(orderRepository.findByIdForMutation(59L)).thenReturn(Optional.of(order));
        when(orderPaymentIntegrityService.hasSettledPaymentEvidence(order)).thenReturn(false);
        when(commonBillingServiceProvider.getIfAvailable()).thenReturn(null);

        Optional<ScheduledClientMessageService.RecoveredBadReviewDeliveryResult> result =
                service.recoverUncertainBadReviewInvoiceDelivery(510L, false);

        assertTrue(result.isPresent());
        assertTrue(result.get().retryScheduled());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertEquals(ScheduledClientMessageService.AUTO_RECOVERED_ERROR_CODE, state.getLastErrorCode());
        assertNull(state.getDeliveryStatus());
        assertNull(state.getDeliveryMessage());
        assertNotNull(state.getNextAttemptAt());
        assertNull(state.getLockedUntil());
        assertEquals(1, state.getSentCount());
        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor =
                ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SKIPPED, attemptCaptor.getValue().getStatus());
        assertEquals(ScheduledClientMessageService.AUTO_RECOVERED_ERROR_CODE, attemptCaptor.getValue().getErrorCode());
    }

    @Test
    void recoversUncertainBadReviewDeliveryWithoutDuplicateWhenLinkStillActive() {
        LocalDateTime preparedAt = LocalDateTime.of(2026, 8, 16, 14, 20);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(511L)
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-invoice:order:60")
                .companyId(25L)
                .orderId(60L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN)
                .deliveryStatus("UNKNOWN")
                .deliveryMessage("Ссылка на оплату: https://o-ogo.ru/pay/active-token")
                .deliveryPreparedAt(preparedAt)
                .build();
        when(stateRepository.findById(511L)).thenReturn(Optional.of(state));
        when(paymentLinkService.reconcileActiveLinkForOrder(60L)).thenReturn(
                new PaymentLinkService.PaymentLinkReconcileResult(
                        901L,
                        PaymentLinkStatus.CREATED,
                        PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
                        false
                )
        );
        when(transactionRunner.callInNewTransaction(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        when(stateRepository.findByIdForUpdate(511L)).thenReturn(Optional.of(state));
        when(attemptRepository.findFirstByStateIdAndStatusOrderByAttemptedAtDescIdDesc(
                511L,
                ScheduledMessageAttemptStatus.SENT
        )).thenReturn(Optional.empty());
        when(attemptRepository.findFirstByScenarioAndTargetKeyAndStatusOrderByAttemptedAtDescIdDesc(
                ClientMessageScenario.BAD_REVIEW_INVOICE,
                "bad-review-invoice:order:60",
                ScheduledMessageAttemptStatus.SENT
        )).thenReturn(Optional.empty());

        Optional<ScheduledClientMessageService.RecoveredBadReviewDeliveryResult> result =
                service.recoverUncertainBadReviewInvoiceDelivery(511L, true);

        assertTrue(result.isPresent());
        assertEquals(false, result.get().retryScheduled());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertEquals("SENT", state.getDeliveryStatus());
        assertNull(state.getNextAttemptAt());
        assertNull(state.getLastErrorCode());
        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor =
                ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SENT, attemptCaptor.getValue().getStatus());
    }
    @Test
    void disabledAutoBanRemainsActiveForRetry() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(504L)
                .scenario(ClientMessageScenario.BAD_REVIEW_AUTO_BAN)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-auto-ban:order:53")
                .orderId(53L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED, true))
                .thenReturn(false);
        when(stateRepository.findById(504L)).thenReturn(Optional.of(state));

        ReflectionTestUtils.invokeMethod(service, "processState", 504L, now);

        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertEquals("bad_review_auto_ban_disabled", state.getLastErrorCode());
        assertNotNull(state.getNextAttemptAt());
        verify(companyRepository, never()).findByIdForCompanyDto(any());
        verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), anyString());
    }

    @Test
    void autoBanSummaryFailureKeepsStateForRetry() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 13, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(508L)
                .scenario(ClientMessageScenario.BAD_REVIEW_AUTO_BAN)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-auto-ban:order:57")
                .orderId(57L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Order order = new Order();
        order.setId(57L);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED, true))
                .thenReturn(true);
        when(orderRepository.findByIdForMutation(57L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(57L))
                .thenThrow(new IllegalStateException("summary unavailable"));

        ReflectionTestUtils.invokeMethod(service, "autoBanAfterBadReviews", state, now);

        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertEquals("bad_review_summary_failed", state.getLastErrorCode());
        assertNotNull(state.getNextAttemptAt());
        verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), anyString());
    }

    @Test
    void settledPaymentCancelsAutoBanBeforeStatusMutation() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 13, 15);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(509L)
                .scenario(ClientMessageScenario.BAD_REVIEW_AUTO_BAN)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-auto-ban:order:58")
                .orderId(58L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Order order = new Order();
        order.setId(58L);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED, true))
                .thenReturn(true);
        when(orderRepository.findByIdForMutation(58L)).thenReturn(Optional.of(order));
        when(orderPaymentIntegrityService.hasSettledPaymentEvidence(order)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "autoBanAfterBadReviews", state, now);

        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor =
                ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(OrderPaymentIntegrityService.SUPPRESSED_ERROR_CODE,
                attemptCaptor.getValue().getErrorCode());
        assertNull(state.getLastErrorCode());
        verify(badReviewTaskService, never()).getSummaryForOrder(any());
        verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), anyString());
    }
}
