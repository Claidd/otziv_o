package com.hunt.otziv.reputationai.infrastructure.ai.openai;

import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiContentPackOptionsTest {

    @Test
    void usesSafePresetForKnownProfile() {
        ReputationAiProperties.OpenAi fallback = new ReputationAiProperties.OpenAi();

        OpenAiContentPackOptions options = OpenAiContentPackOptions.fromProfile(fallback, "maximum");

        assertThat(options.profileKey()).isEqualTo("maximum");
        assertThat(options.model()).isEqualTo("gpt-5.5");
        assertThat(options.timeout()).isEqualTo(Duration.ofMinutes(6));
        assertThat(options.maxOutputTokens()).isEqualTo(15000);
        assertThat(options.reasoningEffort()).isEqualTo("medium");
    }

    @Test
    void fallsBackToConfiguredOptionsForUnknownProfile() {
        ReputationAiProperties.OpenAi fallback = new ReputationAiProperties.OpenAi();
        fallback.setModel("gpt-4.1-mini");
        fallback.setTimeout(Duration.ofSeconds(75));
        fallback.setMaxOutputTokens(7000);

        OpenAiContentPackOptions options = OpenAiContentPackOptions.fromProfile(fallback, "custom-text");

        assertThat(options.profileKey()).isEqualTo("configured");
        assertThat(options.model()).isEqualTo("gpt-4.1-mini");
        assertThat(options.timeout()).isEqualTo(Duration.ofSeconds(75));
        assertThat(options.maxOutputTokens()).isEqualTo(7000);
        assertThat(options.reasoningEffort()).isBlank();
    }
}
