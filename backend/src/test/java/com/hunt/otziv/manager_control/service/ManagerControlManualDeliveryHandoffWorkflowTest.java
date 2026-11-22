package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.client_messages.api.UncertainClientMessageDelivery;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.core.Authentication;

/** A decision on an unconfirmed send also closes the cards earlier control days keep for the same task. */
class ManagerControlManualDeliveryHandoffWorkflowTest {

    @ParameterizedTest
    @EnumSource(UncertainClientMessageDelivery.DeliveryDecision.class)
    void aDecisionClosesTheOtherOpenCardsOfTheSameTask(UncertainClientMessageDelivery.DeliveryDecision decision) {
        var cards = mock(ManagerDailyControlConcreteItemRepository.class);
        var failures = mock(ManagerAutomationFailureService.class);
        var messages = mock(UncertainClientMessageDelivery.class);
        var lifecycle = mock(ManagerControlCardLifecycle.class);
        var repairOutcome = mock(ManagerControlRepairOutcome.class);
        var workflow = new ManagerControlManualDeliveryHandoffWorkflow(cards, mock(ManagerControlAccessPolicy.class),
                mock(ManagerPermissionService.class), failures, messages, lifecycle,
                mock(ManagerControlConcretePresenter.class), repairOutcome);

        var manager = new Manager();
        manager.setId(2L);
        var control = new ManagerDailyControl();
        control.setManager(manager);
        var decided = card(10L, control, ManagerDailyControlItemStatus.OPEN);
        var yesterday = card(11L, control, ManagerDailyControlItemStatus.OPEN);
        var closedBefore = card(12L, control, ManagerDailyControlItemStatus.RESOLVED);
        closedBefore.setComment("закрыта раньше");
        when(cards.findById(10L)).thenReturn(Optional.of(decided));
        when(failures.findIssue(manager, ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE, 501L))
                .thenReturn(Optional.of(new ManagerAutomationFailureService.AutomationFailureIssue(
                        "AUTOMATION_FAILURE:501", ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE, 501L, 501L,
                        null, "Компания", "Напоминание", "Доставка не подтверждена",
                        "state_transaction_outcome_uncertain", "/orders", null,
                        ClientMessageScenario.PAYMENT_REMINDER, 1, LocalDateTime.now().minusDays(2),
                        LocalDateTime.now().minusHours(1), null)));
        when(messages.decide(eq(501L), eq("op-1"), eq(decision), any(), eq(10L), anyString()))
                .thenReturn(new UncertainClientMessageDelivery.DecisionOutcome(decision, "Итог решения"));
        when(cards.findByEntityTypeAndEntityId(ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE, 501L))
                .thenReturn(List.of(decided, yesterday, closedBefore));

        workflow.handoff(10L, new ManagerControlManualDeliveryHandoffWorkflow.Request(
                "op-1", "Проверил чат клиента", true, decision.name()), mock(Principal.class), mock(Authentication.class));

        assertThat(yesterday.getStatus()).isEqualTo(ManagerDailyControlItemStatus.RESOLVED);
        assertThat(yesterday.getComment()).isEqualTo("Закрыто решением по этой же отправке в карточке №10: Итог решения");
        assertThat(yesterday.isAutomaticResolution()).isTrue();
        assertThat(yesterday.getResolvedAt()).isNotNull();
        verify(cards).save(yesterday);
        assertThat(closedBefore.getComment()).isEqualTo("закрыта раньше");
        verify(cards, never()).save(closedBefore);
        // Every decision settles the send, «send again» included: past days are never synchronized again.
        verify(repairOutcome).resolveRepairedConcreteItem(eq(decided), eq(control), eq("Итог решения"), any(), anyString());
    }

    @org.junit.jupiter.api.Test
    void anOrderCardOfAnOrderThatMovedToAnotherManagerCannotDecideItsSend() {
        var cards = mock(ManagerDailyControlConcreteItemRepository.class);
        var access = mock(ManagerControlAccessPolicy.class);
        var messages = mock(UncertainClientMessageDelivery.class);
        var workflow = new ManagerControlManualDeliveryHandoffWorkflow(cards, access, mock(ManagerPermissionService.class),
                mock(ManagerAutomationFailureService.class), messages, mock(ManagerControlCardLifecycle.class),
                mock(ManagerControlConcretePresenter.class), mock(ManagerControlRepairOutcome.class));
        var control = new ManagerDailyControl();
        control.setManager(new Manager());
        var card = card(30L, control, ManagerDailyControlItemStatus.OPEN);
        card.setEntityType("ORDER");
        card.setEntityId(123L);
        when(cards.findById(30L)).thenReturn(Optional.of(card));
        var authentication = mock(Authentication.class);
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "Заказ передан другому менеджеру"))
                .when(access).requireOwnOrder(123L, authentication);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> workflow.handoff(30L,
                new ManagerControlManualDeliveryHandoffWorkflow.Request("op-1", "", true, "CLOSE"),
                mock(Principal.class), authentication))
                .hasMessageContaining("другому менеджеру");
        org.mockito.Mockito.verifyNoInteractions(messages);
    }

    private static ManagerDailyControlConcreteItem card(Long id, ManagerDailyControl control,
                                                        ManagerDailyControlItemStatus status) {
        var card = new ManagerDailyControlConcreteItem();
        card.setId(id);
        card.setControl(control);
        card.setEntityType(ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE);
        card.setEntityId(501L);
        card.setStatus(status);
        return card;
    }
}
