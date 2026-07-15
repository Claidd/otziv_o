package com.hunt.otziv.reputationai.infrastructure.ai.service;

import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;

public interface AiProvider {

    AiResponse generate(AiRequest request);

    String providerName();

    default boolean isAvailable() {
        return true;
    }
}
