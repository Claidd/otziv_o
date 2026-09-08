package com.hunt.otziv.c_companies.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyOrganizationIdentityRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanyOrganizationIdentityServiceTest {
    private final CompanyOrganizationIdentityRepository repository = mock(CompanyOrganizationIdentityRepository.class);
    private final TwoGisLinkResolver resolver = mock(TwoGisLinkResolver.class);
    private final CompanyOrganizationIdentityService service = new CompanyOrganizationIdentityService(repository, resolver);

    @Test
    void resolvesAndPersistsShortLinkButUsesDurableCacheOnSubsequentCalls() {
        when(resolver.resolve("https://go.2gis.com/h8eeD")).thenReturn("3659702978432070");
        assertThat(service.resolve("https://go.2gis.com/h8eeD/?x=1")).isEqualTo("3659702978432070");
        verify(repository).rememberShortLink("https://go.2gis.com/h8eeD", "3659702978432070");
        when(repository.resolvedShortLink("https://go.2gis.com/h8eeD")).thenReturn("3659702978432070");
        service.resolve("https://go.2gis.com/h8eeD");
        verify(resolver, times(1)).resolve(anyString());
    }

    @Test
    void creationAndUrlEditRejectExistingIdentity() {
        when(repository.conflictingFilial("3659702978432070", null)).thenReturn(2905L);
        var filial = Filial.builder().url("https://2gis.ru/firm/3659702978432070/").build();
        assertThatThrownBy(() -> service.prepareSave(filial, true)).hasMessageContaining("#2905");
        filial.setId(3485L);
        when(repository.conflictingFilial("3659702978432070", 3485L)).thenReturn(2905L);
        assertThatThrownBy(() -> service.prepareSave(filial, true)).hasMessageContaining("#2905");
    }

    @Test
    void unrelatedEditOfLegacyDuplicateKeepsHistoricalLink() {
        var filial = Filial.builder().id(3485L).twoGisOrganizationId("3659702978432070")
                .company(Company.builder().id(2514L).build()).build();
        service.prepareSave(filial, false);
        verify(repository).rememberCompany(2514L, "3659702978432070");
        verifyNoInteractions(resolver);
    }

    @Test
    void unresolvedShortLinkDoesNotInventAnIdentityAndOtherPlatformsRemainSupported() {
        when(resolver.resolve("https://go.2gis.com/broken")).thenThrow(new IllegalArgumentException("unavailable"));
        assertThatThrownBy(() -> service.resolve("https://go.2gis.com/broken")).hasMessage("unavailable");
        assertThat(service.resolve("https://vk.com/company")).isNull();
        verify(repository, never()).rememberShortLink(anyString(), anyString());
    }
}
