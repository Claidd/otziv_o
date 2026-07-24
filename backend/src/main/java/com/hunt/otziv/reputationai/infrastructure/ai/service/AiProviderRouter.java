package com.hunt.otziv.reputationai.infrastructure.ai.service;

import com.hunt.otziv.reputationai.application.ReputationAiProviderSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiProviderRouter {

    private final ReputationAiProviderSelectionService providerSelectionService;
    private final List<AiProvider> providers;

    public AiProvider activeProvider() {
        String selected = providerSelectionService.activeProvider();
        return providers.stream()
                .filter(provider -> provider.providerName().equalsIgnoreCase(selected))
                .findFirst()
                .orElseGet(() -> providers.stream()
                        .filter(provider -> provider.providerName().equalsIgnoreCase("local"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No local AI provider configured")));
    }

    public boolean activeProviderAvailable() {
        return activeProvider().isAvailable();
    }

    public String activeProviderName() {
        return activeProvider().providerName();
    }
}
