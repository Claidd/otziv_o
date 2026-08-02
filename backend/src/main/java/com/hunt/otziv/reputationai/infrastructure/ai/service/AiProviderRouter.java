package com.hunt.otziv.reputationai.infrastructure.ai.service;

import com.hunt.otziv.reputationai.application.service.ReputationAiProviderSelectionService;
import com.hunt.otziv.reputationai.application.service.ReputationAiRuntimeSwitch;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiProviderRouter {

    private final ReputationAiProviderSelectionService providerSelectionService;
    private final ReputationAiRuntimeSwitch runtimeSwitch;
    private final List<AiProvider> providers;

    public AiProvider activeProvider() {
        if (!runtimeSwitch.isEnabled()) {
            return disabledProvider("disabled");
        }
        return guardedProvider(resolveProvider());
    }

    private AiProvider resolveProvider() {
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
        return runtimeSwitch.isEnabled() && resolveProvider().isAvailable();
    }

    public String activeProviderName() {
        return resolveProvider().providerName();
    }

    private AiProvider guardedProvider(AiProvider selected) {
        return new AiProvider() {
            @Override
            public AiResponse generate(AiRequest request) {
                if (runtimeSwitch.isEnabled()) {
                    return selected.generate(request);
                }
                return new AiResponse(
                        "",
                        selected.providerName(),
                        0,
                        0,
                        "AI временно отключён оператором"
                );
            }

            @Override
            public String providerName() {
                return selected.providerName();
            }

            @Override
            public boolean isAvailable() {
                return runtimeSwitch.isEnabled() && selected.isAvailable();
            }
        };
    }

    private AiProvider disabledProvider(String providerName) {
        return new AiProvider() {
            @Override
            public AiResponse generate(AiRequest request) {
                return new AiResponse(
                        "",
                        providerName,
                        0,
                        0,
                        "AI временно отключён оператором"
                );
            }

            @Override
            public String providerName() {
                return providerName;
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }
}
