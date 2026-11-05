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
    private com.hunt.otziv.manager_control.service.ManagerControlInvoiceDiagnostics invoices;
    private ManagerAuditDeferralFacts deferralFacts;
    private ManagerCommunicationDailyReportSectionService exchange;

    @BeforeEach
    void setUp() {
        concreteRepository = mock(ManagerDailyControlConcreteItemRepository.class);
        unansweredRepository = mock(ClientChatUnansweredItemRepository.class);
        diagnostics = mock(com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.class);
        invoices = mock(com.hunt.otziv.manager_control.service.ManagerControlInvoiceDiagnostics.class);
        deferralFacts = mock(ManagerAuditDeferralFacts.class);
        exchange = mock(ManagerCommunicationDailyReportSectionService.class);
        service = new ManagerReportReviewTaskContextService(concreteRepository, unansweredRepository, diagnostics, invoices,
                deferralFacts, exchange);
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
    void paidInvoiceWithdrawsHistoricalDeferredQuestionWithoutManualCardClosure() {
        var item = source(7682L, ManagerDailyControlItemStatus.DEFERRED);
        item.setEntityType("COMMON_INVOICE");
        item.setEntityId(252L);
        when(concreteRepository.findById(7682L)).thenReturn(Optional.of(item));
        when(invoices.resolvedForAudit(252L)).thenReturn(true);

        assertThat(service.resolvedSatisfactorily(7682L)).isTrue();
    }

    @Test
    void unresolvedInvoiceIsNotWithdrawnBecauseItsOldCardWasManuallyClosed() {
        var item = source(7682L, ManagerDailyControlItemStatus.RESOLVED);
        item.setEntityType("COMMON_INVOICE");
        when(concreteRepository.findById(7682L)).thenReturn(Optional.of(item));

        assertThat(service.resolvedSatisfactorily(7682L)).isFalse();
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
        when(invoices.resolvedForAudit(source.getEntityId())).thenReturn(true);

        assertThat(service.resolvedSatisfactorily(3545L)).isTrue();
    }

    @Test
    void staleResolvedCardDoesNotWithdrawLiveInvoiceFailure() {
        var source = source(3545L, ManagerDailyControlItemStatus.RESOLVED);
        source.setEntityType("COMMON_INVOICE");
        when(concreteRepository.findById(3545L)).thenReturn(Optional.of(source));
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

    @Test
    void bindsUnreferencedConversationOnlyByCompanyAndExactQuotedMessageOnReportDay() {
        var date = java.time.LocalDate.of(2026, 10, 30);
        var manager = new com.hunt.otziv.u_users.model.Manager(); manager.setId(9L);
        var company = new com.hunt.otziv.c_companies.model.Company(); company.setTitle("Furra designer");
        var chat = answered(ClientChatReplyQuality.GOOD, false,
                "Если у вас какая-то проблема вы можете просто удалить отзыв. Зачем писать отзыв с одной звездой?",
                "Здравствуйте, вы оплатили отзывы?");
        chat.setId(6704L); chat.setCompany(company); chat.setManager(manager); chat.setClosedAt(date.atTime(19,24));
        when(unansweredRepository.findDailyReportItems(9L, date.atStartOfDay(), date.plusDays(1).atStartOfDay(),
                ClientChatUnansweredStatus.OPEN)).thenReturn(List.of(chat));
        var question = new ManagerReportReviewQualityService.ReviewQuestion(
                "По Furra designer клиент спросил: «Если у вас какая-то проблема вы можете просто удалить отзыв. Зачем писать отзыв с одной звездой?» Что не так с ответом?",
                List.of("Понять вопрос клиента"));

        var bound = service.bindConversationSource(question, 9L, date);

        assertThat(bound.sourceChatItemIds()).containsExactly(6704L);
        assertThat(bound.question()).isEqualTo(question.question());
        assertThat(bound.expectedPoints()).isEqualTo(question.expectedPoints());
        assertThat(bound.sourceTaskIds()).isEmpty();
        chat.setManager(new com.hunt.otziv.u_users.model.Manager());
        assertThat(service.bindConversationSource(question, 9L, date).sourceChatItemIds()).isEmpty();
    }

    @Test
    void missingOrForeignConversationDoesNotExposeItsMessages() {
        var chat = answered(ClientChatReplyQuality.GOOD, false, "Private foreign message", "Private foreign reply");
        var manager = new com.hunt.otziv.u_users.model.Manager(); manager.setId(10L); chat.setManager(manager);
        when(unansweredRepository.findById(6704L)).thenReturn(Optional.of(chat));

        assertThat(service.conversationEvidence(List.of(6704L),9L))
                .contains("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:", "availability", "unavailable")
                .doesNotContain("Private foreign");
    }

    @Test
    void answerInAnotherMessengerIsNotAManagerFailureWhileTheOwnerChecksIt() {
        ManagerDailyControlConcreteItem source = source(3545L, ManagerDailyControlItemStatus.OPEN);
        ClientChatUnansweredItem chat = answered(ClientChatReplyQuality.PARTIAL, true, "Напишите в Макс", "");
        chat.setResolutionReasonCode("ANSWERED_OUTSIDE_CHAT");
        when(concreteRepository.findById(3545L)).thenReturn(Optional.of(source));
        when(concreteRepository.findAllById(java.util.Set.of(3545L))).thenReturn(List.of(source));
        when(unansweredRepository.findById(1387L)).thenReturn(Optional.of(chat));

        assertThat(service.resolvedSatisfactorily(3545L)).isTrue();
        assertThat(service.refresh("sourceTaskId=3545", 9L))
                .contains("replyQuality=NOT_APPLICABLE", "auditRequired=false", "другом мессенджере");

        chat.setResolutionReasonCode(null);
        assertThat(service.resolvedSatisfactorily(3545L)).isFalse();
        chat.setResolutionReasonCode("ANSWERED_OUTSIDE_CHAT");
        chat.setStatus(ClientChatUnansweredStatus.OPEN);
        assertThat(service.resolvedSatisfactorily(3545L)).isFalse();
    }

    @Test
    void checkerSeesTheWholeExchangeNotOnlyTheLastClientMessage() {
        var manager = new com.hunt.otziv.u_users.model.Manager(); manager.setId(9L);
        var chat = answered(ClientChatReplyQuality.PARTIAL, true, "Как им написать или позвонить?",
                "Вы можете обновить через свой личный кабинет в 2гис");
        chat.setId(6704L); chat.setManager(manager);
        when(unansweredRepository.findById(6704L)).thenReturn(Optional.of(chat));
        var at = LocalDateTime.of(2026, 9, 23, 12, 0);
        when(exchange.exchange(chat)).thenReturn(List.of(
                new ManagerCommunicationDailyReportSectionService.ExchangeMessage(at, "клиент", "Баргузин", "Не нашла их адрес"),
                new ManagerCommunicationDailyReportSectionService.ExchangeMessage(at.plusMinutes(1), "клиент", "Баргузин", "Как им написать или позвонить?"),
                new ManagerCommunicationDailyReportSectionService.ExchangeMessage(at.plusMinutes(5), "сотрудник", "Анжелика",
                        "Телефон поддержки 2ГИС 8 (800) 200-36-00")));

        assertThat(service.conversationEvidence(List.of(6704L), 9L))
                .contains("chat-case:6704:exchange_1", "Не нашла их адрес", "chat-case:6704:exchange_3",
                        "Телефон поддержки 2ГИС", "recorded_chat_message");
    }

    @Test
    void deferredCardIsTheManagersRemarkOnlyWhenTheManagerDeferredALiveProblem() {
        var item = source(4201L, ManagerDailyControlItemStatus.DEFERRED);
        item.setEntityType("ORDER");
        item.setEntityKey("ORDER:21052");
        item.setTitle("Пункт приема цветного и черного металла");
        var parent = new com.hunt.otziv.manager_control.model.ManagerDailyControlItem(); parent.setId(731L);
        item.setParentItem(parent);
        var reportDate = java.time.LocalDate.of(2026, 10, 21);
        when(deferralFacts.deferredByControlManager(731L, item.getTitle())).thenReturn(true);
        when(deferralFacts.stillOnBoard(9L, "ORDER:21052", reportDate)).thenReturn(false);

        assertThat(service.deferralAttributable(item, reportDate)).isFalse();

        when(deferralFacts.stillOnBoard(9L, "ORDER:21052", reportDate)).thenReturn(true);
        assertThat(service.deferralAttributable(item, reportDate)).isTrue();

        when(deferralFacts.deferredByControlManager(731L, item.getTitle())).thenReturn(false);
        assertThat(service.deferralAttributable(item, reportDate)).isFalse();
    }

    @Test
    void commonInvoiceDeferralUsesTheLiveInvoiceRuleInsteadOfTheDailyBoard() {
        var item = source(7682L, ManagerDailyControlItemStatus.DEFERRED);
        item.setEntityType("COMMON_INVOICE");
        item.setEntityKey("COMMON_INVOICE:252");
        var parent = new com.hunt.otziv.manager_control.model.ManagerDailyControlItem(); parent.setId(800L);
        item.setParentItem(parent);
        when(deferralFacts.deferredByControlManager(800L, item.getTitle())).thenReturn(true);

        assertThat(service.deferralAttributable(item, java.time.LocalDate.of(2026, 10, 23))).isTrue();
        org.mockito.Mockito.verify(deferralFacts, org.mockito.Mockito.never())
                .stillOnBoard(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
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
