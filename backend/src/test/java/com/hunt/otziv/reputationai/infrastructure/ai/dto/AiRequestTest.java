package com.hunt.otziv.reputationai.infrastructure.ai.dto;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiRequestTest {
    @Test
    void legacyConstructorsKeepGlobalEffortFallback() {
        var requests = List.of(
                new AiRequest("t", "s", "u", 0.1),
                new AiRequest("t", "s", "u", 0.1, true),
                new AiRequest("t", "s", "u", 0.1, true, 8000, Duration.ofSeconds(45)),
                new AiRequest("t", "s", "u", 0.1, true, 8000, Duration.ofSeconds(45), true));
        assertThat(requests).allSatisfy(request -> assertThat(request.reasoningEffort()).isNull());
        assertThat(requests.getLast().thinkingEnabled()).isTrue();
        assertThat(requests.getLast().timeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @ParameterizedTest
    @ValueSource(strings = {"low", "high", "max"})
    void allowsOnlySupportedEffortLevels(String effort) {
        assertThat(new AiRequest("t", "s", "u", 0.1, true, 8000,
                Duration.ofSeconds(45), true, effort).reasoningEffort()).isEqualTo(effort);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "medium", "ultra", "disabled"})
    void rejectsInvalidEffortBeforeAnyProviderRequest(String effort) {
        assertThatThrownBy(() -> new AiRequest("t", "s", "u", 0.1, true, 8000,
                Duration.ofSeconds(45), true, effort)).isInstanceOf(IllegalArgumentException.class);
    }
}
