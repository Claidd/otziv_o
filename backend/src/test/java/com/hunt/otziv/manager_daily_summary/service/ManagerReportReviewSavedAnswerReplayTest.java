package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.service.DeepSeekProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProviderRouter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in diagnostic replay. No application context, database writes or message delivery. */
class ManagerReportReviewSavedAnswerReplayTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_MANAGER_AUDIT_SAVED_REPLAY", matches = "1")
    void verifiesSavedAnswerAgainstItsIntactOriginalEvidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var input = mapper.readTree(Files.readString(Path.of(System.getenv("MANAGER_AUDIT_REPLAY_INPUT"))));
        assertThat(input.path("schema").asText()).isEqualTo("manager-audit-private-replay-v1");
        String answer = input.path("answer").asText();
        ZonedDateTime submittedAt = ZonedDateTime.parse(input.path("submittedAt").asText());
        String answerHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(answer.trim().getBytes(StandardCharsets.UTF_8)));
        String header = "ANSWER_EVIDENCE_V3 questionIndex=" + input.path("questionIndex").asInt()
                + " submittedAt=" + submittedAt.toInstant() + " answerSha256=" + answerHash + "\n";
        String payload = input.path("evidencePayload").asText();
        assertThat(payload.startsWith(header)).as("Snapshot belongs to this answer and submission").isTrue();
        var restored = new ManagerReportReviewEvidenceSnapshotService(mapper).restore(payload.substring(header.length()));
        assertThat(restored.isPresent()).as("Original snapshot integrity").isTrue();
        var question = mapper.treeToValue(input.path("question"), ManagerReportReviewQualityService.ReviewQuestion.class);

        ReputationAiProperties properties = new ReputationAiProperties();
        String key = System.getenv("DEEPSEEK_API_KEY");
        assertThat(key != null && !key.isBlank()).as("Process-only provider credential supplied").isTrue();
        properties.getDeepseek().setApiKey(key);
        properties.getDeepseek().setModel(input.path("model").asText());
        DeepSeekProvider provider = new DeepSeekProvider(properties, mapper);
        List<Map<String, Object>> usage = new ArrayList<>();
        AiProvider measured = new AiProvider() {
            @Override public String providerName() { return provider.providerName(); }
            @Override public boolean isAvailable() { return provider.isAvailable(); }
            @Override public AiResponse generate(AiRequest request) {
                assertThat(usage.size()).as("At most two paid replay calls").isLessThan(2);
                long started = System.nanoTime();
                AiResponse response = provider.generate(request);
                usage.add(Map.of("inputTokens", response.inputTokens(), "outputTokens", response.outputTokens(),
                        "durationMs", (System.nanoTime() - started) / 1_000_000, "maxTokens", request.maxTokens()));
                return response;
            }
        };
        AiProviderRouter router = mock(AiProviderRouter.class);
        when(router.activeProvider()).thenReturn(measured);
        when(router.activeProviderAvailable()).thenReturn(true);
        when(router.activeProviderName()).thenReturn("deepseek");
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(call -> input.path("intSettings")
                .path((String) call.getArgument(0)).asInt(call.getArgument(1)));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> input.path("booleanSettings")
                .path((String) call.getArgument(0)).asBoolean(call.getArgument(1)));
        var quality = new ManagerReportReviewQualityService(router, mapper, settings);
        List<String> previous = new ArrayList<>();
        input.path("previousAttempts").forEach(item -> previous.add(item.asText()));
        // Source timestamps without an offset must be interpreted exactly as on the producing server.
        ZoneId serverZone = ZoneId.of(input.path("serverZone").asText());
        TimeZone priorZone = TimeZone.getDefault();
        ManagerReportReviewQualityService.Assessment result;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(serverZone));
            result = quality.assessAnswer(restored.orElseThrow(), question, answer, false, previous, false, submittedAt);
        } finally {
            TimeZone.setDefault(priorZone);
        }

        // Persist fixed metadata only, never the answer, source facts, raw provider output or credential.
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "manager-audit-saved-replay-result-v1");
        report.put("evaluatedAt", Instant.now().toString());
        report.put("reviewId", input.path("reviewId").asLong());
        report.put("questionIndex", input.path("questionIndex").asInt());
        report.put("model", properties.getDeepseek().getModel());
        report.put("serverZone", serverZone.getId());
        report.put("originalSnapshotVerified", true);
        report.put("productionStateChanged", false);
        report.put("decision", result.decision().name());
        report.put("criteria", result.criteria().stream().map(c -> Map.of("index", c.criterionIndex(), "status", c.status().name())).toList());
        report.put("calls", usage);
        Path destination = Path.of("target", "manager-audit-evaluation", "saved-answer-replay.json");
        Files.createDirectories(destination.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), report);
        System.out.println("audit-saved-replay decision=" + result.decision() + " calls=" + usage.size());
        assertThat(usage).as("Saved answer reached the real provider").hasSizeBetween(1, 2);
        assertThat(result.decision().name()).as("Original answer passes technical verification").isNotEqualTo("UNAVAILABLE");
        if (input.hasNonNull("expectedDecision"))
            assertThat(result.decision().name()).as("Saved incident leaves its incorrect data pause")
                    .isEqualTo(input.path("expectedDecision").asText());
    }
}
