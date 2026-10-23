package com.hunt.otziv.reputationai.application.service;

import com.hunt.otziv.reputationai.api.ProblemDiagnosticModel;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.service.DeepSeekProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeepSeekProblemDiagnosticModelTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void boundsDiagnosticEffortWithoutChangingBudgetTimeoutThinkingOrGlobalDefaults(boolean thinking) {
        DeepSeekProvider provider = mock(DeepSeekProvider.class);
        ReputationAiProperties properties = new ReputationAiProperties();
        properties.getDeepseek().setReasoningEffort("high");
        when(provider.generate(any())).thenReturn(new AiResponse("{}", "deepseek", 17, 23));
        var model = new DeepSeekProblemDiagnosticModel(provider, properties);

        var result = model.analyze(new ProblemDiagnosticModel.Input(
                "Return JSON", "{\"facts\":[]}", 8000, Duration.ofSeconds(45), thinking));

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(provider).generate(captor.capture());
        AiRequest request = captor.getValue();
        assertThat(request.task()).isEqualTo("manager-problem-diagnosis");
        assertThat(request.reasoningEffort()).isEqualTo("low");
        assertThat(request.thinkingEnabled()).isEqualTo(thinking);
        assertThat(request.maxTokens()).isEqualTo(8000);
        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(request.jsonObject()).isTrue();
        assertThat(properties.getDeepseek().getReasoningEffort()).isEqualTo("high");
        assertThat(result.text()).isEqualTo("{}");
        assertThat(result.inputTokens()).isEqualTo(17);
        assertThat(result.outputTokens()).isEqualTo(23);
    }
}
