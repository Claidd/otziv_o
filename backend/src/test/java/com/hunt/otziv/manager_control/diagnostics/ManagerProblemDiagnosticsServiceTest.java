package com.hunt.otziv.manager_control.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.api.ManagerProblemDiagnosticSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.*;

class ManagerProblemDiagnosticsServiceTest {
    private final ManagerProblemEvidenceCollector collector = mock(ManagerProblemEvidenceCollector.class);
    private final ManagerProblemDiagnosisStore store = mock(ManagerProblemDiagnosisStore.class);
    private final ManagerProblemDiagnosticSettings settings = mock(ManagerProblemDiagnosticSettings.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ManagerProblemDiagnosticsService service = new ManagerProblemDiagnosticsService(collector, store, settings, new ObjectMapper(), new SimpleMeterRegistry(), events);

    @BeforeEach
    void setUp() { when(settings.mode()).thenReturn("SHADOW"); }

    @Test
    void shadowKeepsFactsButDoesNotPresentModelAdviceAsAuthority() {
        List<Fact> facts = List.of(new Fact("invoice:189:status", "invoice", "status", "COLLECTING"));
        stub(facts);
        DiagnosticView result = service.refresh(10L);
        assertThat(result.facts()).isEqualTo(facts);
        assertThat(result.diagnosis()).isNull();
        assertThat(result.rolloutMode()).isEqualTo("SHADOW");
    }

    @Test
    void uncertainAutomationReceiptIsSystemWorkButInvoiceReadinessRemainsManagerWork() {
        var unknown = new Fact("automation:90:delivery_status", "automation", "delivery_status", "UNKNOWN");
        stub(List.of(new Fact("card:10:entity_type", "card", "entity_type", "AUTOMATION_FAILURE"), unknown));
        assertThat(service.systemOwnsDeliveryReconciliation(10L)).isTrue();
        stub(List.of(new Fact("card:10:entity_type", "card", "entity_type", "COMMON_INVOICE"), unknown));
        assertThat(service.systemOwnsDeliveryReconciliation(10L)).isFalse();
        stub(List.of(new Fact("card:10:entity_type", "card", "entity_type", "AUTOMATION_FAILURE"), unknown,
                new Fact("automation:90:current_cycle", "automation", "current_cycle", "false")));
        assertThat(service.systemOwnsDeliveryReconciliation(10L)).isFalse();
        when(collector.collect(10L)).thenReturn(null);
        assertThat(service.systemOwnsDeliveryReconciliation(10L)).isFalse();
    }

    @Test
    void onlyPilotManagersSeeAdvisoryDiagnosis() {
        when(settings.mode()).thenReturn("PILOT");
        when(settings.pilotManagerIds()).thenReturn(Set.of(1L, 3L));
        stub(List.of());
        assertThat(service.refresh(10L).diagnosis()).isNull();
        when(settings.pilotManagerIds()).thenReturn(Set.of(1L, 2L));
        assertThat(service.refresh(10L).diagnosis()).isNotNull();
    }

    @Test
    void freeTextClaimsNeverEnterAuthoritativeFactsSection() {
        stub(List.of(new Fact("card:10:comment", "card", "comment", "Я оплатил счет и исправил ошибку"),
                new Fact("invoice:189:status", "invoice", "status", "COLLECTING"),
                new Fact("message:70:recorded_text", "recorded_chat_message", "recorded_text", "Когда счет будет готов?")));
        String result = service.contextForAudit(List.of(10L));
        String authoritative = result.substring(result.lastIndexOf("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:"));
        assertThat(authoritative).doesNotContain("Я оплатил").contains("\"field\":\"status\"", "COLLECTING", "recorded_chat_message");
        assertThat(result).contains("подтверждают только содержание");
    }

    @Test
    void disabledRecommendationsStillProvideFreshAuditFactsWithoutStoreOrQueue() {
        when(settings.mode()).thenReturn("OFF");
        LocalDateTime observed = LocalDateTime.now();
        when(collector.collect(10L)).thenReturn(new ManagerProblemEvidenceCollector.Evidence("2:COMMON_INVOICE:189", 2, 10, false,
                List.of(new Fact("invoice:189:status", "invoice", "status", "COLLECTING")), observed));
        String context = service.contextForAudit(List.of(10L));
        assertThat(context).contains("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:", "\"id\":\"invoice:189:status\"", "\"value\":\"COLLECTING\"", observed.toString());
        assertThat(context).doesNotContain("\"caseId\"", "\"evidenceVersion\"", "Рекомендация ИИ caseId", "актуальные факты недоступны");
        verify(collector).collect(10L);
        verifyNoInteractions(store, events);
    }

    @Test
    void missingOrFailedSourceNeverCountsAsResolved() {
        assertThat(service.resolutionState(10L)).isEqualTo(ResolutionState.UNKNOWN);
        when(collector.collect(10L)).thenThrow(new IllegalStateException("source offline"));
        assertThat(service.resolutionState(10L)).isEqualTo(ResolutionState.UNKNOWN);
        assertThat(service.contextForAudit(List.of(10L))).contains("нельзя считать нарушение доказанным");
    }

    @Test
    void optionalDiagnosticFailureDoesNotBreakCardPresentation() {
        when(store.latestForSource(10L)).thenThrow(new IllegalStateException("corrupt snapshot"));
        assertThat(service.latest(10L)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "RETRY", "UNAVAILABLE", "NEEDS_CONTEXT"})
    void unfinishedAnalysisProvidesUsefulHonestGuidanceWithoutChangingQueueState(String state) {
        when(settings.mode()).thenReturn("ENFORCE");
        var facts = List.of(new Fact("invoice:189:status", "invoice", "status", "COLLECTING"),
                new Fact("invoice:189:orderCount", "invoice", "orderCount", "3"),
                new Fact("invoice:189:readyCount", "invoice", "readyCount", "1"));
        when(store.latestForSource(10L)).thenReturn(new ManagerProblemDiagnosisStore.Stored(1, 2, 1, 2, state,
                LocalDateTime.now(), facts, null, null, PROMPT_VERSION, null, 2));
        DiagnosticView view = service.latest(10L);
        assertThat(view.status()).isEqualTo(state);
        assertThat(view.model()).isEqualTo("server-rules");
        assertThat(view.diagnosis().summary()).contains("1 из 3");
        assertThat(view.diagnosis().actions()).hasSize(1);
        assertThat(view.diagnosis().verification()).hasSize(1);
        assertThat(view.facts()).isEqualTo(facts);
        verifyNoInteractions(collector, events);
        verify(store, never()).capture(any());
    }

    @Test
    void currentTechnicalDiagnosisGetsBusinessPresentationWhileItsStoredEvidenceRemainsUntouched() {
        when(settings.mode()).thenReturn("ENFORCE");
        var facts = List.of(new Fact("invoice:189:status", "invoice", "status", "COLLECTING"));
        var original = new Diagnosis("Проверить invoice_order.ready и follow_up_at", List.of(), List.of(), List.of(),
                List.of(new Action("CHECK_SOURCE", "Сверить данные", "Менеджер", "Проверить результат", List.of("invoice:189:status"))), List.of("Проверить результат"));
        when(store.latestForSource(10L)).thenReturn(new ManagerProblemDiagnosisStore.Stored(1, 2, 1, 2, "NEEDS_CONTEXT",
                LocalDateTime.now(), facts, original, "deepseek-flash", "manager-problem-evidence-v2", null, 1));
        var result = service.latest(10L);
        assertThat(result.diagnosis().summary()).contains("Общий счёт").doesNotContain("invoice_order", "follow_up_at");
        assertThat(original.summary()).contains("invoice_order.ready");
        assertThat(result.status()).isEqualTo("NEEDS_CONTEXT");
        assertThat(result.model()).isEqualTo("server-rules");
    }

    @Test
    void storedInvoiceConflictReplacesCachedCurrentConflictClaimWithHistoricalReadOnlyGuidance() throws Exception {
        when(settings.mode()).thenReturn("ENFORCE");
        var facts = List.of(new Fact("card:10:entity_type", "card", "entity_type", "COMMON_INVOICE"),
                new Fact("invoice:297:last_error", "invoice", "last_error", "Текст отзыва уже опубликован ранее"),
                new Fact("invoice:297:orderCount", "invoice", "orderCount", "7"),
                new Fact("invoice:297:readyCount", "invoice", "readyCount", "4"));
        var original = new Diagnosis("Три заказа задержаны из-за конфликта публикации. Текст отзыва всё ещё повторяется.", List.of(), List.of(), List.of("Нужно сверить отзывы."),
                List.of(new Action("CHECK_SOURCE", "Проверьте публикации.", "Менеджер", "Все позиции готовы.", List.of("invoice:297:last_error"))), List.of("Все позиции готовы."));
        assertThat(ManagerProblemBusinessDiagnosis.readable(original)).isTrue();
        String storedJson = new ObjectMapper().writeValueAsString(original);
        var saved = new ManagerProblemDiagnosisStore.Stored(300, 7, 1, 2, "NEEDS_CONTEXT", LocalDateTime.now(), facts, original,
                "deepseek-flash", PROMPT_VERSION, null, 1);
        when(store.latestForSource(10L)).thenReturn(saved);

        DiagnosticView result = service.latest(10L);

        assertThat(result.model()).isEqualTo("server-rules");
        assertThat(result.status()).isEqualTo("NEEDS_CONTEXT");
        assertThat(result.version()).isEqualTo(7);
        assertThat(result.facts()).isSameAs(facts);
        assertThat(result.diagnosis().summary()).contains("4 из 7", "При предыдущей попытке", "не подтверждает конфликт в текущем тексте")
                .doesNotContain("Три заказа задержаны из-за", "Текст отзыва всё ещё повторяется");
        assertThat(new ObjectMapper().writeValueAsString(saved.diagnosis())).isEqualTo(storedJson);
        assertThat(saved.model()).isEqualTo("deepseek-flash");
        verify(store).latestForSource(10L);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(collector, events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "NEEDS_CONTEXT"})
    void historicalArchiveOverridesUnsupportedDebtClaimWithoutRewritingTheStoredAiResult(String status) throws Exception {
        when(settings.mode()).thenReturn("ENFORCE");
        var facts = List.of(new Fact("card:10:entity_type", "card", "entity_type", "COMMON_INVOICE"),
                new Fact("invoice:175:status", "invoice", "status", "PARTIALLY_PAID"),
                new Fact("invoice:175:closed_at", "invoice", "closed_at", "2026-07-30 21:31:26.321616"),
                new Fact("invoice:175:close_reason", "invoice", "close_reason", "MANUAL_ARCHIVE"),
                new Fact("invoice:175:amount_kopecks", "invoice", "amount_kopecks", "450000"),
                new Fact("invoice:175:paid_kopecks", "invoice", "paid_kopecks", "150000"));
        var original = new Diagnosis("Клиент должен доплатить 3000 рублей по общему счёту.", List.of(), List.of(), List.of(),
                List.of(new Action("CHECK_SOURCE", "Проверьте оставшуюся оплату.", "Менеджер", "Остаток оплачен.", List.of("invoice:175:status"))), List.of("Остаток оплачен."));
        assertThat(ManagerProblemBusinessDiagnosis.readable(original)).isTrue();
        String storedJson = new ObjectMapper().writeValueAsString(original);
        var saved = new ManagerProblemDiagnosisStore.Stored(299, 5, 1, 2, status, LocalDateTime.now(), facts, original,
                "deepseek-flash", PROMPT_VERSION, null, 1);
        when(store.latestForSource(10L)).thenReturn(saved);

        DiagnosticView result = service.latest(10L);

        assertThat(result.model()).isEqualTo("server-rules");
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.version()).isEqualTo(5);
        assertThat(result.facts()).isSameAs(facts);
        assertThat(result.diagnosis().summary()).contains("прежнее ручное архивирование", "не подтверждает действующий долг");
        assertThat(new ObjectMapper().writeValueAsString(saved.diagnosis())).isEqualTo(storedJson);
        assertThat(saved.model()).isEqualTo("deepseek-flash");
        verify(store).latestForSource(10L);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(collector, events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "NEEDS_CONTEXT"})
    void oldChatModelAdviceKeepsAuthorshipAndActionsButHandoffCannotSatisfyClosure(String status) throws Exception {
        when(settings.mode()).thenReturn("ENFORCE");
        var facts = chatFacts();
        var original = modelAdviceWithHandoffClosure();
        String storedJson = new ObjectMapper().writeValueAsString(original);
        var saved = new ManagerProblemDiagnosisStore.Stored(1, 2, 1, 2, status, LocalDateTime.now(), facts,
                original, "deepseek-flash", "manager-problem-evidence-v3", null, 1);
        when(store.latestForSource(10L)).thenReturn(saved);

        DiagnosticView result = service.latest(10L);

        assertThat(result.model()).isEqualTo("deepseek-flash");
        assertThat(result.promptVersion()).isEqualTo("manager-problem-evidence-v3");
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.facts()).isSameAs(facts);
        assertThat(result.diagnosis().summary()).isEqualTo(original.summary());
        assertThat(result.diagnosis().actions()).isSameAs(original.actions());
        assertThat(result.diagnosis().confirmedFacts()).isSameAs(original.confirmedFacts());
        assertThat(result.diagnosis().hypotheses()).isSameAs(original.hypotheses());
        assertThat(result.diagnosis().missingEvidence()).isSameAs(original.missingEvidence());
        assertThat(result.diagnosis().verification()).hasSize(2);
        assertThat(result.diagnosis().verification().getFirst()).contains("после последнего сообщения", "ответ по существу", "штатно подтверждено", "с зафиксированной причиной");
        assertThat(result.diagnosis().verification().getLast()).contains("Передача", "взятие в работу", "обещание", "не разрешают закрыть");
        assertThat(ManagerProblemBusinessDiagnosis.readable(result.diagnosis())).isTrue();
        assertThat(new ObjectMapper().writeValueAsString(saved.diagnosis())).isEqualTo(storedJson);
        verify(store).latestForSource(10L);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(collector, events);
    }

    @Test
    void auditAdviceUsesTheSameChatClosurePolicyWithoutPresentingItAsAnObservedFact() throws Exception {
        when(settings.mode()).thenReturn("ENFORCE");
        var facts = chatFacts();
        var original = modelAdviceWithHandoffClosure();
        var evidence = new ManagerProblemEvidenceCollector.Evidence("2:CLIENT_CHAT_UNANSWERED:189", 2, 10, false, facts, LocalDateTime.now());
        when(collector.collect(10L)).thenReturn(evidence);
        when(store.capture(evidence)).thenReturn(new ManagerProblemDiagnosisStore.Stored(1, 2, 1, 2, "READY", LocalDateTime.now(),
                facts, original, "deepseek-flash", "manager-problem-evidence-v3", null, 1));

        String context = service.contextForAudit(List.of(10L));

        String advice = context.lines().filter(line -> line.startsWith("Рекомендация ИИ caseId=")).findFirst().orElseThrow();
        var json = new ObjectMapper().readTree(advice.substring(advice.indexOf(": ") + 2));
        assertThat(json.path("verification").get(0).asText()).contains("ответ по существу", "штатно подтверждено");
        assertThat(json.path("verification").get(1).asText()).contains("не разрешают закрыть");
        assertThat(json.path("summary").asText()).isEqualTo(original.summary());
        assertThat(json.path("actions")).isEqualTo(new ObjectMapper().valueToTree(original.actions()));
        String authoritative = context.substring(context.lastIndexOf("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:"));
        assertThat(authoritative).contains("\"value\":\"OPEN\"").doesNotContain("ответ по существу", "не разрешают закрыть");
        assertThat(original.verification()).containsExactly("Карточку можно закрыть сразу после передачи обращения.");
        verify(collector).collect(10L);
        verify(store).capture(evidence);
        verifyNoMoreInteractions(collector, store);
        verifyNoInteractions(events);
    }

    @Test
    void nonChatDiagnosisRetainsItsClosurePolicyAndReadingDoesNotWriteAnything() {
        when(settings.mode()).thenReturn("ENFORCE");
        var facts = List.of(new Fact("card:10:entity_type", "card", "entity_type", "ORDER"));
        var original = modelAdviceWithHandoffClosure();
        when(store.latestForSource(10L)).thenReturn(new ManagerProblemDiagnosisStore.Stored(1, 2, 1, 2, "READY", LocalDateTime.now(),
                facts, original, "deepseek-flash", PROMPT_VERSION, null, 1));

        DiagnosticView result = service.latest(10L);

        assertThat(result.diagnosis()).isSameAs(original);
        assertThat(result.model()).isEqualTo("deepseek-flash");
        verify(store).latestForSource(10L);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(collector, events);
    }

    @Test
    void oldPaymentFailureDoesNotRewriteClosureForTheCurrentCycle() {
        when(settings.mode()).thenReturn("ENFORCE");
        var original = modelAdviceWithHandoffClosure();
        var facts = List.of(new Fact("card:10:entity_type", "card", "entity_type", "AUTOMATION_FAILURE"),
                new Fact("automation:41:last_error_code", "automation", "last_error_code", "payment_instruction_failed"),
                new Fact("automation:41:current_cycle", "automation", "current_cycle", "false"));
        when(store.latestForSource(10L)).thenReturn(new ManagerProblemDiagnosisStore.Stored(1, 2, 1, 2, "READY", LocalDateTime.now(),
                facts, original, "deepseek-flash", PROMPT_VERSION, null, 1));
        DiagnosticView result = service.latest(10L);
        assertThat(result.diagnosis()).isSameAs(original);
        assertThat(result.model()).isEqualTo("deepseek-flash");
        verify(store).latestForSource(10L);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(collector, events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "NEEDS_CONTEXT"})
    void paymentPreparationFailureCannotCloseOnHandoffDeadlineOrAnotherFailure(String status) throws Exception {
        when(settings.mode()).thenReturn("ENFORCE");
        var facts = List.of(new Fact("card:10:entity_type", "card", "entity_type", "AUTOMATION_FAILURE"),
                new Fact("automation:41:last_error_code", "automation", "last_error_code", "payment_instruction_failed"),
                new Fact("automation:41:current_cycle", "automation", "current_cycle", "true"));
        var original = new Diagnosis("Не удалось подготовить счёт по заказу.", List.of(), List.of(), List.of(),
                List.of(new Action("ESCALATE", "Передайте проверку администратору.", "Менеджер", "Результат сверки записан в счёте.", List.of("automation:41:last_error_code"))),
                List.of("Либо повторная попытка не удалась и случай передан администратору, либо назначен срок следующей проверки."));
        assertThat(ManagerProblemBusinessDiagnosis.readable(original)).isTrue();
        String storedJson = new ObjectMapper().writeValueAsString(original);
        var saved = new ManagerProblemDiagnosisStore.Stored(1, 2, 1, 2, status, LocalDateTime.now(), facts,
                original, "deepseek-flash", PROMPT_VERSION, null, 1);
        when(store.latestForSource(10L)).thenReturn(saved);

        DiagnosticView result = service.latest(10L);

        assertThat(result.model()).isEqualTo("deepseek-flash");
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.diagnosis().summary()).isEqualTo(original.summary());
        assertThat(result.diagnosis().actions()).isSameAs(original.actions());
        assertThat(result.diagnosis().verification().getFirst()).contains("успешную подготовку", "проверенный результат", "штатно зафиксированную ненужность", "с причиной");
        assertThat(result.diagnosis().verification().getLast()).contains("Передача", "срока", "повторная неудачная", "не разрешают закрыть");
        assertThat(ManagerProblemBusinessDiagnosis.readable(result.diagnosis())).isTrue();
        assertThat(new ObjectMapper().writeValueAsString(saved.diagnosis())).isEqualTo(storedJson);
        verify(store).latestForSource(10L);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(collector, events);
    }

    @Test
    void staleResolvedCardCannotHideLiveInvoiceFailure() {
        when(collector.collect(10L)).thenReturn(new ManagerProblemEvidenceCollector.Evidence("2:COMMON_INVOICE:189", 2, 10, true,
                List.of(new Fact("invoice:189:status", "invoice", "status", "NEEDS_ATTENTION"),
                        new Fact("invoice:189:last_error", "invoice", "last_error", "review_approval_failed")), LocalDateTime.now()));
        assertThat(service.resolutionState(10L)).isEqualTo(ResolutionState.OPEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "NEEDS_CONTEXT"})
    void storedReadinessAdviceKeepsAiAttributionButCannotCloseByDeadline(String status) throws Exception {
        when(settings.mode()).thenReturn("ENFORCE");
        var facts = ManagerProblemInvoiceReadinessPolicyTest.facts();
        var original = ManagerProblemInvoiceReadinessPolicyTest.advice();
        String stored = new ObjectMapper().writeValueAsString(original);
        when(store.latestForSource(10L)).thenReturn(new ManagerProblemDiagnosisStore.Stored(1, 2, 1, 2, status,
                LocalDateTime.now(), facts, original, "deepseek-flash", "manager-problem-evidence-v3", null, 1));
        var result = service.latest(10L);
        assertThat(result.model()).isEqualTo("deepseek-flash");
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.diagnosis().summary()).isEqualTo(original.summary());
        assertThat(result.diagnosis().verification().getLast()).contains("не закрывают");
        assertThat(result.diagnosis().actions()).allSatisfy(a -> assertThat(a.verification()).contains("остаётся открытым"));
        assertThat(new ObjectMapper().writeValueAsString(original)).isEqualTo(stored);
        verify(store).latestForSource(10L);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(collector, events);
    }

    @Test
    void auditAdviceUsesReadinessClosureWithoutTurningThePolicyIntoAuthoritativeFacts() throws Exception {
        when(settings.mode()).thenReturn("ENFORCE");
        var facts = ManagerProblemInvoiceReadinessPolicyTest.facts();
        var original = ManagerProblemInvoiceReadinessPolicyTest.advice();
        var evidence = new ManagerProblemEvidenceCollector.Evidence("2:COMMON_INVOICE:1", 2, 10, false, facts, LocalDateTime.now());
        when(collector.collect(10L)).thenReturn(evidence);
        when(store.capture(evidence)).thenReturn(new ManagerProblemDiagnosisStore.Stored(1, 2, 1, 2, "READY", LocalDateTime.now(),
                facts, original, "deepseek-flash", PROMPT_VERSION, null, 1));
        String context = service.contextForAudit(List.of(10L));
        String advice = context.lines().filter(line -> line.startsWith("Рекомендация ИИ caseId=")).findFirst().orElseThrow();
        var json = new ObjectMapper().readTree(advice.substring(advice.indexOf(": ") + 2));
        assertThat(json.path("verification").get(1).asText()).contains("не закрывают");
        assertThat(json.path("actions").get(1).path("verification").asText()).contains("остаётся открытым");
        assertThat(context.substring(context.lastIndexOf("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:")))
                .contains("COLLECTING").doesNotContain("не закрывают", "остаётся открытым");
        assertThat(original.actions().getLast().verification()).isEqualTo("Согласованный срок ещё не истёк.");
        verifyNoInteractions(events);
    }

    private void stub(List<Fact> facts) {
        var evidence = new ManagerProblemEvidenceCollector.Evidence("2:COMMON_INVOICE:189", 2, 10, false, facts, LocalDateTime.now());
        when(collector.collect(10L)).thenReturn(evidence);
        when(store.capture(evidence)).thenReturn(new ManagerProblemDiagnosisStore.Stored(1, 1, 1, 2, "READY", LocalDateTime.now(), facts,
                new Diagnosis("Совет модели", List.of(), List.of(), List.of(), List.of(), List.of()), "deepseek-flash", PROMPT_VERSION, null, 1));
    }

    private List<Fact> chatFacts() {
        return List.of(new Fact("card:10:entity_type", "card", "entity_type", "CLIENT_CHAT_UNANSWERED"),
                new Fact("chat:189:status", "client_chat", "status", "OPEN"));
    }

    private Diagnosis modelAdviceWithHandoffClosure() {
        return new Diagnosis("Обращение открыто, ответ в доступных данных не найден.",
                List.of(new Finding("Обращение открыто.", List.of("chat:189:status"))), List.of(), List.of("Нужно сверить полную переписку."),
                List.of(new Action("ESCALATE", "Передайте обращение ответственному и проверьте ответ.", "Менеджер", "Исполнитель назначен.", List.of("chat:189:status"))),
                List.of("Карточку можно закрыть сразу после передачи обращения."));
    }
}
