package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.service.DeepSeekProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProviderRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in paid release evaluation. Uses production prompts, parser and provider; never accesses production data. */
class ManagerReportReviewLiveEvaluationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_MANAGER_AUDIT_INCIDENT_EVAL", matches = "1")
    void anonymizedCurrentQuestionPlanAndTwoNegativeControls() throws Exception {
        ReputationAiProperties properties = new ReputationAiProperties();
        String key = System.getenv("DEEPSEEK_API_KEY");
        assertThat(key).isNotBlank();
        properties.getDeepseek().setApiKey(key);
        String model = System.getenv("DEEPSEEK_MODEL");
        if (model != null && !model.isBlank()) properties.getDeepseek().setModel(model);
        DeepSeekProvider real = new DeepSeekProvider(properties, mapper);
        List<Map<String, Object>> calls = new ArrayList<>();
        AiProvider measured = new AiProvider() {
            @Override public String providerName() { return real.providerName(); }
            @Override public boolean isAvailable() { return real.isAvailable(); }
            @Override public AiResponse generate(AiRequest request) {
                assertThat(calls.size()).as("At most two calls per case, three authorized cases").isLessThan(6);
                long started = System.nanoTime();
                AiResponse response = real.generate(request);
                calls.add(Map.of("response", response.text(), "error", response.errorMessage(),
                        "inputTokens", response.inputTokens(), "outputTokens", response.outputTokens(),
                        "durationMs", (System.nanoTime() - started) / 1_000_000,
                        "maxTokens", request.maxTokens(), "reasoningEffort", request.reasoningEffort(),
                        "thinking", request.thinkingEnabled(), "timeoutMs", request.timeout().toMillis()));
                return response;
            }
        };
        AiProviderRouter router = mock(AiProviderRouter.class);
        when(router.activeProvider()).thenReturn(measured);
        when(router.activeProviderAvailable()).thenReturn(true);
        when(router.activeProviderName()).thenReturn("deepseek");
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        var service = new ManagerReportReviewQualityService(router, mapper, settings);
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> answers = List.of(ManagerReportReviewIncidentFixture.ANSWER,
                "По этим заказам разберусь с ситуацией и постараюсь помочь, всё будет хорошо.",
                "Я уже проверила эти три заказа: старая отправка по каждому доставлена, клиент получил шаблоны. "
                        + "Зафиксировала успешную доставку в карточках и закрыла вопрос, повторно не отправляла.");
        List<String> ids = List.of("current-three-orders-plan", "generic-non-answer", "false-completed-unknown");
        String filter = System.getenv("MANAGER_AUDIT_INCIDENT_CASES");
        List<String> selected = filter == null || filter.isBlank() ? ids : List.of(filter.split(","));
        assertThat(selected).isNotEmpty().allSatisfy(id -> assertThat(ids).contains(id));
        String evidence = ManagerReportReviewIncidentFixture.evidence();
        for (int index = 0; index < answers.size(); index++) {
            if (!selected.contains(ids.get(index))) continue;
            int before = calls.size();
            var assessment = service.assessAnswer(evidence, ManagerReportReviewIncidentFixture.question(), answers.get(index), false);
            boolean premisesUnderstood = assessment.criteria().stream().filter(c -> c.criterionIndex() < 3).count() == 3
                    && assessment.criteria().stream().filter(c -> c.criterionIndex() < 3)
                    .allMatch(c -> c.status() == ManagerReportReviewQualityService.CriterionStatus.SATISFIED);
            boolean actionOnlyClarification = assessment.decision() == ManagerReportReviewQualityService.Decision.NEEDS_CLARIFICATION
                    && assessment.criteria().stream().anyMatch(c -> c.criterionIndex() >= 3
                    && c.status() == ManagerReportReviewQualityService.CriterionStatus.MISSING)
                    && assessment.criteria().stream().noneMatch(c -> c.status() == ManagerReportReviewQualityService.CriterionStatus.CONTRADICTED)
                    && assessment.claims().stream().noneMatch(c -> c.status() == ManagerReportReviewQualityService.ClaimStatus.CONTRADICTED);
            boolean matches = index == 0 ? premisesUnderstood && (assessment.accepted() || actionOnlyClarification)
                    : index == 1 ? assessment.decision() == ManagerReportReviewQualityService.Decision.NEEDS_CLARIFICATION
                    : !assessment.accepted() && assessment.decision() != ManagerReportReviewQualityService.Decision.UNAVAILABLE
                    && assessment.criteria().stream().noneMatch(c -> c.status() == ManagerReportReviewQualityService.CriterionStatus.CONTRADICTED)
                    && assessment.claims().stream().noneMatch(c -> c.status() == ManagerReportReviewQualityService.ClaimStatus.CONTRADICTED);
            results.add(Map.of("id", ids.get(index), "answer", answers.get(index), "assessment", assessment,
                    "matches", matches, "providerCalls", List.copyOf(calls.subList(before, calls.size()))));
            Path report = Path.of("target", "manager-audit-evaluation", "incident-q1-report.json");
            Files.createDirectories(report.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                    "schema", "manager-audit-incident-evaluation-v1", "evaluatedAt", Instant.now().toString(),
                    "model", properties.getDeepseek().getModel(), "anonymized", true,
                    "question", ManagerReportReviewIncidentFixture.question(), "results", results));
            System.out.println("audit-incident-eval " + ids.get(index) + " " + assessment.decision());
        }
        assertThat(results).hasSize(selected.size());
        assertThat(results).allSatisfy(result -> assertThat(result.get("matches")).as(result.get("id").toString()).isEqualTo(true));
    }

    @Test
    void fixtureHasPositiveNegativeUnknownAndAdversarialLabels() throws Exception {
        JsonNode cases = corpus();
        var ids = new HashSet<String>();
        var labels = new HashSet<String>();
        var categories = new HashSet<String>();
        for (JsonNode sample : cases) {
            assertThat(ids.add(sample.path("id").asText())).as("unique scenario id").isTrue();
            assertThat(sample.path("expectedPoints").isArray()).isTrue();
            assertThat(sample.path("expectedPoints").isEmpty()).isFalse();
            assertThat(sample.path("report").asText()).contains("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:");
            labels.add(sample.path("expectedDecision").asText());
            categories.add(sample.path("category").asText());
        }
        assertThat(labels).containsExactlyInAnyOrder("ACCEPTED", "NEEDS_CLARIFICATION", "NEEDS_CONTEXT");
        assertThat(categories).contains("positive", "contradiction", "insufficient_context", "adversarial", "missing");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_MANAGER_AUDIT_LIVE_EVAL", matches = "1")
    void liveModelMatchesLabelledReleaseScenarios() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertThat(apiKey).as("DEEPSEEK_API_KEY must be provided securely to the evaluation process").isNotBlank();
        ReputationAiProperties properties = new ReputationAiProperties();
        properties.getDeepseek().setApiKey(apiKey);
        String model = System.getenv("DEEPSEEK_MODEL");
        if (model != null && !model.isBlank()) properties.getDeepseek().setModel(model);
        DeepSeekProvider provider = new DeepSeekProvider(properties, mapper);
        List<CallUsage> usage = new ArrayList<>();
        List<Map<String, Object>> syntheticResponses = new ArrayList<>();
        AiProvider measured = new AiProvider() {
            @Override public String providerName() { return provider.providerName(); }
            @Override public boolean isAvailable() { return provider.isAvailable(); }
            @Override public AiResponse generate(AiRequest request) {
                long started = System.nanoTime();
                AiResponse response = provider.generate(request);
                usage.add(new CallUsage(response.inputTokens(), response.outputTokens(),
                        (System.nanoTime() - started) / 1_000_000));
                syntheticResponses.add(Map.of("task", request.task(), "response", response.text(), "error", response.errorMessage()));
                return response;
            }
        };
        AiProviderRouter router = mock(AiProviderRouter.class);
        when(router.activeProvider()).thenReturn(measured);
        when(router.activeProviderAvailable()).thenReturn(true);
        when(router.activeProviderName()).thenReturn("deepseek");
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        ManagerReportReviewQualityService service = new ManagerReportReviewQualityService(router, mapper, settings);
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode sample : corpus()) {
            String filter = System.getenv("MANAGER_AUDIT_EVAL_CASES");
            if (filter != null && !filter.isBlank() && !List.of(filter.split(",")).contains(sample.path("id").asText())) continue;
            var question = new ManagerReportReviewQualityService.ReviewQuestion(
                    sample.path("question").asText(), strings(sample.path("expectedPoints")),
                    List.of(sample.path("sourceTaskId").asLong()));
            int callsBefore = usage.size();
            long started = System.nanoTime();
            var assessment = service.assessAnswer(evidenceReport(sample), question,
                    sample.path("answer").asText(), false, strings(sample.path("previousAttempts")),
                    sample.path("fastPasteRisk").asBoolean(false));
            String expected = sample.path("expectedDecision").asText();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", sample.path("id").asText());
            result.put("category", sample.path("category").asText());
            result.put("expected", expected);
            result.put("actual", assessment.decision().name());
            result.put("matches", expected.equals(assessment.decision().name()));
            result.put("falseAccept", !"ACCEPTED".equals(expected) && assessment.accepted());
            result.put("falseReject", "ACCEPTED".equals(expected)
                    && assessment.decision() == ManagerReportReviewQualityService.Decision.NEEDS_CLARIFICATION);
            result.put("pending", assessment.pending());
            result.put("durationMs", (System.nanoTime() - started) / 1_000_000);
            result.put("providerCalls", usage.size() - callsBefore);
            result.put("criteria", assessment.criteria());
            result.put("claims", assessment.claims());
            result.put("reason", assessment.reason());
            result.put("commitments", assessment.commitments());
            result.put("syntheticProviderResponses", List.copyOf(syntheticResponses.subList(callsBefore, syntheticResponses.size())));
            results.add(result);
            Path progress = Path.of("target", "manager-audit-evaluation", "progress.json");
            Files.createDirectories(progress.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(progress.toFile(), results);
            // Only synthetic id and decision in console; no key, source prompt or API response body.
            System.out.println("audit-eval " + sample.path("id").asText() + " " + assessment.decision());
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "manager-audit-live-evaluation-v1");
        report.put("evaluatedAt", Instant.now().toString());
        report.put("configuredModel", properties.getDeepseek().getModel());
        report.put("protocol", "evidence-v1");
        report.put("corpus", "victoria-regressions.json");
        report.put("total", results.size());
        report.put("matched", results.stream().filter(r -> Boolean.TRUE.equals(r.get("matches"))).count());
        report.put("falseAccepts", results.stream().filter(r -> Boolean.TRUE.equals(r.get("falseAccept"))).count());
        report.put("falseRejects", results.stream().filter(r -> Boolean.TRUE.equals(r.get("falseReject"))).count());
        report.put("pending", results.stream().filter(r -> Boolean.TRUE.equals(r.get("pending"))).count());
        report.put("inputTokens", usage.stream().mapToLong(CallUsage::inputTokens).sum());
        report.put("outputTokens", usage.stream().mapToLong(CallUsage::outputTokens).sum());
        report.put("providerDurationMs", usage.stream().mapToLong(CallUsage::durationMs).sum());
        report.put("results", results);
        Path destination = Path.of("target", "manager-audit-evaluation", "report.json");
        Files.createDirectories(destination.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), report);
        assertThat(results.stream().filter(r -> !Boolean.TRUE.equals(r.get("matches")))
                .map(r -> r.get("id") + ": expected=" + r.get("expected") + ", actual=" + r.get("actual")))
                .as("No mismatch, outage or invalid model schema may silently pass the release gate; see %s", destination)
                .isEmpty();
    }

    private JsonNode corpus() throws Exception {
        try (var stream = getClass().getResourceAsStream("/manager-audit-evaluation/victoria-regressions.json")) {
            assertThat(stream).isNotNull();
            return mapper.readTree(stream).path("cases");
        }
    }

    private String evidenceReport(JsonNode sample) throws Exception {
        String marker = "СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:";
        StringBuilder report = new StringBuilder(marker).append('\n');
        long sourceTaskId = sample.path("sourceTaskId").asLong();
        String observedAt = OffsetDateTime.now().minusHours(sample.path("snapshotAgeHours").asLong(0)).toString();
        int observation = 0;
        // Encode labelled observations using the same source/version envelope as production facts.
        // The fixture's narrative represents a synthetic authoritative event, not an AI diagnosis.
        for (String line : sample.path("report").asText().substring(marker.length()).split("\\n")) {
            if (!line.isBlank()) report.append(mapper.writeValueAsString(Map.of(
                    "id", "observation:" + sourceTaskId + ":" + observation++, "source", "server_observation",
                    "field", "event", "value", line, "sourceTaskId", sourceTaskId,
                    "observedAt", observedAt, "caseId", sourceTaskId, "evidenceVersion", 1))).append('\n');
        }
        for (JsonNode fact : sample.path("facts")) {
            var envelope = fact.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) envelope)
                    .put("sourceTaskId", sourceTaskId).put("observedAt", observedAt)
                    .put("caseId", sourceTaskId).put("evidenceVersion", 1);
            report.append(mapper.writeValueAsString(envelope)).append('\n');
        }
        return report.toString();
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private record CallUsage(long inputTokens, long outputTokens, long durationMs) {}
}
