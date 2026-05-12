package com.hunt.otziv.reputationai.infrastructure.search;

import java.util.List;

public interface SearchProvider {

    String providerName();

    boolean isAvailable();

    List<SearchResult> search(SearchQuery query);
}
