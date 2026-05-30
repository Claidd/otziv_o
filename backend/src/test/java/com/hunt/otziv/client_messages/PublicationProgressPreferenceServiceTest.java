package com.hunt.otziv.client_messages;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicationProgressPreferenceServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Test
    void whatsappDisableCommandTurnsOffCompanyReports() {
        PublicationProgressPreferenceService service = new PublicationProgressPreferenceService(companyRepository);
        Company company = company(10L);
        when(companyRepository.findAllByGroupId("group")).thenReturn(List.of(company));

        Optional<PublicationProgressPreferenceService.PreferenceUpdate> result =
                service.handleWhatsAppCommand("group", "Отключить уведомления");

        assertTrue(result.isPresent());
        assertFalse(company.isPublicationProgressReportsEnabled());
        assertFalse(result.get().enabled());
        verify(companyRepository).save(company);
    }

    @Test
    void whatsappSingularDisableCommandTurnsOffCompanyReports() {
        PublicationProgressPreferenceService service = new PublicationProgressPreferenceService(companyRepository);
        Company company = company(13L);
        when(companyRepository.findAllByGroupId("group")).thenReturn(List.of(company));

        Optional<PublicationProgressPreferenceService.PreferenceUpdate> result =
                service.handleWhatsAppCommand("group", "отключить оповещение о каждой публикации");

        assertTrue(result.isPresent());
        assertFalse(company.isPublicationProgressReportsEnabled());
        assertFalse(result.get().enabled());
        verify(companyRepository).save(company);
    }

    @Test
    void telegramEnableCommandTurnsOnCompanyReports() {
        PublicationProgressPreferenceService service = new PublicationProgressPreferenceService(companyRepository);
        Company company = company(11L);
        company.setPublicationProgressReportsEnabled(false);
        when(companyRepository.findByTelegramGroupChatId(-100L)).thenReturn(Optional.of(company));

        Optional<PublicationProgressPreferenceService.PreferenceUpdate> result =
                service.handleTelegramCommand(-100L, "включить уведомления");

        assertTrue(result.isPresent());
        assertTrue(company.isPublicationProgressReportsEnabled());
        assertTrue(result.get().enabled());
        verify(companyRepository).save(company);
    }

    @Test
    void whatsappStopNotificationsAliasTurnsOffCompanyReports() {
        PublicationProgressPreferenceService service = new PublicationProgressPreferenceService(companyRepository);
        Company company = company(12L);
        when(companyRepository.findAllByGroupId("group")).thenReturn(List.of(company));

        Optional<PublicationProgressPreferenceService.PreferenceUpdate> result =
                service.handleWhatsAppCommand("group", "стоп уведомления");

        assertTrue(result.isPresent());
        assertFalse(company.isPublicationProgressReportsEnabled());
        assertFalse(result.get().enabled());
        verify(companyRepository).save(company);
    }

    @Test
    void whatsappDisableCommandTurnsOffAllCompaniesLinkedToSameGroup() {
        PublicationProgressPreferenceService service = new PublicationProgressPreferenceService(companyRepository);
        Company first = company(14L);
        Company second = company(15L);
        when(companyRepository.findAllByGroupId("group")).thenReturn(List.of(first, second));

        Optional<PublicationProgressPreferenceService.PreferenceUpdate> result =
                service.handleWhatsAppCommand("group", "отключить уведомления");

        assertTrue(result.isPresent());
        assertFalse(first.isPublicationProgressReportsEnabled());
        assertFalse(second.isPublicationProgressReportsEnabled());
        verify(companyRepository).save(first);
        verify(companyRepository).save(second);
    }

    @Test
    void maxUnknownTextIsIgnored() {
        PublicationProgressPreferenceService service = new PublicationProgressPreferenceService(companyRepository);

        Optional<PublicationProgressPreferenceService.PreferenceUpdate> result =
                service.handleMaxCommand(200L, "спасибо");

        assertTrue(result.isEmpty());
    }

    private Company company(Long id) {
        Company company = new Company();
        company.setId(id);
        company.setTitle("Компания");
        company.setPublicationProgressReportsEnabled(true);
        return company;
    }
}
