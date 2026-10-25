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
}
