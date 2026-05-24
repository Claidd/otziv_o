package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SharedChatLinkSyncServiceTest {

    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final SharedChatLinkSyncService service = new SharedChatLinkSyncService(companyRepository);

    @Test
    void copiesKnownChatIdsToCompaniesWithSameChatLink() {
        Company whatsAppSource = company(1L, "Source WA", "https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv?mode=gi_t");
        whatsAppSource.setGroupId("120363123@g.us");
        Company whatsAppTarget = company(2L, "Target WA", "https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv/");

        Company telegramSource = company(3L, "Source TG", "https://t.me/shared_owner");
        telegramSource.setTelegramGroupChatId(-100123L);
        Company telegramTarget = company(4L, "Target TG", "https://telegram.me/@shared_owner");

        Company maxSource = company(5L, "Source MAX", "https://max.ru/join/SharedToken123");
        maxSource.setMaxGroupChatId(-700L);
        Company maxTarget = company(6L, "Target MAX", "web.max.ru/join/SharedToken123/");

        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(
                whatsAppSource,
                whatsAppTarget,
                telegramSource,
                telegramTarget,
                maxSource,
                maxTarget
        ));

        SharedChatLinkSyncResponse response = service.syncSharedChatIds();

        assertEquals("120363123@g.us", whatsAppTarget.getGroupId());
        assertEquals(-100123L, telegramTarget.getTelegramGroupChatId());
        assertEquals(-700L, maxTarget.getMaxGroupChatId());
        assertEquals(6, response.scannedCompanies());
        assertEquals(3, response.sharedChatGroups());
        assertEquals(3, response.updatedCompanies());
        assertEquals(1, response.whatsappLinked());
        assertEquals(1, response.telegramLinked());
        assertEquals(1, response.maxLinked());
        assertEquals(0, response.conflictGroups());
        verify(companyRepository).saveAll(any());
    }

    @Test
    void copiesOnlyChatIdMatchingMessengerInCurrentChatLink() {
        Company source = company(1L, "Source", "https://t.me/shared_owner");
        source.setGroupId("120363123@g.us");
        Company target = company(2L, "Target", "https://telegram.me/@shared_owner");

        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(source, target));

        SharedChatLinkSyncResponse response = service.syncSharedChatIds();

        assertNull(target.getGroupId());
        assertEquals(0, response.updatedCompanies());
        assertEquals(0, response.whatsappLinked());
        assertEquals(0, response.telegramLinked());
        assertEquals(0, response.maxLinked());
        verify(companyRepository, never()).saveAll(any());
    }

    @Test
    void skipsPlatformWhenSharedLinkHasConflictingIds() {
        Company first = company(1L, "First", "https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv");
        first.setGroupId("120363111@g.us");
        Company second = company(2L, "Second", "https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv");
        second.setGroupId("120363222@g.us");
        Company third = company(3L, "Third", "https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv");

        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(first, second, third));

        SharedChatLinkSyncResponse response = service.syncSharedChatIds();

        assertNull(third.getGroupId());
        assertEquals(0, response.updatedCompanies());
        assertEquals(0, response.whatsappLinked());
        assertEquals(1, response.conflictGroups());
        verify(companyRepository, never()).saveAll(any());
    }

    private Company company(Long id, String title, String urlChat) {
        Company company = new Company();
        company.setId(id);
        company.setTitle(title);
        company.setUrlChat(urlChat);
        return company;
    }
}
