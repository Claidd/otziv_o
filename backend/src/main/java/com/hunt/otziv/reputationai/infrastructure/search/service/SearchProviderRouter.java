package com.hunt.otziv.reputationai.infrastructure.search.service;

import com.hunt.otziv.reputationai.application.service.ReputationAiRuntimeSwitch;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.search.dto.SearchQuery;
import com.hunt.otziv.reputationai.infrastructure.search.dto.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SearchProviderRouter {

    private final ReputationAiProperties properties;
    private final ReputationAiRuntimeSwitch runtimeSwitch;
    private final List<SearchProvider> providers;

    public List<SearchResult> search(SearchQuery query) {
        if (!runtimeSwitch.isEnabled()) {
            return List.of();
        }
        SearchProvider provider = resolveProvider();
        if (!provider.isAvailable()) {
            return List.of();
        }
        // Re-read at the outbound call boundary so an operator flip after
        // provider resolution still prevents a new external request.
        if (!runtimeSwitch.isEnabled()) {
            return List.of();
        }
        return provider.search(query);
    }

    public String activeProviderName() {
        return resolveProvider().providerName();
    }

    public boolean activeProviderAvailable() {
        return runtimeSwitch.isEnabled() && resolveProvider().isAvailable();
    }

    private SearchProvider resolveProvider() {
        String selected = properties.getSearch().getProvider().toLowerCase(Locale.ROOT);
        return providers.stream()
                .filter(provider -> provider.providerName().equalsIgnoreCase(selected))
                .findFirst()
                .orElseGet(() -> providers.stream()
                        .filter(provider -> provider.providerName().equalsIgnoreCase("local"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No local search provider configured")));
    }
}
