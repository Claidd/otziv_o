package com.hunt.otziv.worker_activity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProviderRouter;
import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerRiskExplanationQualityServiceTest {
    private final AiProviderRouter router = mock(AiProviderRouter.class);
    private final AiProvider provider = mock(AiProvider.class);
    private final AppSettingService settings = mock(AppSettingService.class);
    private final ReputationAiProperties properties = new ReputationAiProperties();
    private final WorkerRiskIncident incident = new WorkerRiskIncident();
    private WorkerRiskExplanationQualityService service;

    @BeforeEach
    void setUp() {
        service = new WorkerRiskExplanationQualityService(router, properties,
                new ObjectMapper().findAndRegisterModules(), settings,
                mock(OrderRepository.class), mock(WorkerActivityEventRepository.class));
        incident.setId(178L);
        incident.setTitle("Блок аккаунта без попытки войти в него");
        incident.setCreatedAt(LocalDateTime.of(2026, 9, 6, 19, 34));
        when(settings.getBoolean(AppSettingService.WORKER_RISK_EXPLANATION_QUALITY_ENABLED, true)).thenReturn(true);
        when(settings.getInt(AppSettingService.WORKER_RISK_EXPLANATION_AI_TIMEOUT_SECONDS, 20)).thenReturn(20);
        when(router.activeProviderName()).thenReturn("deepseek");
        when(router.activeProviderAvailable()).thenReturn(true);
        when(router.activeProvider()).thenReturn(provider);
    }

    @Test
    void genericAnswerIsRejectedWithoutCallingDeepSeek() {
        var result = service.assess(incident, "большой заказ");
        assertThat(result.quality()).isEqualTo(WorkerRiskExplanationQuality.PARTIAL);
        assertThat(result.assessmentAvailable()).isTrue();
        verify(router, never()).activeProvider();
    }

    @Test
    void assessesExplanationWithThinkingDisabledAndEnoughRoomForJson() throws Exception {
        when(provider.generate(any())).thenReturn(response("""
                {"quality":"LOGICAL","confidence":0.95,"reason":"Пояснение объясняет действие",
                 "contradictions":[],"missingFacts":[],"clarificationQuestion":""}
                """));
        String explanation = "Скопировала логин, пароль, оказался заблокирован, отправила в блок";
        var result = service.assess(incident, explanation);
        assertThat(result.assessmentAvailable()).isTrue();
        assertThat(result.quality()).isEqualTo(WorkerRiskExplanationQuality.LOGICAL);
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(80);
        var request = ArgumentCaptor.forClass(AiRequest.class);
        verify(provider).generate(request.capture());
        assertThat(request.getValue().thinkingEnabled()).isFalse();
        assertThat(request.getValue().maxTokens()).isEqualTo(2048);
        assertThat(request.getValue().jsonObject()).isTrue();
        assertThat(new ObjectMapper().readTree(request.getValue().userPrompt())
                .path("specialistExplanation").asText()).isEqualTo(explanation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]", "{}",
            "{\"quality\":\"LOGICAL\"}",
            "{\"quality\":\"UNKNOWN\",\"confidence\":0.8,\"reason\":\"test\"}",
            "{\"quality\":\"LOGICAL\",\"confidence\":2,\"reason\":\"test\"}",
            "{\"quality\":\"LOGICAL\",\"confidence\":0.8,\"reason\":\"\"}"})
    void malformedOrEmptyAssessmentIsTechnicalFailure(String json) {
        when(provider.generate(any())).thenReturn(response(json));
        var result = service.assess(incident, "Аккаунт был заблокирован");
        assertThat(result.assessmentAvailable()).isFalse();
        assertThat(result.quality()).isEqualTo(WorkerRiskExplanationQuality.NEEDS_REVIEW);
        assertThat(result.clarificationQuestion()).isEmpty();
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(80);
    }

    @Test
    void preservesUsageAndMarksLengthFailureUnavailable() {
        when(provider.generate(any())).thenReturn(new AiResponse("", "deepseek", 500, 700,
                "DeepSeek вернул пустой текст, finish_reason=length."));
        var result = service.assess(incident, "Аккаунт был заблокирован");
        assertThat(result.assessmentAvailable()).isFalse();
        assertThat(result.reason()).contains("finish_reason=length");
        assertThat(result.outputTokens()).isEqualTo(700);
        assertThat(result.model()).isEqualTo(properties.getDeepseek().getModel());
    }

    @Test
    void providerExceptionIsTechnicalFailure() {
        when(provider.generate(any())).thenThrow(new IllegalStateException("unavailable"));
        assertThat(service.assess(incident, "Аккаунт был заблокирован").assessmentAvailable()).isFalse();
    }

    @Test
    void disabledAssessmentIsUnavailableWithoutCallingProvider() {
        when(settings.getBoolean(AppSettingService.WORKER_RISK_EXPLANATION_QUALITY_ENABLED, true)).thenReturn(false);
        assertThat(service.assess(incident, "Аккаунт был заблокирован").assessmentAvailable()).isFalse();
        verify(provider, never()).generate(any());
    }

    @Test
    void unavailableProviderDoesNotAssessExplanation() {
        when(router.activeProviderAvailable()).thenReturn(false);
        assertThat(service.assess(incident, "Аккаунт был заблокирован").assessmentAvailable()).isFalse();
        verify(provider, never()).generate(any());
    }

    @Test
    void validNeedsReviewIsAnAssessmentRatherThanTechnicalFailure() {
        when(provider.generate(any())).thenReturn(response("""
                {"quality":"NEEDS_REVIEW","confidence":0,"reason":"Не хватает фактов"}
                """));
        var result = service.assess(incident, "Аккаунт был заблокирован");
        assertThat(result.assessmentAvailable()).isTrue();
        assertThat(result.quality()).isEqualTo(WorkerRiskExplanationQuality.NEEDS_REVIEW);
    }

    private AiResponse response(String text) {
        return new AiResponse(text, "deepseek", 120, 80);
    }
}
