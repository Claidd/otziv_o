package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramGroupLinkServiceTest {

    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final TelegramGroupLinkService service = new TelegramGroupLinkService(companyRepository);

    @Test
    void linksBotAddedEventByPublicTelegramUsername() {
        Company company = new Company();
        company.setId(22860L);
        company.setTitle("Св-Моторс");
        company.setUrlChat("https://t.me/sv_motor_reviews");

        when(companyRepository.findTop3ByTelegramGroupChatIdIsNullAndUrlChatContainingIgnoreCase("sv_motor_reviews"))
                .thenReturn(List.of(company));

        Optional<String> response = service.handleBotAddedToGroup(-1001234567890L, "sv_motor_reviews", "Св-Моторс");

        assertTrue(response.orElse("").contains("Св-Моторс"));
        assertEquals(-1001234567890L, company.getTelegramGroupChatId());
        verify(companyRepository).save(company);
    }

    @Test
    void ignoresPrivateTelegramGroupWithoutPublicUsername() {
        Optional<String> response = service.handleBotAddedToGroup(-1001234567890L, null, "Св-Моторс");

        assertTrue(response.isEmpty());
        verifyNoInteractions(companyRepository);
    }
}
