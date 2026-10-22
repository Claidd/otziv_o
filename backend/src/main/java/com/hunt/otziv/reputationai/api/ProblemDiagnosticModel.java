package com.hunt.otziv.reputationai.api;

import java.time.Duration;

/** Server-side JSON analysis for already scoped/redacted evidence. Never authorizes mutations or tool execution. */
public interface ProblemDiagnosticModel {
    boolean available();
    Result analyze(Input input);
    record Input(String systemPrompt, String evidenceJson, int maxOutputTokens, Duration timeout, boolean thinkingEnabled) { }
    record Result(String text, String model, int inputTokens, int outputTokens, String errorMessage) { }
}
