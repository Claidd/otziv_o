package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagerControlOrderAutomationDiagnosticsTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final ScheduledClientMessageStateRepository states = mock(ScheduledClientMessageStateRepository.class);
    private final ManagerControlOrderAutomationDiagnostics diagnostics =
            new ManagerControlOrderAutomationDiagnostics(orders, states);
    private final LocalDate today = LocalDate.of(2026, 9, 7);

    @Test
    void healthyCurrentCycleDoesNotCreateAnOverdueCardOrMutateQueue() {
        Order order = waitingOrder();
        var scheduled = activeState(order);
        when(orders.findManagerControlWorkerNewOrdersForControl(List.of(7L), today.minusDays(2)))
                .thenReturn(List.of(order));
        when(states.findByOrderIdIn(List.of(11L))).thenReturn(List.of(scheduled));

        assertThat(diagnostics.workerStaleOrderEntriesForControl(List.of(7L), "Новый", today)).isEmpty();
        verify(orders).findManagerControlWorkerNewOrdersForControl(List.of(7L), today.minusDays(2));
        verify(states).findByOrderIdIn(List.of(11L));
        verifyNoMoreInteractions(orders, states);
        assertThat(order.isWaitingForClient()).isTrue();
        assertThat(scheduled.getStatus()).isEqualTo(ScheduledMessageStateStatus.ACTIVE);
    }

    @Test
    void successFromPreviousWaitingCycleCannotHideMissingCurrentReminder() {
        Order order = waitingOrder();
        var stale = activeState(order);
        stale.setTargetKey("client-text:11:" + order.getWaitingForClientChangedAt().minusDays(1));
        stale.setLastSuccessAt(LocalDateTime.of(2026, 9, 6, 14, 0));
        var result = diagnostics.workerOrderClientTextDecision(order, "Новый", today, Map.of(11L, List.of(stale)));
        assertThat(result.include()).isTrue();
        assertThat(result.reason()).contains("нет записи в очереди CLIENT_TEXT_REMINDER");
    }

    @Test
    void activeFailureRemainsVisibleEvenWhenAnotherDuplicateCycleHasSucceeded() {
        Order order = waitingOrder();
        var success = activeState(order);
        success.setSentCount(1);
        var failure = activeState(order);
        failure.setConsecutiveFailures(2);
        failure.setLastErrorCode("delivery_failed");
        failure.setLastErrorMessage("transport unavailable");
        var result = diagnostics.workerOrderClientTextDecision(order, "Новый", today,
                Map.of(11L, List.of(success, failure)));
        assertThat(result.include()).isTrue();
        assertThat(result.reason()).contains("transport unavailable");
    }

    @Test
    void successfulQueueDoesNotHideMissingTelegramBinding() {
        Order order = waitingOrder();
        order.getCompany().setTelegramGroupChatId(null);
        var result = diagnostics.workerOrderClientTextDecision(order, "Новый", today,
                Map.of(11L, List.of(activeState(order))));
        assertThat(result.include()).isTrue();
        assertThat(result.reason()).contains("Telegram-группа не привязана");
    }

    @Test
    void overdueWaitingFlagStillNeedsActionWhenReminderQueueIsHealthy() {
        Order order = waitingOrder();
        order.setChanged(today.minusDays(40));
        var result = diagnostics.workerOrderClientTextDecision(order, "Новый", today,
                Map.of(11L, List.of(activeState(order))));
        assertThat(result.include()).isTrue();
        assertThat(result.reason()).contains("снимите статус");
    }

    @Test
    void emptyScopeDoesNotLoadAnyOrdersOrReminderState() {
        assertThat(diagnostics.workerStaleOrderEntriesForControl(List.of(), "Новый", today)).isEmpty();
        verifyNoInteractions(orders, states);
    }

    private Order waitingOrder() {
        Company company = new Company();
        company.setUrlChat("https://t.me/example_control");
        company.setTelegramGroupChatId(-100L);
        Order order = new Order();
        order.setId(11L);
        order.setCompany(company);
        order.setWaitingForClient(true);
        order.setChanged(today.minusDays(3));
        order.setWaitingForClientChangedAt(today.minusDays(3).atTime(12, 0));
        return order;
    }

    private ScheduledClientMessageState activeState(Order order) {
        var state = new ScheduledClientMessageState();
        state.setOrderId(order.getId());
        state.setScenario(ClientMessageScenario.CLIENT_TEXT_REMINDER);
        state.setTargetKey("client-text:" + order.getId() + ":" + order.getWaitingForClientChangedAt());
        state.setStatus(ScheduledMessageStateStatus.ACTIVE);
        state.setNextAttemptAt(today.plusDays(1).atStartOfDay());
        return state;
    }
}
