package com.hunt.otziv.whatsapp.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.c_companies.api.MissingWhatsAppRoute;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.service.CompanyMissingWhatsAppRouteService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompanyMissingWhatsAppRouteServiceTest {
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final CompanyMissingWhatsAppRouteService service = new CompanyMissingWhatsAppRouteService(companies);
    @Test void snapshotIncludesTheCompanyOwnersBanPolicyWithoutExposingAnEntity() {
        var company = Company.builder().id(1L).urlChat("invite").status(CompanyStatus.builder().title("Бан").build()).build();
        when(companies.findByIdForCompanyDto(1L)).thenReturn(Optional.of(company));
        assertThat(service.find(1L)).contains(new MissingWhatsAppRoute.Snapshot(1L,"invite",null,false));
    }
    @Test void aConcurrentCompanyEditDoesNotBecomeSuccessfulRepair() {
        var snapshot = new MissingWhatsAppRoute.Snapshot(1L,"invite",null,true);
        assertThat(service.restore(snapshot,"123456789@g.us")).isFalse();
        when(companies.restoreMissingWhatsAppGroup(1L,"invite","123456789@g.us")).thenReturn(1);
        assertThat(service.restore(snapshot,"123456789@g.us")).isTrue();
    }
    @Test void blockedCompanyAndInvalidDestinationNeverReachTheWriteBoundary() {
        assertThat(service.restore(new MissingWhatsAppRoute.Snapshot(1L,"invite",null,false),"123456789@g.us")).isFalse();
        assertThat(service.restore(new MissingWhatsAppRoute.Snapshot(1L,"invite",null,true),"other-chat")).isFalse();
        verifyNoInteractions(companies);
    }
}
