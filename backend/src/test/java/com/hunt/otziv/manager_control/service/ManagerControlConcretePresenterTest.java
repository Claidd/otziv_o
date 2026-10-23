package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewCommitmentService;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagerControlConcretePresenterTest {
    private static final String FACTS = "Клиент не проверил шаблоны 108 дн. Исход отправки не подтверждён.";
    private static final String OLD_ADVICE = "Если починка недоступна или не помогла, скопируйте текст, откройте чат, отправьте ссылку на проверку и нажмите «Отправлено».";
    private final ManagerControlOrderDeliveryState deliveryState = mock(ManagerControlOrderDeliveryState.class);

    @Test
    void blockedContactIsExplainedToOlderClientsWithoutChangingStoredProblemFacts() {
        var card = card();
        String blocked = "Исход автоматической отправки не подтверждён. Не отправляйте повторное сообщение вручную.";
        when(deliveryState.sendBlockedReason(card)).thenReturn(blocked);

        var response = presenter().concreteItemResponse(card, "Текст сообщения", "Специалист");

        assertThat(response.reason()).contains(FACTS, blocked).doesNotContain(OLD_ADVICE, "скопируйте текст");
        assertThat(response.clientMessageSendBlockedReason()).isEqualTo(blocked);
        assertThat(response.ageDays()).isEqualTo(108L);
        assertThat(response.status()).isEqualTo("На проверке");
        assertThat(card.getReason()).isEqualTo(FACTS + " " + OLD_ADVICE);
    }

    @Test
    void unblockedContactKeepsTheExistingProblemPresentation() {
        var card = card();

        var response = presenter().concreteItemResponse(card, "Текст сообщения", "Специалист");

        assertThat(response.reason()).isEqualTo(card.getReason());
        assertThat(response.clientMessageSendBlockedReason()).isNull();
    }

    @Test
    void freshExplanationStillIncludesAuthoritativeDeliveryFenceWithoutChangingStoredReason() {
        var card = card();
        String fresh = "Не удалось подготовить счёт; откройте платёж для сверки.";
        String blocked = "Исход отправки не подтверждён. Не отправляйте повторно.";
        when(deliveryState.sendBlockedReason(card)).thenReturn(blocked);

        var response = presenter().concreteItemResponse(card, "Текст", "Специалист", fresh);

        assertThat(response.reason()).contains(fresh, blocked).doesNotContain(FACTS, OLD_ADVICE);
        assertThat(response.clientMessageSendBlockedReason()).isEqualTo(blocked);
        assertThat(card.getReason()).isEqualTo(FACTS + " " + OLD_ADVICE);
    }

    private ManagerControlConcretePresenter presenter() {
        var sla = mock(ManagerControlSlaPolicy.class);
        when(sla.decorateConcreteSla(any(), any())).thenAnswer(call -> call.getArgument(1));
        return new ManagerControlConcretePresenter(mock(ManagerProblemDiagnosticsService.class),
                mock(ManagerReportReviewCommitmentService.class), mock(WorkerRiskIncidentRepository.class),
                mock(ManagerControlWorkerTaskLookup.class), mock(ManagerControlInvoiceDiagnostics.class),
                mock(ScheduledClientMessageStateRepository.class), mock(CompanyRepository.class), sla, deliveryState);
    }

    private ManagerDailyControlConcreteItem card() {
        var card = new ManagerDailyControlConcreteItem();
        card.setId(1L);
        card.setEntityType("ORDER");
        card.setEntityId(77L);
        card.setAgeDays(108L);
        card.setStatusLabel("На проверке");
        card.setReason(FACTS + " " + OLD_ADVICE);
        return card;
    }
}
