package com.hunt.otziv.manager_control.diagnostics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.api.ManagerProblemDiagnosticSettings;
import com.hunt.otziv.reputationai.api.ProblemDiagnosticModel;
import com.hunt.otziv.reputationai.application.service.DeepSeekProblemDiagnosticModel;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.service.DeepSeekProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.*;

/** One explicitly enabled paid evaluation. The fixture contains only allowlisted source metadata; the store is a mock. */
class ManagerProblemArchiveLiveEvaluationTest {
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "card", Set.of("entity_type", "control_date", "item_status"),
            "invoice", Set.of("invoice_id", "status", "amount_kopecks", "paid_kopecks", "account_enabled", "created_at",
                    "closed_at", "close_reason", "previous_status", "orderCount", "readyCount", "archivedActiveCount"),
            "invoice_order", Set.of("order_id", "ready", "paid", "amount_kopecks", "active_membership", "paid_at",
                    "current_order_status", "current_status_changed_at", "archive_source_order_status_title", "payment_method", "has_source_payment_link"),
            "order_status_history", Set.of("event_id", "created_at", "order_id", "source", "action", "previous_status", "next_status"),
            "invoice_status_history", Set.of("event_id", "created_at", "source", "action", "previous_status", "next_status"),
            "evidence_scope", Set.of("invoice_history_scope"),
            "coverage", Set.of("evaluation_scope"));

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_MANAGER_ARCHIVE_LIVE_EVAL", matches = "1")
    void archiveHistoryIsAnalyzedOnceByTheProductionWorkerBeforeAnyReadSideOverride() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assertThat(key).as("Supply the key only through the evaluation process environment").isNotBlank();
        Path fixturePath = Path.of(System.getenv("MANAGER_ARCHIVE_EVIDENCE_FILE"));
        byte[] fixture = Files.readAllBytes(fixturePath);
        String fixtureHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture));
        assertThat(fixtureHash).isEqualTo(System.getenv("MANAGER_ARCHIVE_EVIDENCE_SHA256"));
        ObjectMapper mapper = new ObjectMapper();
        var input = mapper.readTree(fixture);
        assertThat(input.path("schema").asText()).isEqualTo("archive-invoice-live-evidence-v1");
        List<Fact> facts = mapper.convertValue(input.path("facts"), new TypeReference<>() { });
        assertThat(facts).hasSizeBetween(20, 400).allSatisfy(fact -> {
            assertThat(ALLOWED.getOrDefault(fact.subject(), Set.of())).contains(fact.field());
            assertThat(fact.value()).hasSizeLessThanOrEqualTo(1000).doesNotContain("http://", "https://", "sk-", "Bearer ");
        });
        assertThat(ManagerProblemBusinessDiagnosis.hasHistoricalInvoiceArchive(facts)).isTrue();
        assertThat(ManagerProblemBusinessDiagnosis.uncertainDelivery(facts)).isEmpty();
        // Apply the same deterministic derivation as production to the unchanged, allowlisted source snapshot.
        facts = new ArrayList<>(facts);
        ManagerProblemEvidenceCollector.appendInvoiceMonetaryDisplay(facts);
        ManagerProblemEvidenceCollector.appendInvoiceArchiveChronology(facts);
        assertThat(facts).anyMatch(f -> f.subject().equals("invoice_money") && f.value().contains("1500 ₽") && f.value().contains("4500 ₽"));
        assertThat(facts).anyMatch(f -> f.field().equals("open_status_with_historical_archive") && f.value().equals("true"));
        assertThat(facts).anyMatch(f -> f.field().equals("payment_after_invoice_archive") && f.value().contains("оплата позже архива: true"));

        var properties = new ReputationAiProperties();
        properties.getDeepseek().setApiKey(key);
        properties.getDeepseek().setModel("deepseek-flash");
        var model = spy(new DeepSeekProblemDiagnosticModel(new DeepSeekProvider(properties, mapper), properties));
        List<Map<String, Object>> calls = new ArrayList<>();
        doAnswer(call -> {
            ProblemDiagnosticModel.Input request = call.getArgument(0);
            ProblemDiagnosticModel.Result response = (ProblemDiagnosticModel.Result) call.callRealMethod();
            boolean complete = !response.text().isBlank() && response.errorMessage().isBlank();
            calls.add(Map.of("model", response.model(), "inputTokens", response.inputTokens(), "outputTokens", response.outputTokens(),
                    "maxOutputTokens", request.maxOutputTokens(), "complete", complete,
                    "failureMetadata", complete ? "" : ManagerProblemDiagnosisWorker.modelFailure(response),
                    "requestSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.evidenceJson().getBytes(StandardCharsets.UTF_8))),
                    "systemPromptSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.systemPrompt().getBytes(StandardCharsets.UTF_8)))));
            return response;
        }).when(model).analyze(any());
        var settings = mock(ManagerProblemDiagnosticSettings.class);
        when(settings.maxRequestsPerHour()).thenReturn(1); // No second provider call, even for a schema retry.
        when(settings.maxOutputTokens()).thenReturn(8000);
        when(settings.timeoutSeconds()).thenReturn(45);
        when(settings.thinkingEnabled()).thenReturn(true);
        var diagnostics = mock(ManagerProblemDiagnosticsService.class);
        when(diagnostics.mode()).thenReturn("SHADOW");
        var store = mock(ManagerProblemDiagnosisStore.class);
        var metrics = new SimpleMeterRegistry();
        var job = new ManagerProblemDiagnosisStore.Stored(input.path("caseId").asLong(), 0, 1, 2, "RUNNING",
                LocalDateTime.parse(input.path("observedAt").asText()), facts, null, null, PROMPT_VERSION, "isolated-evaluation-only", 1);
        when(store.claim(anyBoolean(), anyLong())).thenReturn(job);
        List<Diagnosis> completed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        doAnswer(call -> { completed.add(call.getArgument(1)); return true; })
                .when(store).complete(any(), any(), anyString(), anyInt(), anyInt());
        doAnswer(call -> { failures.add(call.getArgument(1)); return null; }).when(store).retry(any(), anyString());
        var worker = new ManagerProblemDiagnosisWorker(diagnostics, mock(ManagerProblemEvidenceCollector.class), store,
                new ManagerProblemDiagnosisValidator(mapper), model, settings, mapper, metrics);

        worker.processNext();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "manager-archive-source-live-evaluation-v1");
        report.put("evaluatedAt", Instant.now().toString());
        report.put("sourceObservedUtc", input.path("utcObservedAt").asText());
        report.put("fixtureSha256", fixtureHash);
        report.put("factCount", facts.size());
        report.put("promptVersion", PROMPT_VERSION);
        report.put("providerCalls", calls);
        report.put("validated", completed.size() == 1 && failures.isEmpty());
        report.put("rawValidatedBeforeReadSideOverride", completed.isEmpty() ? null : completed.getFirst());
        report.put("failures", failures);
        report.put("schemaRetries", metrics.counter("manager.problem.diagnostics.schema_retries").count());
        report.put("productionMutations", false);
        report.put("humanReviewRequired", true);
        report.put("humanReviewRubric", List.of(
                "Recognize the earlier invoice archive and later independent order restorations/payment; do not invent why a person restored them.",
                "Preserve the recorded 1500 payment; a calculated 3000 remainder of historical composition is not established collectible debt.",
                "Recommend concrete source reconciliation, with no new invoice/send/publication, automatic cancellation, write-off or refund.",
                "Closing requires verified source correction and retained payment evidence, not a handoff, promise or deadline."));
        Path reportPath = Path.of("target", "manager-diagnosis-evaluation", "archive175-report.json");
        Files.createDirectories(reportPath.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);

        assertThat(calls).as("At most one paid call; report: %s", reportPath).hasSize(1);
        assertThat(calls.getFirst().get("complete")).isEqualTo(true);
        assertThat(((Number) calls.getFirst().get("outputTokens")).intValue()).isBetween(1, 8000);
        assertThat(failures).isEmpty();
        assertThat(completed).hasSize(1);
        assertThat(ManagerProblemBusinessDiagnosis.readable(completed.getFirst())).isTrue();
        verify(model, times(1)).analyze(any());
        verify(diagnostics, never()).refresh(any());
        verify(store, never()).capture(any());
    }
}
