package com.hunt.otziv.reputationai.infrastructure.search.service;

import com.hunt.otziv.reputationai.infrastructure.search.dto.SearchQuery;
import com.hunt.otziv.reputationai.infrastructure.search.dto.SearchResult;
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
