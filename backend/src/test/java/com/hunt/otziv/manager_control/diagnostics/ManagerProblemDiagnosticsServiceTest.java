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
    void staleResolvedCardCannotHideLiveInvoiceFailure() {
        when(collector.collect(10L)).thenReturn(new ManagerProblemEvidenceCollector.Evidence("2:COMMON_INVOICE:189", 2, 10, true,
                List.of(new Fact("invoice:189:status", "invoice", "status", "NEEDS_ATTENTION"),
                        new Fact("invoice:189:last_error", "invoice", "last_error", "review_approval_failed")), LocalDateTime.now()));
        assertThat(service.resolutionState(10L)).isEqualTo(ResolutionState.OPEN);
    }

    private void stub(List<Fact> facts) {
        var evidence = new ManagerProblemEvidenceCollector.Evidence("2:COMMON_INVOICE:189", 2, 10, false, facts, LocalDateTime.now());
        when(collector.collect(10L)).thenReturn(evidence);
        when(store.capture(evidence)).thenReturn(new ManagerProblemDiagnosisStore.Stored(1, 1, 1, 2, "READY", LocalDateTime.now(), facts,
                new Diagnosis("Совет модели", List.of(), List.of(), List.of(), List.of(), List.of()), "deepseek-flash", PROMPT_VERSION, null, 1));
    }
}
