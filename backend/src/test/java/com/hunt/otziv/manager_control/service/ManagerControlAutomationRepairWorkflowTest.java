package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_chat_control.service.ClientChatMessageReconciliationService;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ClientMessageStateSafety;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManagerControlAutomationRepairWorkflowTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.class,
            names = {"HISTORY_UNAVAILABLE", "HISTORY_CHECKED_NO_RECEIPT", "UNRESOLVED", "SENT_CONFIRMED"})
    void orderCardReconcilesTheExistingUncertainOperationWithoutRearmingOrSending(
            ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult receipt) {
        var messages = mock(ScheduledClientMessageService.class);
        var states = mock(ScheduledClientMessageStateRepository.class);
        var diagnostics = new ManagerControlOrderAutomationDiagnostics(mock(com.hunt.otziv.p_products.repository.OrderRepository.class), states);
        var outcome = mock(ManagerControlRepairOutcome.class);
        var workflow = new ManagerControlAutomationRepairWorkflow(mock(ManagerControlChatRepairWorkflow.class), outcome,
                mock(ManagerControlProblemExamples.class), diagnostics, messages, states,
                mock(ClientChatMessageReconciliationService.class), mock(ManagerAutomationFailureService.class),
                new com.hunt.otziv.client_messages.service.CurrentOrderDeliveryUncertaintyService(states, mock(org.springframework.jdbc.core.JdbcTemplate.class)), mock(ManagerControlManualDeliveryRepair.class));
        var order = new com.hunt.otziv.p_products.model.Order(); order.setId(77L);
        order.setStatusChangedAt(LocalDateTime.of(2026, 7, 14, 0, 0));
        var state = ScheduledClientMessageState.builder().id(3938L).orderId(77L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER).targetKey("order:77:2026-07-14T00:00")
                .deliveryStatus("UNKNOWN").lastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN).build();
        when(messages.orderAutomationScenario(order)).thenReturn(Optional.of(ClientMessageScenario.REVIEW_CHECK_REMINDER));
        when(states.findByOrderIdIn(java.util.List.of(77L))).thenReturn(java.util.List.of(state));
        when(states.findById(3938L)).thenReturn(Optional.of(state));
        when(messages.recoverUncertainOrdinaryDelivery(3938L)).thenReturn(receipt);
        var card = new ManagerDailyControlConcreteItem(); card.setId(1L); card.setEntityType("ORDER"); card.setEntityId(77L);
        var control = new ManagerDailyControl();
        if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.SENT_CONFIRMED)
            workflow.repairOrderAutomationConcreteItem(card, control, order, () -> "manager");
        else assertThrows(ResponseStatusException.class,
                () -> workflow.repairOrderAutomationConcreteItem(card, control, order, () -> "manager"));
        verify(messages).recoverUncertainOrdinaryDelivery(3938L);
        verify(messages, never()).ensureOrderAutomationForOrder(org.mockito.ArgumentMatchers.any());
        verify(messages, never()).retryNow(anyLong());
        if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.SENT_CONFIRMED)
            verify(outcome).recordConfirmedContact(org.mockito.ArgumentMatchers.eq(card), org.mockito.ArgumentMatchers.eq(control),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(state.getTargetKey()));
        verify(outcome, never()).resolveRepairedConcreteItem(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test void recoveredContactRetainsFollowUpWhenTheOpenParentIsRefreshed() {
        var cards = mock(com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository.class);
        when(cards.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));
        var lifecycle = mock(ManagerControlCardLifecycle.class);
        var outcome = new ManagerControlRepairOutcome(mock(ManagerControlProblemExamples.class), mock(ManagerControlAccessPolicy.class),
                lifecycle, mock(ManagerControlConcretePresenter.class), mock(com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository.class), cards,
                mock(ManagerControlOrderDeliveryState.class));
        var card = new ManagerDailyControlConcreteItem(); card.setId(1L); card.setEntityType("ORDER"); card.setControl(new ManagerDailyControl());
        when(cards.findByIdForUpdate(1L)).thenReturn(Optional.of(card));
        card.setStatus(com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus.OPEN);
        var parent = new com.hunt.otziv.manager_control.model.ManagerDailyControlItem();
        parent.setStatus(com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus.OPEN);
        parent.setGroup(com.hunt.otziv.manager_control.model.ManagerDailyControlGroup.ACTION);
        outcome.recordConfirmedContact(card, card.getControl(), "fixture receipt confirmed", () -> "manager", "fixture", "order:77:2026-07-14T00:00");
        var snapshot = new ManagerControlConcreteSnapshotWorkflow(mock(com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.class),
                mock(ManagerControlProblemExamples.class), lifecycle, mock(ManagerControlSlaPolicy.class),
                mock(ManagerControlConcretePresenter.class), cards, mock(ManagerControlOrderDeliveryState.class));
        assertEquals(com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus.ACTION_TAKEN, card.getStatus());
        assertEquals(null, card.getResolvedAt());
        assertTrue(snapshot.isConcreteSnoozed(card));
        org.junit.jupiter.api.Assertions.assertFalse(snapshot.reopenResolvedConcreteItemStillActive(parent, card));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"state_transaction_outcome_uncertain", "manual_delivery_handoff_pending"})
    void checkedHistoryWithoutReceiptContinuesInternallyWithoutRetryOrManagerHandoff(String errorCode) {
        var stateRepository = mock(ScheduledClientMessageStateRepository.class);
        var scheduledMessages = mock(ScheduledClientMessageService.class);
        var failures = mock(ManagerAutomationFailureService.class);
        var repairOutcome = mock(ManagerControlRepairOutcome.class);
        var workflow = new ManagerControlAutomationRepairWorkflow(
                mock(ManagerControlChatRepairWorkflow.class), repairOutcome,
                mock(ManagerControlProblemExamples.class), mock(ManagerControlOrderAutomationDiagnostics.class),
                scheduledMessages, stateRepository, mock(ClientChatMessageReconciliationService.class), failures,
                new com.hunt.otziv.client_messages.service.CurrentOrderDeliveryUncertaintyService(stateRepository, mock(org.springframework.jdbc.core.JdbcTemplate.class)), mock(ManagerControlManualDeliveryRepair.class));

        var manager = new Manager();
        manager.setId(3L);
        var control = new ManagerDailyControl();
        control.setManager(manager);
        var item = new ManagerDailyControlConcreteItem();
        item.setEntityType(ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE);
        item.setEntityId(501L);
        var issue = new ManagerAutomationFailureService.AutomationFailureIssue(
                "AUTOMATION_FAILURE:501", item.getEntityType(), 501L, 501L, null,
                "Компания", "Напоминание", "Ошибка автоматизации", "state_transaction_outcome_uncertain",
                "/orders", null, ClientMessageScenario.REVIEW_CHECK_REMINDER, 1,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().minusMinutes(30), null);
        var state = ScheduledClientMessageState.builder()
                .id(501L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER)
                .lastErrorCode(errorCode)
                .build();
        when(failures.findIssue(manager, item.getEntityType(), item.getEntityId()))
                .thenReturn(Optional.of(issue));
        when(stateRepository.findById(501L)).thenReturn(Optional.of(state));
        when(scheduledMessages.recoverUncertainOrdinaryDelivery(501L)).thenReturn(
                ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.HISTORY_CHECKED_NO_RECEIPT);

        var failure = assertThrows(ResponseStatusException.class,
                () -> workflow.repairAutomationFailureConcreteItem(item, control, () -> "admin"));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertTrue(failure.getReason().contains("продолжит сверку автоматически"));
        assertTrue(failure.getReason().contains("Новое сообщение не отправлялось"));
        org.junit.jupiter.api.Assertions.assertFalse(failure.getReason().contains("Передать вручную"));
        verify(scheduledMessages, never()).retryNow(anyLong());
        verifyNoInteractions(repairOutcome);
    }

    @Test void lateHandoffReceiptResolvesOnlyTheDisappearedAutomationCauseWithoutRetryOrChatRepair() {
        var states = mock(ScheduledClientMessageStateRepository.class);
        var messages = mock(ScheduledClientMessageService.class);
        var failures = mock(ManagerAutomationFailureService.class);
        var outcome = mock(ManagerControlRepairOutcome.class);
        var chat = mock(ManagerControlChatRepairWorkflow.class);
        var workflow = new ManagerControlAutomationRepairWorkflow(chat, outcome, mock(ManagerControlProblemExamples.class),
                mock(ManagerControlOrderAutomationDiagnostics.class), messages, states,
                mock(ClientChatMessageReconciliationService.class), failures,
                mock(com.hunt.otziv.client_messages.api.CurrentOrderDeliveryUncertainty.class), mock(ManagerControlManualDeliveryRepair.class));
        var manager = new Manager(); manager.setId(2L);
        var control = new ManagerDailyControl(); control.setManager(manager);
        var card = new ManagerDailyControlConcreteItem(); card.setEntityType("AUTOMATION_FAILURE"); card.setEntityId(501L);
        var issue = new ManagerAutomationFailureService.AutomationFailureIssue("AUTOMATION_FAILURE:501", "AUTOMATION_FAILURE",
                501L, 501L, null, "Company", "Reminder", "Failure", "handoff", "/orders", null,
                ClientMessageScenario.REVIEW_CHECK_REMINDER, 1, LocalDateTime.now(), LocalDateTime.now(), null);
        when(failures.findIssue(manager, card.getEntityType(), 501L)).thenReturn(Optional.of(issue), Optional.empty());
        when(states.findById(501L)).thenReturn(Optional.of(ScheduledClientMessageState.builder().id(501L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER).deliveryStatus("UNKNOWN")
                .lastErrorCode(ClientMessageStateSafety.MANUAL_DELIVERY_HANDOFF_PENDING).build()));
        when(messages.recoverUncertainOrdinaryDelivery(501L))
                .thenReturn(ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.SENT_CONFIRMED);
        workflow.repairAutomationFailureConcreteItem(card, control, () -> "manager");
        verify(messages).recoverUncertainOrdinaryDelivery(501L);
        verify(messages, never()).retryNow(anyLong());
        verifyNoInteractions(chat);
        verify(outcome).resolveRepairedConcreteItem(org.mockito.ArgumentMatchers.eq(card), org.mockito.ArgumentMatchers.eq(control),
                org.mockito.ArgumentMatchers.contains("подтверждена"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
}
