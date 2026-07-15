package com.hunt.otziv.reputationai.infrastructure.search.service;

import com.hunt.otziv.reputationai.infrastructure.search.dto.SearchQuery;
import com.hunt.otziv.reputationai.infrastructure.search.dto.SearchResult;
import java.util.List;

public interface SearchProvider {

    String providerName();

    boolean isAvailable();

    List<SearchResult> search(SearchQuery query);
}
