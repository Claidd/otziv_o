package com.hunt.otziv.reputationai.infrastructure.search;

import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SearchProviderRouter {

    private final ReputationAiProperties properties;
    private final List<SearchProvider> providers;

    public List<SearchResult> search(SearchQuery query) {
        SearchProvider provider = resolveProvider();
        if (!provider.isAvailable()) {
            return List.of();
        }

        return provider.search(query);
    }

    public String activeProviderName() {
        return resolveProvider().providerName();
    }

    public boolean activeProviderAvailable() {
        return resolveProvider().isAvailable();
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
