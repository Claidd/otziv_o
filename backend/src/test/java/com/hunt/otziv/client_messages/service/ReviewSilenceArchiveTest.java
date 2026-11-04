package com.hunt.otziv.client_messages.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.*;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageReconciliationService;
import com.hunt.otziv.client_chat_control.api.ReviewArchiveHistory;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import com.hunt.otziv.p_products.model.*;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.service.OrderPaymentIntegrityService;
import com.hunt.otziv.u_users.api.AutomationNoticeRecipients;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class ReviewSilenceArchiveTest {
    @Mock com.hunt.otziv.payments.api.SpecialistPaymentReviewGate paymentReviewGate;
    @Mock ScheduledClientMessageStateRepository stateRepository;
    @Mock ScheduledClientMessageAttemptRepository attemptRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderDeliveryControlContext orderDeliveryContext;
    @Mock com.hunt.otziv.client_messages.api.OrderManualContactReservation manualContactReservation;
    @Mock OrderStatusTransitionService orderStatusTransitionService;
    @Mock OrderPaymentIntegrityService orderPaymentIntegrityService;
    @Mock AppSettingService appSettingService;
    @Mock com.hunt.otziv.client_chat_control.api.ReminderContactHistory archiveChatHistory;
    @Mock ReviewArchiveHistory reviewArchiveHistory;
    @Mock com.hunt.otziv.review_recovery.service.ReviewRecoveryHoldService reviewRecoveryHoldService;
    @Mock AutomationNoticeRecipients archiveNoticeRecipients;
    @Mock ReviewArchiveNotifications archiveNotifications;
    @Mock ClientMessageTransactionRunner transactionRunner;
    @Mock ObjectProvider<CommonBillingService> commonBillingServiceProvider;
    @Mock CommonBillingService billing;
    @Mock ArchiveCompanyMessageCandidateRepository archiveCandidateRepository;
    @Spy ClientMessageTime messageTime = new ClientMessageTime();
    @Spy ClientMessageSlotPlanner slotPlanner = new ClientMessageSlotPlanner();
    @InjectMocks ScheduledClientMessageService service;
    AutoCloseable mocks;
    final LocalDateTime now = LocalDateTime.now();
    ScheduledClientMessageState state;
    Order order;
    Company company;
    OrderDeliveryControlContext.Snapshot source;

    @BeforeEach void setup() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        when(appSettingService.getBooleanFreshFailClosed(anyString(),anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettingService.getBoolean(anyString(),anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettingService.getString(anyString(),anyString())).thenAnswer(i -> i.getArgument(1));
        when(appSettingService.getInt(anyString(),anyInt())).thenAnswer(i -> i.getArgument(1));
        when(transactionRunner.callInNewTransaction(any())).thenAnswer(i -> ((Supplier<?>)i.getArgument(0)).get());
        when(transactionRunner.callInPreparationTransaction(any())).thenAnswer(i -> ((Supplier<?>)i.getArgument(0)).get());
        company = new Company(); company.setId(20L); company.setTitle("Fixture company"); company.setGroupId("fixture-group");
        company.setStatusChangedAt(now); company.setStatus(com.hunt.otziv.c_companies.model.CompanyStatus.builder().title("В работе").build());
        var manager = new Manager(); manager.setId(2L); manager.setClientId("fixture-client"); company.setManager(manager);
        order = new Order(); order.setId(77L); order.setCompany(company); order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("На проверке").build()); order.setStatusChangedAt(now.minusDays(20));
        order.setClientMessageGeneration(2);
        source = new OrderDeliveryControlContext.Snapshot(77L,2L,"На проверке",2,order.getStatusChangedAt(),false,null);
        state = ScheduledClientMessageState.builder().id(30L).orderId(77L).companyId(20L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER).status(ScheduledMessageStateStatus.ACTIVE)
                .targetKey("order:77:"+source.cycleAt().withNano(0)).sentCount(7).lastSuccessAt(now.minusDays(3)).build();
        when(stateRepository.findById(30L)).thenReturn(Optional.of(state));
        when(reviewArchiveHistory.inspect(any(),any())).thenAnswer(i -> new ReviewArchiveHistory.Check(
                ReviewArchiveHistory.Outcome.CLEAR,"History checked",i.getArgument(1),now,0,"fixture"));
        when(stateRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(state));
        when(orderRepository.findById(77L)).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForMutation(77L)).thenReturn(Optional.of(order));
        when(orderDeliveryContext.read(77L)).thenReturn(Optional.of(source));
        when(orderDeliveryContext.lock(77L)).thenReturn(Optional.of(source));
        when(commonBillingServiceProvider.getObject()).thenReturn(billing);
        when(billing.archiveReviewMembershipAfterReminders(77L)).thenReturn(Map.of());
        when(archiveNoticeRecipients.archiveRecipients(2L)).thenReturn(List.of(10L,11L));
        when(orderStatusTransitionService.changeStatusForOrder(77L,"Архив")).thenAnswer(i -> {
            order.setStatus(OrderStatus.builder().title("Архив").build());
            company.getStatus().setTitle("На стопе");
            return true;
        });
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(eq(ClientMessageScenario.ARCHIVE_REORDER_OFFER),anyString()))
                .thenReturn(Optional.empty());
    }
    @AfterEach void close() throws Exception { mocks.close(); }

    private void waitForClientTexts() throws Exception {
        order.setStatus(OrderStatus.builder().title("Новый").build());
        order.setWaitingForClient(true);
        order.setWaitingForClientChangedAt(now.minusDays(20));
        state.setScenario(ClientMessageScenario.CLIENT_TEXT_REMINDER);
        state.setTargetKey("client-text:77:" + order.getWaitingForClientChangedAt().withNano(0));
        source = new OrderDeliveryControlContext.Snapshot(77L,2L,"Новый",2,
                order.getStatusChangedAt(),true,order.getWaitingForClientChangedAt());
        when(orderDeliveryContext.read(77L)).thenReturn(Optional.of(source));
        when(orderDeliveryContext.lock(77L)).thenReturn(Optional.of(source));
        when(orderStatusTransitionService.archiveAfterClientTextReminders(77L)).thenAnswer(i -> {
            order.setStatus(OrderStatus.builder().title("Архив").build());
            order.setWaitingForClient(false);
            company.getStatus().setTitle("На стопе");
            return true;
        });
    }

    @ParameterizedTest @ValueSource(strings={"worker","pause","scenario","archive","changed_during_history"})
    void autoresponderSettingsBlockArchiveAndResumeWithoutLosingTheCycle(String condition) throws Exception {
        waitForClientTexts();
        String key=switch(condition) {
            case "worker" -> AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED;
            case "archive" -> AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_AUTO_ARCHIVE_ENABLED;
            default -> AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_ENABLED;
        };
        if (condition.equals("pause")) when(appSettingService.getStringFresh(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL,null)).thenReturn(now.plusHours(1).toString());
        else if (condition.equals("changed_during_history")) when(reviewArchiveHistory.inspect(any(),any())).thenAnswer(i -> {
            when(appSettingService.getBooleanFreshFailClosed(key,true)).thenReturn(false);
            return new ReviewArchiveHistory.Check(ReviewArchiveHistory.Outcome.CLEAR,"checked",i.getArgument(1),now,0,"fixture");
        });
        else when(appSettingService.getBooleanFreshFailClosed(key,true)).thenReturn(false);
        assertThat(service.archiveAfterReminders(30).archived()).isFalse();
        assertThat(state.getSentCount()).isEqualTo(7);
        assertThat(order.isWaitingForClient()).isTrue();
        verifyNoInteractions(billing,archiveNotifications);
        when(appSettingService.getBooleanFreshFailClosed(key,true)).thenReturn(true);
        when(appSettingService.getStringFresh(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL,null)).thenReturn(null);
        doReturn(new ReviewArchiveHistory.Check(ReviewArchiveHistory.Outcome.CLEAR,"checked",state.getLastSuccessAt(),now,0,"fixture"))
                .when(reviewArchiveHistory).inspect(any(),any());
        assertThat(service.archiveAfterReminders(30).archived()).isTrue();
        assertThat(state.getSentCount()).isEqualTo(7);
    }

    @Test void clientTextSilenceArchivesNewOrderAndQueuesOneOfferAfterTheLastOrder() throws Exception {
        waitForClientTexts();
        assertThat(service.archiveAfterReminders(30).archived()).isTrue();
        assertThat(order.isWaitingForClient()).isFalse();
        assertThat(company.getStatus().getTitle()).isEqualTo("На стопе");
        assertThat(state.getStatus()).isEqualTo(ScheduledMessageStateStatus.DONE);
        verify(orderStatusTransitionService).archiveAfterClientTextReminders(77L);
        verify(orderStatusTransitionService,never()).changeStatusForOrder(anyLong(),anyString());
        verify(billing).archiveReviewMembershipAfterReminders(77L);
        verify(archiveNotifications).recordArchive(eq(30L),eq(77L),eq(state.getTargetKey()),eq(7),any(),
                argThat(time -> time.isAfter(now.plusMonths(2))),contains("поставлено в очередь"));
        verify(archiveNotifications).enqueue(eq(30L),eq("client"),argThat(e -> e.text().contains("Тексты или пожелания")));
        assertThat(service.archiveAfterReminders(30).archived()).isFalse();
        verify(stateRepository,times(1)).findByScenarioAndTargetKeyForUpdate(eq(ClientMessageScenario.ARCHIVE_REORDER_OFFER),anyString());
    }

    @ParameterizedTest @ValueSource(strings={"window","limit","gap","paused"})
    void archiveNoticesUseTheSharedAutoresponderBudget(String condition) throws Exception {
        waitForClientTexts();
        LocalDateTime local=LocalDateTime.of(2026,11,3,condition.equals("window")?22:11,0);
        LocalDateTime storage=messageTime.toStorageTime(local);
        doReturn(local).when(messageTime).nowIrkutsk();
        doReturn(storage).when(messageTime).nowStorage();
        if(condition.equals("limit")) when(stateRepository.countArchiveNoticeReservationsSince(any())).thenReturn(140L);
        if(condition.equals("gap")) when(stateRepository.latestArchiveNoticeReservation()).thenReturn(Optional.of(storage));
        if(condition.equals("paused")) when(appSettingService.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED,true)).thenReturn(false);
        var next=service.archiveNoticeNextSlot(30,new com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target(20L,"fixture",null,null,null));
        assertThat(next).isAfter(storage.plusSeconds(1));
        verifyNoInteractions(archiveNotifications);
    }

    @Test void clientTextSilenceWaitsTheClientTextIntervalAfterTheSeventhReminder() throws Exception {
        waitForClientTexts();
        when(appSettingService.getInt(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_INTERVAL_DAYS,2)).thenReturn(3);
        state.setLastSuccessAt(now.minusDays(2).minusHours(1));
        assertThat(service.archiveAfterReminders(30).archived()).isFalse();
        state.setLastSuccessAt(now.minusDays(3).minusMinutes(1));
        assertThat(service.archiveAfterReminders(30).archived()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings={"six","recent","answered","new_cycle","status","payment","unknown","reply","history_missing","reply_during_check"})
    void clientTextArchiveRequiresTheSameWaitingCycleAndCompleteSilenceEvidence(String condition) throws Exception {
        waitForClientTexts();
        switch(condition) {
            case "six" -> state.setSentCount(6);
            case "recent" -> state.setLastSuccessAt(now.minusHours(1));
            case "answered" -> order.setWaitingForClient(false);
            case "new_cycle" -> order.setWaitingForClientChangedAt(now.minusDays(1));
            case "status" -> order.setStatus(OrderStatus.builder().title("На проверке").build());
            case "payment" -> when(orderPaymentIntegrityService.hasSettledPaymentEvidence(order)).thenReturn(true);
            case "unknown" -> state.setDeliveryStatus("UNKNOWN");
            case "reply", "history_missing" -> when(reviewArchiveHistory.inspect(any(),any())).thenReturn(new ReviewArchiveHistory.Check(
                    condition.equals("reply")?ReviewArchiveHistory.Outcome.REPLY_FOUND:ReviewArchiveHistory.Outcome.HISTORY_MISSING,
                    "No silence proof",state.getLastSuccessAt(),now,1,"fixture"));
            case "reply_during_check" -> when(reviewArchiveHistory.inspect(any(),any())).thenAnswer(i -> {
                order.setWaitingForClient(false);
                return new ReviewArchiveHistory.Check(ReviewArchiveHistory.Outcome.CLEAR,"History checked",i.getArgument(1),now,0,"fixture");
            });
        }
        assertThat(service.archiveAfterReminders(30).archived()).isFalse();
        verify(orderStatusTransitionService,never()).archiveAfterClientTextReminders(anyLong());
        verifyNoInteractions(archiveNotifications);
    }

    @Test void sevenReceiptsArchiveAndAtomicallyQueueOfferAndBothKindsOfNotification() throws Exception {
        assertThat(service.archiveAfterReminders(30).archived()).isTrue();
        assertThat(state.getStatus()).isEqualTo(ScheduledMessageStateStatus.DONE);
        assertThat(state.getSentCount()).isEqualTo(7);
        verify(orderStatusTransitionService).changeStatusForOrder(77L,"Архив");
        verify(archiveNotifications).recordArchive(eq(30L),eq(77L),eq(state.getTargetKey()),eq(7),any(),
                argThat(time -> time.isAfter(now.plusMonths(2))),contains("поставлено в очередь"));
        verify(archiveNotifications).enqueue(eq(30L),eq("client"),argThat(e -> e.text().contains("перенесли заказ в архив") && e.staffChatId()==null));
        verify(archiveNotifications).enqueue(eq(30L),eq("staff:10"),argThat(e -> e.text().contains("поставлено в очередь")));
        verify(archiveNotifications).enqueue(eq(30L),eq("staff:11"),any());
        assertThat(service.archiveAfterReminders(30).archived()).isFalse();
        verify(archiveNotifications,times(3)).enqueue(anyLong(),anyString(),any());
    }

    @ParameterizedTest @ValueSource(strings={"client","group","manager"})
    void missingIdentityIsVisibleAndDeferredWithoutTransportOrArchival(String missing) throws Exception {
        if(missing.equals("client")) order.getManager().setClientId(" ");
        if(missing.equals("group")) company.setGroupId(null);
        if(missing.equals("manager")) {order.setManager(null);company.setManager(null);}
        var result=service.archiveAfterReminders(30);
        assertThat(result.archived()).isFalse();
        assertThat(result.reason()).contains("привязку");
        assertThat(state.getArchiveBlockReason()).isEqualTo(result.reason());
        assertThat(state.getArchiveNextCheckAt()).isAfter(now.plusMinutes(25));
        verifyNoInteractions(archiveChatHistory,archiveNotifications);
        verify(orderStatusTransitionService,never()).changeStatusForOrder(anyLong(),anyString());
    }

    @Test void repairingBindingAllowsManualRecheckBeforeDeferredTime() {
        company.setGroupId(null);
        assertThat(service.archiveAfterReminders(30).archived()).isFalse();
        company.setGroupId("repaired-group");
        assertThat(service.archiveAfterReminders(30).archived()).isTrue();
        assertThat(state.getArchiveBlockReason()).isNull();
        assertThat(state.getArchiveNextCheckAt()).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"https://t.me/group","https://max.ru/chat"})
    void otherChannelsNeverUseAnOldWhatsappGroupToAssertClientSilence(String url) {
        company.setUrlChat(url);
        var result=service.archiveAfterReminders(30);
        assertThat(result.archived()).isFalse();
        assertThat(result.reason()).contains("привязку");
        verifyNoInteractions(archiveChatHistory,reviewArchiveHistory,archiveNotifications);
    }

    @ParameterizedTest @ValueSource(strings={"TELEGRAM","MAX"})
    void completeChannelHistoryArchivesUsingTheCorrectChatInsteadOfTheOldWhatsappBinding(String platform) {
        company.setUrlChat(platform.equals("MAX")?"https://max.ru/chat":"https://t.me/group");
        company.setTelegramGroupChatId(-123L);company.setMaxGroupChatId(-456L);
        assertThat(service.archiveAfterReminders(30).archived()).isTrue();
        verify(reviewArchiveHistory).inspect(argThat(chat->chat.platform().equals(platform)
                && chat.chatId().equals(platform.equals("MAX")?"-456":"-123")),eq(state.getLastSuccessAt()));
        assertThat(state.getArchiveCheckStatus()).isEqualTo("ARCHIVED");
        assertThat(state.getArchiveCheckChannel()).isEqualTo(platform);
        verifyNoInteractions(archiveChatHistory);
    }

    @ParameterizedTest @ValueSource(strings={"REPLY_FOUND","HISTORY_MISSING","HISTORY_UNAVAILABLE"})
    void missingOrUncertainChannelHistoryIsRecordedAndRetriedWithoutChangingTheOrder(String outcome) {
        company.setUrlChat("https://t.me/group");company.setTelegramGroupChatId(-123L);
        when(reviewArchiveHistory.inspect(any(),any())).thenReturn(new ReviewArchiveHistory.Check(
                ReviewArchiveHistory.Outcome.valueOf(outcome),"Proof unavailable",state.getLastSuccessAt(),now,0,"fixture"));
        assertThat(service.archiveAfterReminders(30).archived()).isFalse();
        assertThat(state.getArchiveCheckStatus()).isEqualTo(outcome);
        assertThat(state.getArchiveCheckChannel()).isEqualTo("TELEGRAM");
        assertThat(state.getArchiveNextCheckAt()).isAfter(now);
        verifyNoInteractions(archiveNotifications);
    }

    @Test void replyArrivingDuringHistoryLookupPreventsArchivalAtCommit() {
        when(reviewArchiveHistory.hasLocalReply(any(),any())).thenReturn(true);
        assertThat(service.archiveAfterReminders(30).archived()).isFalse();
        verifyNoInteractions(archiveNotifications);
    }

    @ParameterizedTest @ValueSource(strings={"six","unknown","recent","paid","pending","changed","reply","history_unavailable"})
    void unsafeOrIncompleteConditionsDoNotArchiveOrNotify(String condition) throws Exception {
        switch(condition) {
            case "six" -> state.setSentCount(6);
            case "unknown" -> state.setDeliveryStatus("UNKNOWN");
            case "recent" -> state.setLastSuccessAt(now.minusHours(1));
            case "paid" -> when(orderPaymentIntegrityService.hasSettledPaymentEvidence(order)).thenReturn(true);
            case "pending" -> when(manualContactReservation.blocksScheduledContact(77L)).thenReturn(true);
            case "changed" -> when(orderDeliveryContext.lock(77L)).thenReturn(Optional.empty());
            case "reply" -> when(reviewArchiveHistory.inspect(any(),any())).thenReturn(new ReviewArchiveHistory.Check(ReviewArchiveHistory.Outcome.REPLY_FOUND,"reply",state.getLastSuccessAt(),now,1,"fixture"));
            case "history_unavailable" -> when(reviewArchiveHistory.inspect(any(),any())).thenThrow(new IllegalStateException("offline"));
        }
        try { assertThat(service.archiveAfterReminders(30).archived()).isFalse(); }
        catch (IllegalStateException failure) { assertThat(condition).isEqualTo("history_unavailable"); }
        verify(orderStatusTransitionService,never()).changeStatusForOrder(anyLong(),anyString());
        verifyNoInteractions(archiveNotifications);
    }

    @Test void activeOtherOrderPreventsOfferButNotItsHistoricalArchiveNotice() {
        when(archiveCandidateRepository.hasArchiveReorderBlocker(anyLong(),anyList(),anyList())).thenReturn(true);
        var result = service.archiveAfterReminders(30);
        assertThat(result.archived()).isTrue();
        assertThat(result.reason()).contains("Предложение нового заказа отложено");
        verify(archiveNotifications).recordArchive(eq(30L),eq(77L),anyString(),eq(7),any(),isNull(),contains("отложено"));
        verify(stateRepository,never()).findByScenarioAndTargetKeyForUpdate(eq(ClientMessageScenario.ARCHIVE_REORDER_OFFER),anyString());
    }

    @Test void alreadyStoppedCompanyStartsOfferDelayFromThisArchive() {
        company.setStatusChangedAt(now.minusYears(2));
        var oldOffer = ScheduledClientMessageState.builder().id(99L)
                .status(ScheduledMessageStateStatus.ACTIVE).nextAttemptAt(now.minusYears(1)).sentCount(3).build();
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(eq(ClientMessageScenario.ARCHIVE_REORDER_OFFER),anyString()))
                .thenReturn(Optional.of(oldOffer));
        assertThat(service.archiveAfterReminders(30).archived()).isTrue();
        assertThat(oldOffer.getNextAttemptAt()).isAfter(now.plusMonths(2));
        assertThat(oldOffer.getSentCount()).isEqualTo(3);
        verify(archiveNotifications).recordArchive(eq(30L),eq(77L),anyString(),eq(7),any(),eq(oldOffer.getNextAttemptAt()),contains("поставлено в очередь"));
    }

    @Test void uncertainExistingOfferIsPreservedAndNotReportedAsNewlyQueued() {
        var oldOffer = ScheduledClientMessageState.builder().id(99L).status(ScheduledMessageStateStatus.ACTIVE)
                .deliveryStatus("UNKNOWN").deliveryEnvelope("frozen").nextAttemptAt(now.minusDays(1)).build();
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(eq(ClientMessageScenario.ARCHIVE_REORDER_OFFER),anyString()))
                .thenReturn(Optional.of(oldOffer));
        assertThat(service.archiveAfterReminders(30).archived()).isTrue();
        assertThat(oldOffer.getDeliveryEnvelope()).isEqualTo("frozen");
        assertThat(oldOffer.getNextAttemptAt()).isEqualTo(now.minusDays(1));
        verify(archiveNotifications).recordArchive(eq(30L),eq(77L),anyString(),eq(7),any(),isNull(),contains("отложено"));
    }
}
