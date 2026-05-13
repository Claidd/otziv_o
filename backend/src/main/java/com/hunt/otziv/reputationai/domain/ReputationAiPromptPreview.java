package com.hunt.otziv.reputationai.domain;

import java.util.List;

public record ReputationAiPromptPreview(
        String key,
        String sampleName,
        String renderedContent,
        List<String> replacedPlaceholders,
        List<String> unresolvedPlaceholders,
        List<String> warnings
) {
}
