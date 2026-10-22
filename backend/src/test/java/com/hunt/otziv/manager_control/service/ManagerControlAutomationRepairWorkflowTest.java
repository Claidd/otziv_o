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

    @Test
    void checkedHistoryWithoutReceiptRequiresManualHandoffWithoutRetry() {
        var stateRepository = mock(ScheduledClientMessageStateRepository.class);
        var scheduledMessages = mock(ScheduledClientMessageService.class);
        var failures = mock(ManagerAutomationFailureService.class);
        var repairOutcome = mock(ManagerControlRepairOutcome.class);
        var workflow = new ManagerControlAutomationRepairWorkflow(
                mock(ManagerControlChatRepairWorkflow.class), repairOutcome,
                mock(ManagerControlProblemExamples.class), mock(ManagerControlOrderAutomationDiagnostics.class),
                scheduledMessages, stateRepository, mock(ClientChatMessageReconciliationService.class), failures);

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
                .lastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN)
                .build();
        when(failures.findIssue(manager, item.getEntityType(), item.getEntityId()))
                .thenReturn(Optional.of(issue));
        when(stateRepository.findById(501L)).thenReturn(Optional.of(state));
        when(scheduledMessages.recoverUncertainOrdinaryDelivery(501L)).thenReturn(
                ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.HISTORY_CHECKED_NO_RECEIPT);

        var failure = assertThrows(ResponseStatusException.class,
                () -> workflow.repairAutomationFailureConcreteItem(item, control, () -> "admin"));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertTrue(failure.getReason().contains("Передать вручную"));
        assertTrue(failure.getReason().contains("автоматический повтор заблокирован"));
        verify(scheduledMessages, never()).retryNow(anyLong());
        verifyNoInteractions(repairOutcome);
    }
}
