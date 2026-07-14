package com.hunt.otziv.client_chat_control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.u_users.model.Manager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClientChatCompanyResolutionServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Test
    void keepsOneSlaOwnerForSharedWhatsappChatOfSameManager() {
        Manager manager = manager(7L);
        Company first = company(1L, "Компания А", manager);
        Company second = company(2L, "Компания Б", manager);
        when(companyRepository.findAllByGroupId("shared@g.us")).thenReturn(List.of(first, second));

        ClientChatCompanyResolutionService.Resolution result = service().resolve(
                ClientChatPlatform.WHATSAPP,
                "shared@g.us"
        );

        assertEquals(first, result.primaryCompany());
        assertEquals(manager, result.manager());
        assertEquals(2, result.companyCount());
        assertFalse(result.ambiguous());
        assertEquals("Компания А, Компания Б", result.companyTitles());
    }

    @Test
    void doesNotAssignAmbiguousSharedTelegramChatToEitherManager() {
        Company first = company(1L, "Компания А", manager(7L));
        Company second = company(2L, "Компания Б", manager(8L));
        when(companyRepository.findAllByTelegramGroupChatIdOrderById(-100123L)).thenReturn(List.of(first, second));

        ClientChatCompanyResolutionService.Resolution result = service().resolve(
                ClientChatPlatform.TELEGRAM,
                "-100123"
        );

        assertEquals(first, result.primaryCompany());
        assertNull(result.manager());
        assertTrue(result.ambiguous());
    }

    @Test
    void supportsSharedMaxChat() {
        Manager manager = manager(7L);
        Company first = company(1L, "Компания А", manager);
        Company second = company(2L, "Компания Б", manager);
        when(companyRepository.findAllByMaxGroupChatIdOrderById(555L)).thenReturn(List.of(first, second));

        ClientChatCompanyResolutionService.Resolution result = service().resolve(ClientChatPlatform.MAX, "555");

        assertEquals(manager, result.manager());
        assertEquals(2, result.companyCount());
        assertFalse(result.ambiguous());
    }

    private ClientChatCompanyResolutionService service() {
        return new ClientChatCompanyResolutionService(companyRepository);
    }

    private Manager manager(Long id) {
        Manager manager = new Manager();
        manager.setId(id);
        return manager;
    }

    private Company company(Long id, String title, Manager manager) {
        Company company = new Company();
        company.setId(id);
        company.setTitle(title);
        company.setManager(manager);
        return company;
    }
}
