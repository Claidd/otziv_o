package com.hunt.otziv.reputationai.infrastructure.ai.openai;

import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiResearchReportOptionsTest {

    @Test
    void usesSmallerPresetForEconomyProfile() {
        ReputationAiProperties.OpenAi.ResearchReport fallback = new ReputationAiProperties.OpenAi.ResearchReport();

        OpenAiResearchReportOptions options = OpenAiResearchReportOptions.fromProfile(fallback, "economy");

        assertThat(options.profileKey()).isEqualTo("economy");
        assertThat(options.model()).isEqualTo("gpt-5.4-mini");
        assertThat(options.timeout()).isEqualTo(Duration.ofMinutes(8));
        assertThat(options.maxToolCalls()).isEqualTo(6);
        assertThat(options.maxOutputTokens()).isEqualTo(4500);
        assertThat(options.reasoningEffort()).isEqualTo("low");
        assertThat(options.searchContextSize()).isEqualTo("low");
    }

    @Test
    void usesSafePresetForKnownProfile() {
        ReputationAiProperties.OpenAi.ResearchReport fallback = new ReputationAiProperties.OpenAi.ResearchReport();

        OpenAiResearchReportOptions options = OpenAiResearchReportOptions.fromProfile(fallback, "maximum");

        assertThat(options.profileKey()).isEqualTo("maximum");
        assertThat(options.model()).isEqualTo("gpt-5.5");
        assertThat(options.timeout()).isEqualTo(Duration.ofMinutes(12));
        assertThat(options.maxToolCalls()).isEqualTo(48);
        assertThat(options.maxOutputTokens()).isEqualTo(16000);
        assertThat(options.reasoningEffort()).isEqualTo("medium");
        assertThat(options.searchContextSize()).isEqualTo("medium");
    }

    @Test
    void fallsBackToConfiguredOptionsForUnknownProfile() {
        ReputationAiProperties.OpenAi.ResearchReport fallback = new ReputationAiProperties.OpenAi.ResearchReport();
        fallback.setModel("gpt-5.4-mini");
        fallback.setTimeout(Duration.ofMinutes(7));
        fallback.setMaxToolCalls(12);
        fallback.setMaxOutputTokens(9500);
        fallback.setReasoningEffort("low");
        fallback.setSearchContextSize("low");

        OpenAiResearchReportOptions options = OpenAiResearchReportOptions.fromProfile(fallback, "custom-text");

        assertThat(options.profileKey()).isEqualTo("configured");
        assertThat(options.model()).isEqualTo("gpt-5.4-mini");
        assertThat(options.timeout()).isEqualTo(Duration.ofMinutes(7));
        assertThat(options.maxToolCalls()).isEqualTo(12);
        assertThat(options.maxOutputTokens()).isEqualTo(9500);
        assertThat(options.reasoningEffort()).isEqualTo("low");
        assertThat(options.searchContextSize()).isEqualTo("low");
    }
}
