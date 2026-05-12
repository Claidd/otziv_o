package com.hunt.otziv.reputationai.infrastructure.ai;

public interface AiProvider {

    AiResponse generate(AiRequest request);

    String providerName();

    default boolean isAvailable() {
        return true;
    }
}
