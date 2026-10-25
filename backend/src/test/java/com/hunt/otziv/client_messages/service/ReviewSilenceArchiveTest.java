package com.hunt.otziv.client_messages.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.*;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageReconciliationService;
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
    @Mock ScheduledClientMessageStateRepository stateRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderDeliveryControlContext orderDeliveryContext;
    @Mock com.hunt.otziv.client_messages.api.OrderManualContactReservation manualContactReservation;
    @Mock OrderStatusTransitionService orderStatusTransitionService;
    @Mock OrderPaymentIntegrityService orderPaymentIntegrityService;
    @Mock AppSettingService appSettingService;
    @Mock com.hunt.otziv.client_chat_control.api.ReminderContactHistory archiveChatHistory;
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

    @ParameterizedTest @ValueSource(strings={"six","unknown","recent","paid","pending","changed","reply","history_unavailable"})
    void unsafeOrIncompleteConditionsDoNotArchiveOrNotify(String condition) throws Exception {
        switch(condition) {
            case "six" -> state.setSentCount(6);
            case "unknown" -> state.setDeliveryStatus("UNKNOWN");
            case "recent" -> state.setLastSuccessAt(now.minusHours(1));
            case "paid" -> when(orderPaymentIntegrityService.hasSettledPaymentEvidence(order)).thenReturn(true);
            case "pending" -> when(manualContactReservation.blocksScheduledContact(77L)).thenReturn(true);
            case "changed" -> when(orderDeliveryContext.lock(77L)).thenReturn(Optional.empty());
            case "reply" -> when(archiveChatHistory.hasReplyBlockingArchive(anyString(),anyString(),any())).thenReturn(true);
            case "history_unavailable" -> when(archiveChatHistory.hasReplyBlockingArchive(anyString(),anyString(),any())).thenThrow(new IllegalStateException("offline"));
        }
        try { assertThat(service.archiveAfterReminders(30).archived()).isFalse(); }
        catch (IllegalStateException failure) { assertThat(condition).isEqualTo("history_unavailable"); }
        verify(orderStatusTransitionService,never()).changeStatusForOrder(anyLong(),anyString());
        verifyNoInteractions(archiveNotifications);
    }

    @Test void activeOtherOrderPreventsOfferButNotItsHistoricalArchiveNotice() {
        when(archiveCandidateRepository.hasArchiveReorderBlocker(anyLong(),anyList(),anyList())).thenReturn(true);
        assertThat(service.archiveAfterReminders(30).archived()).isTrue();
        verify(archiveNotifications).recordArchive(eq(30L),eq(77L),anyString(),eq(7),any(),isNull(),contains("отложено"));
        verify(stateRepository,never()).findByScenarioAndTargetKeyForUpdate(eq(ClientMessageScenario.ARCHIVE_REORDER_OFFER),anyString());
    }
}
