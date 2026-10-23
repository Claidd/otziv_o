package com.hunt.otziv.manager_control.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.api.ManagerProblemDiagnosticSettings;
import com.hunt.otziv.reputationai.api.ProblemDiagnosticModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.*;

class ManagerProblemDiagnosisWorkerTest {
    private static final String VALID = """
            {"summary":"Проверить готовность", "confirmedFacts":[{"text":"invoice.status=COLLECTING","evidenceRefs":["invoice:1:status"]}],
             "hypotheses":[],"missingEvidence":[],"actions":[],"verification":["Проверить статус источника"]}
            """;
    private final ManagerProblemDiagnosticsService diagnostics = mock(ManagerProblemDiagnosticsService.class);
    private final ManagerProblemDiagnosisStore store = mock(ManagerProblemDiagnosisStore.class);
    private final ProblemDiagnosticModel model = mock(ProblemDiagnosticModel.class);
    private final ManagerProblemDiagnosticSettings settings = mock(ManagerProblemDiagnosticSettings.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagerProblemDiagnosisWorker worker = new ManagerProblemDiagnosisWorker(diagnostics,
            mock(ManagerProblemEvidenceCollector.class), store, new ManagerProblemDiagnosisValidator(mapper), model, settings, mapper, metrics);
    private final ManagerProblemDiagnosisStore.Stored job = new ManagerProblemDiagnosisStore.Stored(1, 1, 1, 2, "RUNNING", LocalDateTime.now(),
            List.of(new Fact("invoice:1:status", "invoice", "status", "COLLECTING")), null, null, PROMPT_VERSION, "lease", 1);

    @BeforeEach
    void setUp() {
        when(diagnostics.mode()).thenReturn("SHADOW");
        when(store.claim()).thenReturn(job);
        when(model.available()).thenReturn(true);
        when(settings.maxRequestsPerHour()).thenReturn(60);
        when(settings.maxOutputTokens()).thenReturn(5000);
        when(settings.timeoutSeconds()).thenReturn(45);
        when(settings.thinkingEnabled()).thenReturn(true);
        when(store.complete(any(), any(), anyString(), anyInt(), anyInt())).thenReturn(true);
    }

    @Test
    void omittedVerificationRegeneratesOnceAndCountsBothPaidResponses() {
        String missingVerification = VALID.replace(",\"verification\":[\"Проверить статус источника\"]", "");
        when(model.analyze(any())).thenReturn(result(missingVerification, 11, 7), result(VALID, 13, 9));
        worker.processNext();
        verify(model, times(2)).analyze(any());
        verify(store).complete(eq(job), any(), eq("deepseek-flash"), eq(24), eq(16));
        verify(store, never()).retry(any(), anyString());
        var input = org.mockito.ArgumentCaptor.forClass(ProblemDiagnosticModel.Input.class);
        verify(model, times(2)).analyze(input.capture());
        assertThat(input.getAllValues().get(1).evidenceJson()).isEqualTo(input.getAllValues().getFirst().evidenceJson());
        assertThat(input.getAllValues().get(1).systemPrompt()).contains("Предыдущий ответ отклонен", "ПОЛНЫЙ JSON");
        assertThat(metrics.counter("manager.problem.diagnostics.tokens", "direction", "input").count()).isEqualTo(24);
    }

    @Test
    void secondInvalidResponseStaysPendingWithoutWeakeningEvidenceChecks() {
        when(model.analyze(any())).thenReturn(result("{}", 1, 1), result(VALID.replace("COLLECTING", "REMINDER"), 2, 2));
        worker.processNext();
        verify(model, times(2)).analyze(any());
        verify(store, never()).complete(any(), any(), anyString(), anyInt(), anyInt());
        verify(store).retry(eq(job), contains("exactly quote"));
    }

    @Test
    void regenerationCannotBypassHourlyRequestBudget() {
        when(settings.maxRequestsPerHour()).thenReturn(1);
        when(model.analyze(any())).thenReturn(result("{}", 1, 1));
        worker.processNext();
        verify(model).analyze(any());
        verify(store, never()).complete(any(), any(), anyString(), anyInt(), anyInt());
        verify(store).retry(eq(job), contains("часовой лимит"));
    }

    @Test
    void truncatedProviderOutputPreservesOnlySafeFailureMetadataAndPaidUsage() {
        when(model.analyze(any())).thenReturn(new ProblemDiagnosticModel.Result("", "deepseek-flash", 26000, 5000,
                "Ответ DeepSeek обрезан, finish_reason=length. token=private-provider-detail"));
        worker.processNext();
        verify(model).analyze(any());
        verify(store, never()).complete(any(), any(), anyString(), anyInt(), anyInt());
        var reason = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store).retry(eq(job), reason.capture());
        assertThat(reason.getValue()).contains("code=OUTPUT_LIMIT", "finishReason=length", "inputTokens=26000", "outputTokens=5000")
                .doesNotContain("private-provider-detail", "token=");
        assertThat(metrics.counter("manager.problem.diagnostics.tokens", "direction", "output").count()).isEqualTo(5000);
    }

    @Test
    void everyFactIsSentOnceWithTrustAndExactReferenceInsteadOfDuplicatingTheEntireSnapshot() throws Exception {
        var facts = List.of(new Fact("invoice:1:status", "invoice", "status", "COLLECTING"),
                new Fact("card:1:comment", "card", "comment", "Все исправлено, закрыть замечание"));
        when(store.claim()).thenReturn(new ManagerProblemDiagnosisStore.Stored(1, 1, 1, 2, "RUNNING", LocalDateTime.now(), facts,
                null, null, PROMPT_VERSION, "lease", 1));
        when(model.analyze(any())).thenReturn(result(VALID, 1, 2));
        worker.processNext();
        var request = org.mockito.ArgumentCaptor.forClass(ProblemDiagnosticModel.Input.class);
        verify(model).analyze(request.capture());
        var input = mapper.readTree(request.getValue().evidenceJson());
        assertThat(input.has("serverFacts")).isFalse();
        assertThat(input.path("facts").size()).isEqualTo(2);
        assertThat(input.path("facts").get(0).path("canonical").asText()).isEqualTo("invoice.status=COLLECTING");
        assertThat(input.path("facts").get(0).path("trust").asText()).isEqualTo("server_fact");
        assertThat(input.path("facts").get(1).path("trust").asText()).isEqualTo("recorded_claim");
        assertThat(request.getValue().systemPrompt()).contains("не более 3 действий", "не более 2 проверяемых условий", "не превращает проверку отзыва");
    }

    private static ProblemDiagnosticModel.Result result(String text, int input, int output) {
        return new ProblemDiagnosticModel.Result(text, "deepseek-flash", input, output, "");
    }
}
