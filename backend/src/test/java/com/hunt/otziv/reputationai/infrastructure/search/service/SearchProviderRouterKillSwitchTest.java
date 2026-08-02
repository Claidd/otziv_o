package com.hunt.otziv.reputationai.infrastructure.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.reputationai.application.service.ReputationAiRuntimeSwitch;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.search.dto.SearchQuery;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchProviderRouterKillSwitchTest {

    @Test
    void disabledSwitchReturnsNoResultsWithoutCallingExternalProvider() {
        ReputationAiProperties properties = new ReputationAiProperties();
        properties.getSearch().setProvider("yandex");
        ReputationAiRuntimeSwitch runtimeSwitch = mock(ReputationAiRuntimeSwitch.class);
        SearchProvider provider = mock(SearchProvider.class);
        when(runtimeSwitch.isEnabled()).thenReturn(false);
        when(provider.providerName()).thenReturn("yandex");
        when(provider.isAvailable()).thenReturn(true);
        SearchProviderRouter router = new SearchProviderRouter(properties, runtimeSwitch, List.of(provider));
        SearchQuery query = new SearchQuery("company", 5);

        assertThat(router.activeProviderName()).isEqualTo("yandex");
        assertThat(router.activeProviderAvailable()).isFalse();
        assertThat(router.search(query)).isEmpty();
        verify(provider, never()).search(query);
    }

    @Test
    void switchFlipAtOutboundBoundaryPreventsSearchCall() {
        ReputationAiProperties properties = new ReputationAiProperties();
        properties.getSearch().setProvider("yandex");
        ReputationAiRuntimeSwitch runtimeSwitch = mock(ReputationAiRuntimeSwitch.class);
        SearchProvider provider = mock(SearchProvider.class);
        when(runtimeSwitch.isEnabled()).thenReturn(true, false);
        when(provider.providerName()).thenReturn("yandex");
        when(provider.isAvailable()).thenReturn(true);
        SearchProviderRouter router = new SearchProviderRouter(properties, runtimeSwitch, List.of(provider));
        SearchQuery query = new SearchQuery("company", 5);

        assertThat(router.search(query)).isEmpty();
        verify(provider, never()).search(query);
    }
}
