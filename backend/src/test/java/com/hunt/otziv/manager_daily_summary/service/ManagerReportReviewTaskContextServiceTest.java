package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.client_chat_control.model.ClientChatReplyQuality;
import com.hunt.otziv.client_chat_control.model.ClientChatResolutionType;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagerReportReviewTaskContextServiceTest {

    private ManagerDailyControlConcreteItemRepository concreteRepository;
    private ClientChatUnansweredItemRepository unansweredRepository;
    private ManagerReportReviewTaskContextService service;
    private com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService diagnostics;

    @BeforeEach
    void setUp() {
        concreteRepository = mock(ManagerDailyControlConcreteItemRepository.class);
        unansweredRepository = mock(ClientChatUnansweredItemRepository.class);
        diagnostics = mock(com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.class);
        service = new ManagerReportReviewTaskContextService(concreteRepository, unansweredRepository, diagnostics);
    }

    @Test
    void includesClientMessageManagerReplyAndQualityInFreshContext() {
        ManagerDailyControlConcreteItem source = source(3545L, ManagerDailyControlItemStatus.RESOLVED);
        ClientChatUnansweredItem chat = answered(
                ClientChatReplyQuality.GOOD,
                false,
                "Добрый день! Пока приостановим",
                "Здравствуйте, поняла вас, хорошо"
        );
        when(concreteRepository.findAllById(java.util.Set.of(3545L))).thenReturn(List.of(source));
        when(concreteRepository.findById(3545L)).thenReturn(Optional.of(source));
        when(unansweredRepository.findById(1387L)).thenReturn(Optional.of(chat));

        String context = service.refresh("Карточка Каприз\nsourceTaskId=3545", 9L);

        assertThat(context)
                .contains("currentStatus=RESOLVED")
                .contains("clientMessage=Добрый день! Пока приостановим")
                .contains("managerReply=Здравствуйте, поняла вас, хорошо")
                .contains("replyQuality=GOOD");
        assertThat(service.resolvedSatisfactorily(3545L)).isTrue();
    }

    @Test
    void doesNotTreatPartialReplyAsSatisfactoryCompletion() {
        ManagerDailyControlConcreteItem source = source(3545L, ManagerDailyControlItemStatus.RESOLVED);
        ClientChatUnansweredItem chat = answered(
                ClientChatReplyQuality.PARTIAL,
                true,
                "Когда будет готово?",
                "Хорошо"
        );
        when(concreteRepository.findById(3545L)).thenReturn(Optional.of(source));
        when(unansweredRepository.findById(1387L)).thenReturn(Optional.of(chat));

        assertThat(service.resolvedSatisfactorily(3545L)).isFalse();
    }

    @Test
    void missingSourceIsNotEvidenceOfResolution() {
        when(concreteRepository.findById(3545L)).thenReturn(Optional.empty());

        assertThat(service.resolvedSatisfactorily(3545L)).isFalse();
    }

    @Test
    void onlyAnOwnedAutomationSourceCanBeExcludedAsSystemWork() {
        var item = source(3545L, ManagerDailyControlItemStatus.OPEN);
        item.setEntityType("AUTOMATION_FAILURE");
        when(concreteRepository.findById(3545L)).thenReturn(Optional.of(item));
        when(diagnostics.systemOwnsDeliveryReconciliation(3545L)).thenReturn(true);
        assertThat(service.systemOwnsSource(3545L, 9L)).isTrue();
        assertThat(service.systemOwnsSource(3545L, 10L)).isFalse();
        item.setEntityType("COMMON_INVOICE");
        assertThat(service.systemOwnsSource(3545L, 9L)).isFalse();
        assertThat(service.systemOwnsSource(999L, 9L)).isFalse();
    }

    @Test
    void deferredCardDoesNotWithdrawAuditQuestion() {
        ManagerDailyControlConcreteItem source = source(3545L, ManagerDailyControlItemStatus.DEFERRED);
        when(concreteRepository.findById(3545L)).thenReturn(Optional.of(source));

        assertThat(service.resolvedSatisfactorily(3545L)).isFalse();
    }

    @Test
    void laterResolvedCardWithdrawsEarlierDeferredQuestion() {
        ManagerDailyControlConcreteItem source = source(3545L, ManagerDailyControlItemStatus.DEFERRED);
        source.setEntityType("COMMON_INVOICE");
        source.setEntityKey("COMMON_INVOICE:175");
        LocalDateTime deferredAt = LocalDateTime.now().minusDays(2);
        source.setDeferredAt(deferredAt);
        ManagerDailyControl control = mock(ManagerDailyControl.class);
        when(control.managerId()).thenReturn(9L);
        source.setControl(control);
        when(concreteRepository.findById(3545L)).thenReturn(Optional.of(source));
        when(concreteRepository.existsResolutionAfterDeferral(
                9L, "COMMON_INVOICE:175", deferredAt, ManagerDailyControlItemStatus.RESOLVED))
                .thenReturn(true);
        when(diagnostics.resolutionState(3545L)).thenReturn(
                com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.ResolutionState.RESOLVED);

        assertThat(service.resolvedSatisfactorily(3545L)).isTrue();
    }

    @Test
    void staleResolvedCardDoesNotWithdrawLiveInvoiceFailure() {
        var source = source(3545L, ManagerDailyControlItemStatus.RESOLVED);
        source.setEntityType("COMMON_INVOICE");
        when(concreteRepository.findById(3545L)).thenReturn(Optional.of(source));
        when(diagnostics.resolutionState(3545L)).thenReturn(
                com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.ResolutionState.OPEN);
        assertThat(service.resolvedSatisfactorily(3545L)).isFalse();
    }

    @Test
    void foreignSourceCannotExposeMessagesOrSupplyAuditFacts() {
        var source = source(3545L, ManagerDailyControlItemStatus.OPEN);
        when(concreteRepository.findAllById(java.util.Set.of(3545L))).thenReturn(List.of(source));

        String context = service.refresh("sourceTaskId=3545", 17L);

        assertThat(context).contains("evidenceStatus=UNAVAILABLE").doesNotContain("currentStatus=", "Каприз");
        org.mockito.Mockito.verifyNoInteractions(unansweredRepository);
        org.mockito.Mockito.verify(diagnostics).contextForAudit(java.util.Set.of());
    }

    @Test
    void storedAuthoritativeMarkerCannotPretendToBeFreshWithoutSources() {
        String context = service.refresh("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:\nстарый статус", 9L);
        assertThat(context).doesNotContain("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:")
                .contains("Ранее сохранённый контекст:");
        org.mockito.Mockito.verifyNoInteractions(diagnostics, unansweredRepository);
    }

    @Test
    void refreshSourcesReadsOnlyExplicitQuestionIdsWithoutHistoricalPrefix() {
        var source = source(3545L, ManagerDailyControlItemStatus.OPEN);
        when(concreteRepository.findAllById(java.util.Set.of(3545L))).thenReturn(List.of(source));
        when(diagnostics.contextForAudit(java.util.Set.of(3545L))).thenReturn("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:\ncomplete-facts");

        String context = service.refreshSources("sourceTaskId=999\n" + "old-context".repeat(60_000), List.of(3545L), 9L);

        assertThat(context).contains("sourceTaskId=3545", "complete-facts").doesNotContain("sourceTaskId=999", "old-context");
        org.mockito.Mockito.verify(concreteRepository).findAllById(java.util.Set.of(3545L));
    }

    @Test
    void unboundPlanRefreshesReferencedSourcesButNeverRepeatsHistoricalText() {
        var source = source(3545L, ManagerDailyControlItemStatus.OPEN);
        when(concreteRepository.findAllById(java.util.Set.of(3545L, 42L))).thenReturn(List.of(source));
        when(diagnostics.contextForAudit(java.util.Set.of(3545L))).thenReturn("complete-facts");

        String context = service.refreshSources("sourceTaskId=3545\nsourceTaskId=42\nold-context", List.of(), 9L);

        assertThat(context).contains("sourceTaskId=3545", "sourceTaskId=42; evidenceStatus=UNAVAILABLE", "complete-facts")
                .doesNotContain("old-context");
    }

    @Test
    void refreshSourcesCannotUseUnscopedHistoryAsCurrentEvidence() {
        String context = service.refreshSources("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:\nold-history", List.of(), 9L);
        assertThat(context).contains("не подтверждено").doesNotContain("old-history", "СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:");
        org.mockito.Mockito.verifyNoInteractions(concreteRepository, diagnostics, unansweredRepository);
    }

    private ManagerDailyControlConcreteItem source(
            Long id,
            ManagerDailyControlItemStatus status
    ) {
        ManagerDailyControlConcreteItem source = new ManagerDailyControlConcreteItem();
        source.setId(id);
        source.setEntityType("CLIENT_CHAT_UNANSWERED");
        source.setEntityId(1387L);
        source.setTitle("Каприз");
        source.setStatus(status);
        var control = mock(ManagerDailyControl.class);
        org.mockito.Mockito.lenient().when(control.managerId()).thenReturn(9L);
        source.setControl(control);
        return source;
    }

    private ClientChatUnansweredItem answered(
            ClientChatReplyQuality quality,
            boolean auditRequired,
            String clientMessage,
            String managerReply
    ) {
        ClientChatUnansweredItem item = new ClientChatUnansweredItem();
        item.setStatus(ClientChatUnansweredStatus.ANSWERED);
        item.setLastMessageText(clientMessage);
        item.setResolutionReplyText(managerReply);
        item.setResolutionType(ClientChatResolutionType.ANSWERED);
        item.setReplyQuality(quality);
        item.setReplyQualityReason(quality == ClientChatReplyQuality.GOOD
                ? "Ответ соответствует сообщению клиента"
                : "Ответ слишком общий");
        item.setAuditRequired(auditRequired);
        return item;
    }
}
