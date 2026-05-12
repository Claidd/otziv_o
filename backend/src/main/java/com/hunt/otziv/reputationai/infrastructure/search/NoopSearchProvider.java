package com.hunt.otziv.reputationai.infrastructure.search;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NoopSearchProvider implements SearchProvider {

    @Override
    public String providerName() {
        return "local";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<SearchResult> search(SearchQuery query) {
        return List.of();
    }
}
