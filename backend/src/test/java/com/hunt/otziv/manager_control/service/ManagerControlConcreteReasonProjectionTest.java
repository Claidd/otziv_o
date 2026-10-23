package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewCommitmentService;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManagerControlConcreteReasonProjectionTest {
    private final ManagerDailyControlConcreteItemRepository cards = mock(ManagerDailyControlConcreteItemRepository.class);
    private final ManagerControlOrderDeliveryState delivery = mock(ManagerControlOrderDeliveryState.class);
    private final ManagerControlCardLifecycle lifecycle = mock(ManagerControlCardLifecycle.class);
    private final ManagerProblemDiagnosticsService diagnostics = mock(ManagerProblemDiagnosticsService.class);

    @ParameterizedTest
    @ValueSource(strings = {"AUTOMATION_FAILURE", "COMMON_INVOICE"})
    void readUsesCurrentExplanationWithoutWritingOldReasonOrActionHistory(String type) {
        var card = card(type);
        String oldReason = card.getReason();
        String comment = card.getComment();
        LocalDateTime updated = card.getUpdatedAt(), touched = card.getLastManualTouchAt(), followUp = card.getFollowUpAt();
        var source = new ManagerControlConcreteItemResponse(null, type, card.getEntityId(), "Текущий источник", "",
                "Требует внимания", 1L, "Проверьте существующую отправку; её результат не подтверждён.",
                "/admin/source", null, null, null, null, "OPEN", null, null, null, null, null);
        when(cards.findByParentItem(card.getParentItem())).thenReturn(List.of(card));

        var response = workflow().readConcreteExamples(card.getParentItem(), List.of(source)).getFirst();

        assertThat(response.reason()).isEqualTo(source.reason()).doesNotContain("JDBC", "payment_instruction_failed");
        assertThat(response.comment()).isEqualTo(comment);
        assertThat(response.itemStatus()).isEqualTo("ACTION_TAKEN");
        assertThat(response.actionType()).isEqualTo("ACTION_TAKEN");
        assertThat(response.updatedAt()).isEqualTo(updated);
        assertThat(response.lastManualTouchAt()).isEqualTo(touched);
        assertThat(response.followUpAt()).isEqualTo(followUp);
        assertThat(card.getReason()).isEqualTo(oldReason);
        assertThat(card.getComment()).isEqualTo(comment);
        assertThat(card.getStatus()).isEqualTo(ManagerDailyControlItemStatus.ACTION_TAKEN);
        assertThat(card.getUpdatedAt()).isEqualTo(updated);
        assertThat(card.getLastManualTouchAt()).isEqualTo(touched);
        assertThat(card.getFollowUpAt()).isEqualTo(followUp);
        verify(cards, never()).save(any());
        verifyNoInteractions(lifecycle);
        verify(diagnostics, never()).requestRefresh(any());
    }

    @Test
    void absentCurrentSourceRetainsStoredExplanationAndPendingDeliveryState() {
        var card = card("COMMON_INVOICE");
        card.setReason("Счет ожидает сверки состава с ответственным специалистом.");
        when(cards.findByParentItem(card.getParentItem())).thenReturn(List.of(card));
        when(delivery.pending(card)).thenReturn(true);

        var response = workflow().readConcreteExamples(card.getParentItem(), List.of()).getFirst();

        assertThat(response.reason()).isEqualTo(card.getReason());
        assertThat(response.itemStatus()).isEqualTo("ACTION_TAKEN");
        assertThat(response.comment()).isEqualTo(card.getComment());
        verify(cards, never()).save(any());
        verifyNoInteractions(lifecycle);
        verify(diagnostics, never()).requestRefresh(any());
    }

    @Test
    void absentCurrentSourceHidesOnlyTechnicalLegacyTextWithoutInventingCurrentFactsOrRewritingHistory() {
        var card = card("COMMON_INVOICE");
        String stored = card.getReason();
        LocalDateTime updated = card.getUpdatedAt();
        when(cards.findByParentItem(card.getParentItem())).thenReturn(List.of(card));
        when(delivery.pending(card)).thenReturn(true);

        var response = workflow().readConcreteExamples(card.getParentItem(), List.of()).getFirst();

        assertThat(response.reason()).contains("Сохраненное замечание", "актуальные данные источника недоступны",
                "Не повторяйте отправку").doesNotContain("JDBC", "select", "payment_instruction_failed");
        assertThat(card.getReason()).isEqualTo(stored);
        assertThat(card.getUpdatedAt()).isEqualTo(updated);
        assertThat(response.comment()).isEqualTo(card.getComment());
        assertThat(response.itemStatus()).isEqualTo("ACTION_TAKEN");
        verify(cards, never()).save(any());
        verifyNoInteractions(lifecycle);
        verify(diagnostics, never()).requestRefresh(any());
    }

    private ManagerControlConcreteSnapshotWorkflow workflow() {
        var sla = mock(ManagerControlSlaPolicy.class);
        when(sla.decorateConcreteSla(any(), any())).thenAnswer(call -> call.getArgument(1));
        var presenter = new ManagerControlConcretePresenter(diagnostics, mock(ManagerReportReviewCommitmentService.class),
                mock(WorkerRiskIncidentRepository.class), mock(ManagerControlWorkerTaskLookup.class),
                mock(ManagerControlInvoiceDiagnostics.class), mock(ScheduledClientMessageStateRepository.class),
                mock(CompanyRepository.class), sla, delivery);
        return new ManagerControlConcreteSnapshotWorkflow(diagnostics, mock(ManagerControlProblemExamples.class, CALLS_REAL_METHODS), lifecycle,
                sla, presenter, cards, delivery);
    }

    private ManagerDailyControlConcreteItem card(String type) {
        var parent = new ManagerDailyControlItem(); parent.setId(22L); parent.setReasonCode("COMMON_INVOICES");
        var card = new ManagerDailyControlConcreteItem();
        card.setId(31L); card.setParentItem(parent); card.setEntityType(type); card.setEntityId(297L);
        card.setEntityKey(type + ":297"); card.setTitle("Сохранённая карточка");
        card.setReason("payment_instruction_failed: JDBC select order_id from orders");
        card.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN); card.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        card.setComment("Менеджер уже передал проблему специалисту");
        card.setUpdatedAt(LocalDateTime.now().minusHours(3));
        card.setLastManualTouchAt(LocalDateTime.now().minusHours(2));
        card.setFollowUpAt(LocalDateTime.now().minusHours(1));
        return card;
    }
}
