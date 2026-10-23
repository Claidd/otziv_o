package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.client_messages.api.DeliveryOperation;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ManagerControlManualDeliveryRepairTest {
    final OrderDeliveryControlContext orders = mock(OrderDeliveryControlContext.class);
    final ManagerControlOrderDeliveryState delivery = mock(ManagerControlOrderDeliveryState.class);
    final ManagerClientMessageWorker worker = mock(ManagerClientMessageWorker.class);
    final ManagerDailyControlConcreteItemRepository cards = mock(ManagerDailyControlConcreteItemRepository.class);
    final ManagerControlConcretePresenter presenter = mock(ManagerControlConcretePresenter.class);
    final ManagerControlCardLifecycle lifecycle = mock(ManagerControlCardLifecycle.class);
    final ManagerControlManualDeliveryRepair repair = new ManagerControlManualDeliveryRepair(orders, delivery, worker,
            new ManagerControlTransactionRunner(), cards, presenter, lifecycle);
    final OrderDeliveryControlContext.Snapshot source = new OrderDeliveryControlContext.Snapshot(77L, 2L,
            "На проверке", 4, LocalDateTime.of(2026, 7, 14, 10, 0), false, null);
    final ManagerDailyControlConcreteItem card = new ManagerDailyControlConcreteItem();
    final ManagerClientMessageQueue.RecordedControl row = new ManagerClientMessageQueue.RecordedControl(
            new DeliveryOperation("saved-operation", "UNKNOWN", 1, "operation_unknown"), 9,
            source.cycleAt(), source.cycleAt(), null);

    @BeforeEach void setup() {
        var manager = new Manager(); manager.setId(2L);
        var control = new ManagerDailyControl(); control.setManager(manager);
        card.setId(10L); card.setControl(control); card.setEntityType("ORDER"); card.setEntityId(77L);
        when(orders.read(77L)).thenReturn(Optional.of(source));
        when(orders.lock(77L)).thenReturn(Optional.of(source));
        when(cards.findByIdForUpdate(10L)).thenReturn(Optional.of(card));
    }

    @Test void confirmedExistingOperationRestoresOnlyItsContactAndReturnsTheFreshCard() {
        when(delivery.history(source)).thenReturn(List.of(row), List.of());
        when(delivery.restoreConfirmedContact(card)).thenReturn(true);
        var response = mock(ManagerControlConcreteItemResponse.class);
        when(presenter.concreteItemResponse(card)).thenReturn(response);
        assertThat(repair.repair(10, 77, 2L)).contains(response);
        verify(worker).reconcileExisting("saved-operation");
        verify(cards).save(card);
        verify(delivery).restoreConfirmedContact(card);
    }

    @Test void absenceOfProofRetainsTheCardAndDoesNotPretendTheOrderWasResolved() {
        when(delivery.history(source)).thenReturn(List.of(row));
        assertThatThrownBy(() -> repair.repair(10, 77, 2L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("результат пока не подтверждён");
        verify(worker).reconcileExisting("saved-operation");
        verify(cards, never()).save(any());
        verify(delivery, never()).restoreConfirmedContact(any());
        verifyNoInteractions(presenter, lifecycle);
    }

    @Test void changedOwnerCannotInspectOrReconcileThePreviousManagersOperation() {
        assertThatThrownBy(() -> repair.repair(10, 77, 3L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(worker, delivery, cards);
    }

    @Test void newCycleDuringReceiptLookupCannotReceiveTheOldContactOrDeadline() {
        when(delivery.history(source)).thenReturn(List.of(row));
        when(orders.lock(77L)).thenReturn(Optional.of(new OrderDeliveryControlContext.Snapshot(77L, 2L,
                "На проверке", 5, source.cycleAt().plusDays(1), false, null)));
        assertThatThrownBy(() -> repair.repair(10, 77, 2L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Источник изменился");
        verify(cards, never()).save(any());
        verify(delivery, never()).restoreConfirmedContact(any());
    }

    @Test void noPendingManualOperationLeavesScheduledRepairToItsOwnWorkflow() {
        when(delivery.history(source)).thenReturn(List.of());
        assertThat(repair.repair(10, 77, 2L)).isEmpty();
        verifyNoInteractions(worker, cards, presenter);
    }
}
