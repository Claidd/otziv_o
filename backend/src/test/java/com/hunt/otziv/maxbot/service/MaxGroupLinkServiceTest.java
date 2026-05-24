package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaxGroupLinkServiceTest {

    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final MaxBotClient maxBotClient = mock(MaxBotClient.class);
    private final MaxGroupLinkService service = new MaxGroupLinkService(companyRepository, maxBotClient);

    @Test
    void usesBotStartUrlByDefaultEvenWhenMaxWebGroupUrlIsPresent() {
        ReflectionTestUtils.setField(service, "botUsername", "id380124742639_bot");
        ReflectionTestUtils.setField(service, "linkSecret", "test-secret");

        String inviteUrl = service.buildInviteUrl(
                1160L,
                "https://web.max.ru/-72727178175095?utm=test",
                null
        );

        assertTrue(inviteUrl.startsWith("https://max.ru/id380124742639_bot?start=c1160_"));
    }

    @Test
    void linksBotAddedEventByMaxWebGroupUrl() {
        Company company = new Company();
        company.setId(1160L);
        company.setTitle("Метролог Групп");
        company.setUrlChat("https://web.max.ru/-72727178175095");

        when(companyRepository.findTop3ByMaxGroupChatIdIsNullAndUrlChatContaining("-72727178175095"))
                .thenReturn(List.of(company));

        Optional<String> response = service.handleBotAdded(-72727178175095L, null);

        assertTrue(response.orElse("").contains("Метролог Групп"));
        assertEquals(-72727178175095L, company.getMaxGroupChatId());
        verify(companyRepository).save(company);
    }

    @Test
    void linksBotAddedEventByMaxJoinLinkFromChatInfo() throws Exception {
        Company company = new Company();
        company.setId(1160L);
        company.setTitle("Метролог Групп");
        company.setUrlChat("https://max.ru/join/9zHtzGdJS6B0P0kHGaH6jUGUrRURXAM5E0yXBmz4PFc");

        JsonNode chat = new ObjectMapper().readTree("""
                {
                  "chat_id": -74924486091383,
                  "link": "https://max.ru/join/9zHtzGdJS6B0P0kHGaH6jUGUrRURXAM5E0yXBmz4PFc"
                }
                """);

        when(maxBotClient.getChat(-74924486091383L)).thenReturn(chat);
        when(companyRepository.findTop3ByMaxGroupChatIdIsNullAndUrlChatContaining("9zHtzGdJS6B0P0kHGaH6jUGUrRURXAM5E0yXBmz4PFc"))
                .thenReturn(List.of(company));

        Optional<String> response = service.handleBotAdded(-74924486091383L, null);

        assertTrue(response.orElse("").contains("Метролог Групп"));
        assertEquals(-74924486091383L, company.getMaxGroupChatId());
        verify(companyRepository).save(company);
    }

    @Test
    void refusesPendingCompanyWhenAddedChatHasDifferentJoinLink() throws Exception {
        Company company = new Company();
        company.setId(1160L);
        company.setTitle("Метролог Групп");
        company.setUrlChat("https://max.ru/join/expectedJoinToken123");

        JsonNode chat = new ObjectMapper().readTree("""
                {
                  "chat_id": -74924486091383,
                  "link": "https://max.ru/join/actualJoinToken456"
                }
                """);

        when(maxBotClient.getChat(-74924486091383L)).thenReturn(chat);
        when(companyRepository.findTop3ByMaxGroupChatIdIsNullAndUrlChatContaining("actualJoinToken456"))
                .thenReturn(List.of());
        when(companyRepository.findFirstByMaxLinkUserIdOrderByMaxLinkRequestedAtDesc(203090551L))
                .thenReturn(Optional.of(company));

        Optional<String> response = service.handleBotAdded(-74924486091383L, 203090551L);

        assertTrue(response.orElse("").contains("не совпадает"));
        verify(companyRepository, never()).save(company);
    }
}
