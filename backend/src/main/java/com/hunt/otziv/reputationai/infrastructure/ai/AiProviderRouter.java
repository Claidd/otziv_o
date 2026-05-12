package com.hunt.otziv.reputationai.infrastructure.ai;

import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AiProviderRouter {

    private final ReputationAiProperties properties;
    private final List<AiProvider> providers;

    public AiProvider activeProvider() {
        String selected = properties.getProvider().toLowerCase(Locale.ROOT);
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
