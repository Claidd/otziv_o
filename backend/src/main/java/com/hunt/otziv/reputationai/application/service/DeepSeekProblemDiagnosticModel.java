package com.hunt.otziv.reputationai.application.service;

import com.hunt.otziv.reputationai.api.ProblemDiagnosticModel;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.service.DeepSeekProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeepSeekProblemDiagnosticModel implements ProblemDiagnosticModel {
    private final DeepSeekProvider deepSeek;
    private final ReputationAiProperties properties;
    @Override public boolean available() { return deepSeek.isAvailable(); }
    @Override public Result analyze(Input input) {
        AiResponse response = deepSeek.generate(new AiRequest("manager-problem-diagnosis", input.systemPrompt(), input.evidenceJson(),
                // Keep thinking enabled while reserving the bounded output budget for the diagnosis JSON.
                0.1, true, input.maxOutputTokens(), input.timeout(), input.thinkingEnabled(), "low"));
        return new Result(response.text(), properties.getDeepseek().getModel(), response.inputTokens(), response.outputTokens(), response.errorMessage());
    }
}
