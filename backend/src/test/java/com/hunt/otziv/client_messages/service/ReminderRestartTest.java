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

    @Test void cancelledManualReservationResumesExistingReminderWithoutLosingThreeConfirmedContacts() {
        reservedReminder();
        var lastSuccess = state.getLastSuccessAt();
        var checked = service.checkManualReservationReminder(30, 2L).orElseThrow();
        LocalDateTime recentContact = LocalDateTime.now().minusHours(2);

        LocalDateTime next = service.resumeAfterCancelledManualContact(checked, recentContact);

        assertThat(next).isAfterOrEqualTo(recentContact.plusDays(2).withNano(0));
        assertThat(slotPlanner.isAllowedNow(messageTime.toIrkutskTime(next))).isTrue();
        assertThat(state.getSentCount()).isEqualTo(3);
        assertThat(state.getLastSuccessAt()).isEqualTo(lastSuccess);
        assertThat(state.getDispatchGeneration()).isZero();
        assertThat(state.getDeliveryToken()).isNull();
        assertThat(state.getDeliveryEnvelope()).isNull();
        assertThat(state.getLastErrorCode()).isNull();
        assertThat(state.getNextAttemptAt()).isEqualTo(next);
        assertThat(order.getPayDay()).isNull();
        assertThat(order.isComplete()).isFalse();
        assertThat(service.checkManualReservationReminder(30, 2L)).isEmpty();
        assertThatThrownBy(() -> service.resumeAfterCancelledManualContact(checked, recentContact));
        verifyNoInteractions(messageSender, jdbcTemplate);
    }

    @Test void cancelledReservationDeadlineUsesLastConfirmedReminderWhenItIsNewerThanChat() {
        reservedReminder();
        state.setLastSuccessAt(LocalDateTime.now().minusHours(1));
        var checked = service.checkManualReservationReminder(30, 2L).orElseThrow();
        LocalDateTime next = service.resumeAfterCancelledManualContact(checked, LocalDateTime.now().minusDays(2));
        assertThat(next).isAfterOrEqualTo(state.getLastSuccessAt().plusDays(2).withNano(0));
        assertThat(state.getSentCount()).isEqualTo(3);
        verifyNoInteractions(messageSender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invoice", "prepared", "unknown", "token", "envelope", "message", "channel",
            "task", "prepared-at", "lease", "paid", "complete", "cycle", "other-error", "manager"})
    void reservationInspectionRejectsEveryExistingDeliveryOrChangedSource(String change) {
        reservedReminder();
        switch (change) {
            case "invoice" -> state.setScenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY);
            case "prepared" -> state.setDeliveryStatus("PREPARED");
            case "unknown" -> state.setDeliveryStatus("UNKNOWN");
            case "token" -> state.setDeliveryToken("token");
            case "envelope" -> state.setDeliveryEnvelope("envelope");
            case "message" -> state.setDeliveryMessage("message");
            case "channel" -> state.setDeliveryChannel("WhatsApp");
            case "task" -> state.setDeliveryTaskId(5L);
            case "prepared-at" -> state.setDeliveryPreparedAt(LocalDateTime.now());
            case "lease" -> state.setLockedUntil(LocalDateTime.now().plusMinutes(5));
            case "paid" -> order.setPayDay(java.time.LocalDate.now());
            case "complete" -> order.setComplete(true);
            case "cycle" -> order.setStatusChangedAt(LocalDateTime.now());
            case "other-error" -> state.setLastErrorCode("state_transaction_outcome_uncertain");
            case "manager" -> { }
        }
        assertThat(service.checkManualReservationReminder(30, "manager".equals(change) ? 3L : 2L)).isEmpty();
        verify(stateRepository, never()).save(any());
        verifyNoInteractions(messageSender, jdbcTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid", "cycle", "envelope", "lease", "count", "success", "generation", "channel", "stale-check"})
    void reservationResumeRevalidatesEverySourceAfterHistoryLookup(String change) {
        reservedReminder();
        var inspected = service.checkManualReservationReminder(30, 2L).orElseThrow();
        switch (change) {
            case "paid" -> order.setPayDay(java.time.LocalDate.now());
            case "cycle" -> order.setStatusChangedAt(LocalDateTime.now());
            case "envelope" -> state.setDeliveryEnvelope("new preparation");
            case "lease" -> state.setLockedUntil(LocalDateTime.now().plusMinutes(5));
            case "count" -> state.setSentCount(4);
            case "success" -> state.setLastSuccessAt(LocalDateTime.now());
            case "generation" -> state.setDispatchGeneration(1);
            case "channel" -> order.getCompany().setGroupId("different-chat");
            case "stale-check" -> inspected = new com.hunt.otziv.client_messages.api.PeriodicReminderRestart.ReservedReminderCheck(
                    inspected.stateId(), inspected.targetKey(), inspected.scenario(), source, inspected.clientId(), inspected.groupId(),
                    inspected.dispatchGeneration(), inspected.sentCount(), inspected.lastSuccessAt(), LocalDateTime.now().minusMinutes(10));
        }
        var checked = inspected;
        assertThatThrownBy(() -> service.resumeAfterCancelledManualContact(checked, LocalDateTime.now()));
        assertThat(state.getLastErrorCode()).isEqualTo("manual_contact_reserved");
        verify(stateRepository, never()).save(any());
        verifyNoInteractions(messageSender, jdbcTemplate);
    }

    @ParameterizedTest @ValueSource(strings = {"WhatsApp", "MAX", "Telegram"})
    void freshUncertainPeriodicSendGetsOnlyAFutureOccurrenceWithoutClaimingDelivery(String channel) {
        var before = LocalDateTime.now();
        var lastSuccess = state.getLastSuccessAt();
        state.setDeliveryChannel(channel);
        state.setDeliveryPreparedAt(before.minusHours(1));
        when(messageSender.recordedOutcome("old-operation"))
                .thenReturn(ClientMessageSendResult.failed("operation_unknown", "unknown"));
        when(messageSender.hasSettledUncertainOperation("old-operation")).thenReturn(true);
        var inspected = service.checkDeferredReminderRestart(30, 2L).orElseThrow();
        var next = service.restartDeferredReminder(inspected, null, "owner");
        assertThat(next).isAfterOrEqualTo(before.plusDays(2));
        assertThat(slotPlanner.isAllowedNow(messageTime.toIrkutskTime(next))).isTrue();
        assertThat(state.getSentCount()).isEqualTo(11);
        assertThat(state.getLastSuccessAt()).isEqualTo(lastSuccess);
        assertThat(state.getDispatchGeneration()).isEqualTo(1);
        assertThat(state.getDeliveryEnvelope()).isNull();
        assertThat(state.getNextAttemptAt()).isEqualTo(next);
        assertThat(order.getPayDay()).isNull(); assertThat(order.isComplete()).isFalse();
        verify(jdbcTemplate).update(contains("INSERT INTO client_reminder_restart_audit"), eq("repair:old-operation"),
                eq("old-operation"), eq("owner"), eq("periodic_next_cycle_scheduled_unknown_preserved"), eq(2L), eq(1L), eq(next), eq(30L));
        verify(messageSender).recordedOutcome("old-operation");
        verify(messageSender).hasSettledUncertainOperation("old-operation");
        verifyNoMoreInteractions(messageSender);
        assertThatThrownBy(() -> service.restartDeferredReminder(inspected, null, "owner"));
    }

    @ParameterizedTest @ValueSource(strings = {"sent", "known-unsent", "unavailable"})
    void deferredInspectionDoesNotReplacePositiveReceiptsOrKnownFailures(String outcome) {
        state.setDeliveryChannel("MAX");
        when(messageSender.recordedOutcome("old-operation")).thenReturn(switch (outcome) {
            case "sent" -> ClientMessageSendResult.sent("MAX", "receipt");
            case "known-unsent" -> ClientMessageSendResult.failed("gateway_not_ready", "not sent");
            default -> null;
        });
        assertThat(service.checkDeferredReminderRestart(30, 2L)).isEmpty();
        verifyNoInteractions(jdbcTemplate);
        verify(stateRepository, never()).save(any());
    }

    @ParameterizedTest @ValueSource(strings = {"inflight", "lease", "invoice", "archive-offer", "prepared", "channel", "token", "manager"})
    void deferredInspectionExcludesActiveDispatchesAndNonPeriodicOrMismatchedSources(String change) {
        state.setDeliveryChannel("MAX");
        switch (change) {
            case "inflight" -> state.setDeliveryPreparedAt(LocalDateTime.now().minusMinutes(4));
            case "lease" -> state.setLockedUntil(LocalDateTime.now().plusMinutes(1));
            case "invoice" -> state.setScenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY);
            case "archive-offer" -> state.setScenario(ClientMessageScenario.ARCHIVE_REORDER_OFFER);
            case "prepared" -> state.setDeliveryStatus("PREPARED");
            case "channel" -> state.setDeliveryChannel(null);
            case "token" -> state.setDeliveryToken("changed");
            case "manager" -> { }
        }
        assertThat(service.checkDeferredReminderRestart(30, "manager".equals(change) ? 3L : 2L)).isEmpty();
        verifyNoInteractions(messageSender, jdbcTemplate);
    }

    @ParameterizedTest @ValueSource(strings = {"paid", "complete", "cycle", "prepared-time", "channel", "actor", "stale-check"})
    void deferredWriteRevalidatesCurrentMoneyCycleAndFrozenDelivery(String change) {
        state.setDeliveryChannel("MAX");
        when(messageSender.recordedOutcome("old-operation"))
                .thenReturn(ClientMessageSendResult.failed("operation_unknown", "unknown"));
        when(messageSender.hasSettledUncertainOperation("old-operation")).thenReturn(true);
        var inspected = service.checkDeferredReminderRestart(30, 2L).orElseThrow();
        switch (change) {
            case "paid" -> order.setPayDay(java.time.LocalDate.now());
            case "complete" -> order.setComplete(true);
            case "cycle" -> order.setStatusChangedAt(LocalDateTime.now());
            case "prepared-time" -> state.setDeliveryPreparedAt(state.getDeliveryPreparedAt().minusHours(1));
            case "channel" -> state.setDeliveryChannel("Telegram");
            case "stale-check" -> inspected = new com.hunt.otziv.client_messages.api.PeriodicReminderRestart.DeferredReminderCheck(
                    new com.hunt.otziv.client_messages.api.PeriodicReminderRestart.ReminderRestartCheck(inspected.source().delivery(),
                            inspected.source().envelope(), inspected.source().order(), inspected.source().preparedAt(),
                            LocalDateTime.now().minusMinutes(6)), inspected.channel());
            case "actor" -> { }
        }
        var frozen = inspected;
        assertThatThrownBy(() -> service.restartDeferredReminder(frozen, null, "actor".equals(change) ? "" : "owner"));
        assertThat(state.getDispatchGeneration()).isZero();
        verifyNoInteractions(jdbcTemplate);
        verify(stateRepository, never()).save(any());
    }

    @Test void olderFrozenLineageCanOnlyResumeAnIndependentFutureOccurrenceInTheSameCycle() {
        state.setDeliveryChannel("MAX");
        order.setClientMessageGeneration(3);
        source = new OrderDeliveryControlContext.Snapshot(77L, 2L, "На проверке", 3, order.getStatusChangedAt(), false, null);
        when(orderDeliveryContext.read(77L)).thenReturn(Optional.of(source));
        when(orderDeliveryContext.lock(77L)).thenReturn(Optional.of(source));
        when(messageSender.recordedOutcome("old-operation")).thenReturn(ClientMessageSendResult.failed("operation_unknown", "unknown"));
        when(messageSender.hasSettledUncertainOperation("old-operation")).thenReturn(true);
        assertThat(service.checkStaleReminderRestart(30, 2L)).isEmpty();
        var deferred = service.checkDeferredReminderRestart(30, 2L).orElseThrow();
        assertThat(deferred.source().order().generation()).isEqualTo(3);
        var next = service.restartDeferredReminder(deferred, null, "owner");
        assertThat(next).isAfterOrEqualTo(LocalDateTime.now().plusDays(2).withNano(0));
        assertThat(state.getDispatchGeneration()).isEqualTo(1);
        assertThat(order.getClientMessageGeneration()).isEqualTo(3);
        assertThat(state.getSentCount()).isEqualTo(11);
        verify(messageSender, never()).recoverRecordedOutcomeFromChat(anyString(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"older-current-lineage", "different-cycle"})
    void aNewerFrozenLineageOrAnotherCycleCannotBeAdoptedAsAFutureReminder(String change) {
        state.setDeliveryChannel("MAX");
        var altered = new OrderDeliveryControlContext.Snapshot(77L, 2L, "На проверке",
                "older-current-lineage".equals(change) ? 1 : 2,
                "different-cycle".equals(change) ? LocalDateTime.now() : order.getStatusChangedAt(), false, null);
        when(orderDeliveryContext.read(77L)).thenReturn(Optional.of(altered));
        assertThat(service.checkDeferredReminderRestart(30, 2L)).isEmpty();
        verifyNoInteractions(messageSender, jdbcTemplate);
    }

    private void reservedReminder() {
        state.setDeliveryStatus(null);
        state.setDeliveryToken(null);
        state.setDeliveryEnvelope(null);
        state.setDeliveryPreparedAt(null);
        state.setLastErrorCode("manual_contact_reserved");
        state.setSentCount(3);
        when(orderRepository.findByIdForOrderDto(77L)).thenReturn(Optional.of(order));
    }
}
