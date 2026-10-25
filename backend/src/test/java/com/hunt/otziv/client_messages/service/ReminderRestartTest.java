package com.hunt.otziv.client_messages.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import com.hunt.otziv.p_products.model.*;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;

class ReminderRestartTest {
    @Mock ScheduledClientMessageStateRepository stateRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderDeliveryControlContext orderDeliveryContext;
    @Mock ClientChatMessageSender messageSender;
    @Mock AppSettingService appSettingService;
    @Mock JdbcTemplate jdbcTemplate;
    @Spy ClientMessageTime messageTime = new ClientMessageTime();
    @Spy ClientMessageSlotPlanner slotPlanner = new ClientMessageSlotPlanner();
    @InjectMocks ScheduledClientMessageService service;
    AutoCloseable mocks;
    ScheduledClientMessageState state;
    Order order;
    OrderDeliveryControlContext.Snapshot source;
    ScheduledClientMessageService.ReminderRestartCheck check;

    @BeforeEach void setup() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        var now = LocalDateTime.now();
        var manager = new Manager(); manager.setId(2L); manager.setClientId("fixture-client");
        var company = new Company(); company.setGroupId("fixture-chat");
        var status = new OrderStatus(); status.setTitle("На проверке");
        order = new Order(); order.setId(77L); order.setManager(manager); order.setCompany(company); order.setStatus(status);
        order.setStatusChangedAt(now.minusDays(20)); order.setClientMessageGeneration(2);
        source = new OrderDeliveryControlContext.Snapshot(77L, 2L, "На проверке", 2, order.getStatusChangedAt(), false, null);
        String target = "order:77:" + source.cycleAt().withNano(0);
        var prepared = new ScheduledClientMessageService.PreparedScheduledDelivery(30L, ClientMessageScenario.REVIEW_CHECK_REMINDER,
                target, 77L, "token", "old-operation", null, "fixture-client", "fixture-chat", "text", null, 2, null, null, 2, "На проверке", null, List.of());
        String envelope = new ObjectMapper().writeValueAsString(prepared);
        state = ScheduledClientMessageState.builder().id(30L).orderId(77L).scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER)
                .status(ScheduledMessageStateStatus.ACTIVE).targetKey(target).sentCount(11).lastSuccessAt(now.minusDays(5))
                .deliveryStatus("UNKNOWN").deliveryToken("token").deliveryEnvelope(envelope).deliveryPreparedAt(now.minusDays(3))
                .lastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN).build();
        check = new ScheduledClientMessageService.ReminderRestartCheck(new com.hunt.otziv.client_messages.api.PeriodicReminderRestart.DeliveryReference(
                prepared.stateId(), prepared.token(), prepared.targetKey(), prepared.operationId(), prepared.clientId(), prepared.groupId()), envelope, source, state.getDeliveryPreparedAt(), now);
        when(stateRepository.findById(30L)).thenReturn(Optional.of(state));
        when(stateRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(state));
        when(orderRepository.findByIdForMutation(77L)).thenReturn(Optional.of(order));
        when(orderDeliveryContext.read(77L)).thenReturn(Optional.of(source));
        when(orderDeliveryContext.lock(77L)).thenReturn(Optional.of(source));
    }
    @AfterEach void close() throws Exception { mocks.close(); }

    @Test void newGenerationPreservesHistoricalCountsAndMoneyAndCannotBeAppliedTwice() {
        var success = state.getLastSuccessAt();
        var recentChat = LocalDateTime.now().minusHours(2);
        var next = service.restartStaleReminder(check, recentChat, "owner");
        assertThat(next).isAfterOrEqualTo(recentChat.plusDays(2).withNano(0));
        assertThat(slotPlanner.isAllowedNow(messageTime.toIrkutskTime(next))).isTrue();
        assertThat(state.getDispatchGeneration()).isEqualTo(1);
        assertThat(state.getSentCount()).isEqualTo(11);
        assertThat(state.getLastSuccessAt()).isEqualTo(success);
        assertThat(state.getDeliveryEnvelope()).isNull();
        assertThat(order.isComplete()).isFalse();
        assertThat(order.getPayDay()).isNull();
        assertThatThrownBy(() -> service.restartStaleReminder(check, recentChat, "owner"));
        verifyNoInteractions(messageSender);
    }

    @ParameterizedTest @ValueSource(strings={"paid","cycle","token","lease","fresh","status","one_off","stale_check"})
    void changedBusinessOrOperationIsNeverRestarted(String change) {
        switch(change) {
            case "paid" -> order.setPayDay(java.time.LocalDate.now());
            case "cycle" -> order.setStatusChangedAt(LocalDateTime.now());
            case "token" -> state.setDeliveryToken("other");
            case "lease" -> state.setLockedUntil(LocalDateTime.now().plusMinutes(5));
            case "fresh" -> state.setDeliveryPreparedAt(LocalDateTime.now());
            case "status" -> state.setDeliveryStatus("SENT");
            case "one_off" -> state.setScenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY);
            case "stale_check" -> check = new ScheduledClientMessageService.ReminderRestartCheck(check.delivery(),check.envelope(),source,check.preparedAt(),LocalDateTime.now().minusMinutes(10));
        }
        assertThatThrownBy(() -> service.restartStaleReminder(check, LocalDateTime.now(), "owner"));
        assertThat(state.getDispatchGeneration()).isZero();
        verifyNoInteractions(jdbcTemplate, messageSender);
        verify(stateRepository,never()).save(any());
    }

    @Test void failedAuditWriteLeavesFrozenOperationUntouched() {
        doThrow(new IllegalStateException("db unavailable")).when(jdbcTemplate).update(anyString(), any(Object[].class));
        assertThatThrownBy(() -> service.restartStaleReminder(check, LocalDateTime.now(), "owner"));
        assertThat(state.getDeliveryEnvelope()).isEqualTo(check.envelope());
        assertThat(state.getDispatchGeneration()).isZero();
        verify(stateRepository,never()).save(any());
    }

    @Test void inspectionUsesSavedOperationAndOnlyAllowsExplicitHistoryMiss() {
        when(messageSender.recoverRecordedOutcomeFromChat("old-operation", check.preparedAt()))
                .thenReturn(ClientMessageSendResult.failed("whatsapp_history_unavailable", "offline"));
        assertThat(service.checkStaleReminderRestart(30, 2L)).isEmpty();
        when(messageSender.recoverRecordedOutcomeFromChat("old-operation", check.preparedAt()))
                .thenReturn(ClientMessageSendResult.sent("WHATSAPP", "provider-receipt"));
        assertThat(service.checkStaleReminderRestart(30, 2L)).isEmpty();
        // Positive proof is left for the ordinary receipt finalizer/card contact workflow.
        assertThat(state.getDeliveryStatus()).isEqualTo("UNKNOWN");
        when(messageSender.recoverRecordedOutcomeFromChat("old-operation", check.preparedAt()))
                .thenReturn(ClientMessageSendResult.failed("whatsapp_history_checked_no_receipt", "checked"));
        assertThat(service.checkStaleReminderRestart(30, 2L)).isPresent();
        assertThat(service.checkStaleReminderRestart(30, 3L)).isEmpty();
        state.setDeliveryToken("changed");
        assertThat(service.checkStaleReminderRestart(30, 2L)).isEmpty();
        verify(stateRepository, never()).save(any());
        verifyNoInteractions(jdbcTemplate);
    }
}
