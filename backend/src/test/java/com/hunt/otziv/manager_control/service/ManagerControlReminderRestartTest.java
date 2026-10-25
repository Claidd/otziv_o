package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.hunt.otziv.client_chat_control.api.ReminderContactHistory;
import com.hunt.otziv.client_messages.api.DeliveryOperation;
import com.hunt.otziv.client_messages.api.PeriodicReminderRestart;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.*;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManagerControlReminderRestartTest {
    final PeriodicReminderRestart scheduled = mock(PeriodicReminderRestart.class);
    final ReminderContactHistory history = mock(ReminderContactHistory.class);
    final ManagerClientMessageQueue queue = mock(ManagerClientMessageQueue.class);
    final ManagerClientMessageWorker worker = mock(ManagerClientMessageWorker.class);
    final OrderDeliveryControlContext orders = mock(OrderDeliveryControlContext.class);
    final ManagerDailyControlConcreteItemRepository cards = mock(ManagerDailyControlConcreteItemRepository.class);
    final ManagerControlRepairOutcome outcome = mock(ManagerControlRepairOutcome.class);
    final com.hunt.otziv.client_messages.api.ReviewReminderClosure closure = mock(com.hunt.otziv.client_messages.api.ReviewReminderClosure.class);
    final ManagerControlReminderRestart repair = new ManagerControlReminderRestart(scheduled, history, queue, worker,
            orders, new ManagerControlTransactionRunner(), cards, outcome, closure);
    final LocalDateTime now = LocalDateTime.now();
    final OrderDeliveryControlContext.Snapshot source = new OrderDeliveryControlContext.Snapshot(77L, 2L, "На проверке", 2,
            now.minusDays(20), false, null);
    final PeriodicReminderRestart.DeliveryReference frozen = new PeriodicReminderRestart.DeliveryReference(
            30L, "token", "order:77:" + source.cycleAt().withNano(0), "old-operation", "fixture-client", "fixture-chat");
    final PeriodicReminderRestart.ReminderRestartCheck checked = new PeriodicReminderRestart.ReminderRestartCheck(
            frozen, "saved-envelope", source, now.minusDays(3), now);
    final ManagerDailyControlConcreteItem card = new ManagerDailyControlConcreteItem();
    final ManagerControlConcreteItemResponse response = mock(ManagerControlConcreteItemResponse.class);
    final LocalDateTime due = now.plusDays(2);

    @BeforeEach void setup() {
        var manager = new Manager(); manager.setId(2L);
        var control = new ManagerDailyControl(); control.setManager(manager);
        card.setId(10L); card.setControl(control); card.setEntityType("ORDER"); card.setEntityId(77L);
        when(scheduled.checkStaleReminderRestart(30, 2L)).thenReturn(Optional.of(checked));
        when(cards.findByIdForUpdate(10L)).thenReturn(Optional.of(card));
        when(orders.lock(77L)).thenReturn(Optional.of(source));
        when(history.latestReminderContact(eq("fixture-client"), eq("fixture-chat"), any())).thenReturn(now);
        when(scheduled.restartStaleReminder(eq(checked), any(), eq("owner"))).thenReturn(due);
        when(outcome.recordReminderScheduled(eq(card), anyString(), any(), eq(due))).thenReturn(response);
    }

    ManagerClientMessageQueue.RecordedControl pending(String state, String error) {
        var message = mock(ManagerControlClientSendWorkflow.PreparedClientMessage.class);
        when(message.orderId()).thenReturn(77L); when(message.orderManagerId()).thenReturn(2L);
        when(message.orderStatus()).thenReturn(source.status()); when(message.sourceGeneration()).thenReturn(2L);
        when(message.sourceStatusChangedAt()).thenReturn(source.cycleAt());
        when(message.managerClientId()).thenReturn("fixture-client"); when(message.groupId()).thenReturn("fixture-chat");
        return new ManagerClientMessageQueue.RecordedControl(new DeliveryOperation("manual-old", state, 1, error),
                9, now.minusDays(3), now, message);
    }

    @Test void buttonChecksBothOperationsRetiresUnknownCommandAndSchedulesWithoutDispatch() {
        var pending = pending("UNKNOWN", "whatsapp_history_checked_no_receipt");
        when(queue.controlHistory(77)).thenReturn(List.of(pending));
        when(queue.controlHistoryForSettlement(77)).thenReturn(List.of(pending));
        when(worker.reconcileExisting("manual-old")).thenReturn(true);
        assertThat(repair.repair(10, 30, 2L, () -> "owner")).contains(response);
        var sequence = inOrder(worker, history, scheduled, queue, outcome);
        sequence.verify(worker).reconcileExisting("manual-old");
        sequence.verify(history).latestReminderContact(eq("fixture-client"), eq("fixture-chat"), any());
        sequence.verify(scheduled).restartStaleReminder(checked, now, "owner");
        sequence.verify(queue).supersedeStaleReminder(pending, "owner", due);
        sequence.verify(outcome).recordReminderScheduled(eq(card), contains("Очередь напоминаний восстановлена"), any(), eq(due));
    }

    @Test void unavailableHistoryCannotRetireAnOperationOrCloseCard() {
        when(history.latestReminderContact(anyString(), anyString(), any())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> repair.repair(10, 30, 2L, () -> "owner")).hasMessageContaining("Не удалось прочитать");
        verify(scheduled, never()).restartStaleReminder(any(), any(), anyString());
        verifyNoInteractions(outcome);
    }

    @Test void cancelledBeforeProviderCallDoesNotPermanentlyBlockAuthorizedRepair() {
        var cancelled = pending("FAILED", "context_changed");
        when(queue.controlHistory(77)).thenReturn(List.of(cancelled));
        when(queue.controlHistoryForSettlement(77)).thenReturn(List.of(cancelled));
        assertThat(repair.repair(10,30,2L,() -> "owner")).contains(response);
        verifyNoInteractions(worker);
        verify(queue).supersedeStaleReminder(cancelled,"owner",due);
        verify(history).latestReminderContact(eq("fixture-client"),eq("fixture-chat"),any());
    }

    @Test void failedFinalizationIsNeverMistakenForCancellationBeforeDispatch() {
        var failed = pending("FAILED", "finalization_required");
        when(queue.controlHistory(77)).thenReturn(List.of(failed));
        assertThat(repair.repair(10,30,2L,() -> "owner")).isEmpty();
        verify(scheduled,never()).restartStaleReminder(any(),any(),anyString());
        verify(queue,never()).supersedeStaleReminder(any(),anyString(),any());
    }

    @Test void changedCycleAndNewPendingCommandAreRejected() {
        var pending = pending("UNKNOWN", "whatsapp_history_checked_no_receipt");
        when(queue.controlHistoryForSettlement(77)).thenReturn(List.of(pending));
        assertThatThrownBy(() -> repair.repair(10, 30, 2L, () -> "owner")).hasMessageContaining("изменился заказ или отправка");
        verify(scheduled, never()).restartStaleReminder(any(), any(), anyString());
        verifyNoInteractions(outcome);
        when(orders.lock(77L)).thenReturn(Optional.of(new OrderDeliveryControlContext.Snapshot(77L, 2L, "Оплачено", 3, now, false, null)));
        assertThatThrownBy(() -> repair.repair(10, 30, 2L, () -> "owner")).hasMessageContaining("изменился заказ или отправка");
    }

    @Test void repeatedClickReturnsExistingDeadlineWithoutNewGenerationOrDispatch() {
        when(scheduled.checkStaleReminderRestart(30, 2L)).thenReturn(Optional.empty());
        when(scheduled.pendingReminderRestart(30, 2L)).thenReturn(Optional.of(new PeriodicReminderRestart.PendingReminderRestart(source, due)));
        assertThat(repair.repair(10, 30, 2L, () -> "owner")).contains(response);
        verifyNoInteractions(worker, history);
        verify(scheduled, never()).restartStaleReminder(any(), any(), anyString());
        verify(queue, never()).supersedeStaleReminder(any(), anyString(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"ORDER", "AUTOMATION_FAILURE"})
    void bothCardTypesRepairCancelledManualReservationWithoutSending(String cardType) {
        var reserved = reservedReminder();
        var cancelled = pending("FAILED", "context_changed");
        when(queue.controlHistory(77)).thenReturn(List.of(cancelled));
        when(queue.controlHistoryForSettlement(77)).thenReturn(List.of(cancelled));
        card.setEntityType(cardType);
        card.setEntityId("ORDER".equals(cardType) ? 77L : 30L);

        assertThat(repair.repair(10, 30, 2L, () -> "owner")).contains(response);

        var sequence = inOrder(history, scheduled, queue, outcome);
        sequence.verify(history).latestReminderContact(eq("fixture-client"), eq("fixture-chat"), any());
        sequence.verify(scheduled).resumeAfterCancelledManualContact(reserved, now);
        sequence.verify(queue).supersedeCancelledReservation(cancelled, "owner", due, reserved);
        sequence.verify(outcome).recordReminderScheduled(eq(card), contains("отменённая до отправки ручная команда"), any(), eq(due));
        verifyNoInteractions(worker);
        verify(scheduled, never()).restartStaleReminder(any(), any(), anyString());
        when(scheduled.checkManualReservationReminder(30, 2L)).thenReturn(Optional.empty());
        when(scheduled.pendingReminderRestart(30, 2L))
                .thenReturn(Optional.of(new PeriodicReminderRestart.PendingReminderRestart(source, due)));
        assertThat(repair.repair(10, 30, 2L, () -> "owner")).contains(response);
        verify(scheduled, times(1)).resumeAfterCancelledManualContact(reserved, now);
        verify(queue, times(1)).supersedeCancelledReservation(cancelled, "owner", due, reserved);
        verify(history, times(1)).latestReminderContact(anyString(), anyString(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "SENDING", "QUEUED", "RETRYABLE", "FINALIZATION", "RECENT", "EMPTY"})
    void reservationRepairNeverRetiresUncertainLiveOrRecentManualCommands(String condition) {
        reservedReminder();
        var blocked = "FINALIZATION".equals(condition) ? pending("FAILED", "finalization_required")
                : List.of("RECENT", "EMPTY").contains(condition) ? pending("FAILED", "context_changed")
                : pending(condition, "receipt_unavailable");
        if ("RECENT".equals(condition)) blocked = new ManagerClientMessageQueue.RecordedControl(blocked.delivery(),
                blocked.cardId(), now.minusHours(1), now, blocked.source());
        when(queue.controlHistory(77)).thenReturn("EMPTY".equals(condition) ? List.of() : List.of(blocked));

        assertThat(repair.repair(10, 30, 2L, () -> "owner")).isEmpty();

        verifyNoInteractions(history, worker, outcome);
        verify(scheduled, never()).resumeAfterCancelledManualContact(any(), any());
        verify(queue, never()).supersedeCancelledReservation(any(), anyString(), any(), any());
    }

    @Test void reservationRepairKeepsCancelledCommandWhenHistoryIsUnavailable() {
        reservedReminder();
        var cancelled = pending("FAILED", "context_changed");
        when(queue.controlHistory(77)).thenReturn(List.of(cancelled));
        when(history.latestReminderContact(anyString(), anyString(), any())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> repair.repair(10, 30, 2L, () -> "owner")).hasMessageContaining("Не удалось прочитать");
        verify(scheduled, never()).resumeAfterCancelledManualContact(any(), any());
        verify(queue, never()).supersedeCancelledReservation(any(), anyString(), any(), any());
        verifyNoInteractions(worker, outcome);
    }

    @ParameterizedTest @ValueSource(strings = {"new-command", "changed-command", "route", "actor", "card", "cycle"})
    void reservationRepairRechecksCancelledCommandsAndCurrentAuthorityUnderLocks(String change) {
        reservedReminder();
        var cancelled = pending("FAILED", "context_changed");
        when(queue.controlHistory(77)).thenReturn(List.of(cancelled));
        when(queue.controlHistoryForSettlement(77)).thenReturn(List.of(cancelled));
        switch (change) {
            case "new-command" -> {
                var queued = pending("QUEUED", null);
                when(queue.controlHistoryForSettlement(77)).thenReturn(List.of(cancelled, queued));
            }
            case "changed-command" -> {
                var unknown = pending("UNKNOWN", "receipt_unavailable");
                when(queue.controlHistoryForSettlement(77)).thenReturn(List.of(unknown));
            }
            case "route" -> when(cancelled.source().groupId()).thenReturn("different-chat");
            case "actor" -> { }
            case "card" -> card.setEntityId(99L);
            case "cycle" -> when(orders.lock(77L)).thenReturn(Optional.of(new OrderDeliveryControlContext.Snapshot(77L, 2L,
                    "На проверке", 3, now, false, null)));
        }
        assertThatThrownBy(() -> repair.repair(10, 30, 2L, () -> "actor".equals(change) ? "" : "owner"))
                .hasMessageContaining("изменился заказ или отправка");
        verify(scheduled, never()).resumeAfterCancelledManualContact(any(), any());
        verify(queue, never()).supersedeCancelledReservation(any(), anyString(), any(), any());
        verifyNoInteractions(worker, outcome);
    }

    private PeriodicReminderRestart.ReservedReminderCheck reservedReminder() {
        var reserved = new PeriodicReminderRestart.ReservedReminderCheck(30L, frozen.targetKey(), "REVIEW_CHECK_REMINDER",
                source, "fixture-client", "fixture-chat", 0, 3, now.minusDays(3), now);
        when(scheduled.checkStaleReminderRestart(30, 2L)).thenReturn(Optional.empty());
        when(scheduled.checkManualReservationReminder(30, 2L)).thenReturn(Optional.of(reserved));
        when(scheduled.resumeAfterCancelledManualContact(eq(reserved), any())).thenReturn(due);
        return reserved;
    }
}
