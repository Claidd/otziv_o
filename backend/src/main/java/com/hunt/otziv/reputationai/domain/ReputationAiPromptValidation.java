package com.hunt.otziv.reputationai.domain;

import java.util.List;

public record ReputationAiPromptValidation(
        String key,
        boolean valid,
        List<String> missingPlaceholders,
        List<String> warnings
) {
}
