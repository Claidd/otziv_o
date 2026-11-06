package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaxGroupLinkServiceTest {

    private static final String LINK_SECRET = "max-link-secret-for-tests-at-least-32-bytes";

    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final MaxBotClient maxBotClient = mock(MaxBotClient.class);
    private final MaxGroupLinkService service = new MaxGroupLinkService(companyRepository, maxBotClient);

    @BeforeEach
    void configureLinkSecret() {
        ReflectionTestUtils.setField(service, "linkSecret", LINK_SECRET);
    }

    @Test
    void usesBotStartUrlByDefaultEvenWhenMaxWebGroupUrlIsPresent() {
        ReflectionTestUtils.setField(service, "botUsername", "id380124742639_bot");
        String inviteUrl = service.buildInviteUrl(
                1160L,
                "https://web.max.ru/-72727178175095?utm=test",
                null
        );

        assertTrue(inviteUrl.startsWith("https://max.ru/id380124742639_bot?start=c"));
    }

    @Test
    void groupCommandBindsAGroupTheBotAlreadyServesForAnotherBranch() {
        ReflectionTestUtils.setField(service, "botUsername", "id380124742639_bot");
        Company company = new Company();
        company.setId(235L);
        company.setTitle("Кристалл, банкетный зал");
        company.setUrlChat("https://max.ru/join/6CnIZCfuUU3_other");
        company.setMaxLinkUserId(203090551L);
        when(companyRepository.findById(235L)).thenReturn(Optional.of(company));
        String inviteUrl = service.buildInviteUrl(235L, company.getUrlChat(), null);
        String payload = inviteUrl.substring(inviteUrl.indexOf("start=") + "start=".length());

        Optional<String> response = service.handleGroupStartCommand(-74761808437879L, 203090551L, payload);

        assertTrue(response.orElse("").contains("Кристалл, банкетный зал"));
        assertEquals(-74761808437879L, company.getMaxGroupChatId());
        assertEquals(null, company.getMaxLinkUserId());
        verify(companyRepository).save(company);

        Optional<String> reused = service.handleGroupStartCommand(-74761808437879L, 203090551L, payload);
        assertTrue(reused.orElse("").contains("устарела или уже использована"));
    }

    @Test
    void groupCommandAfterTheLinkWasOpenedUsesThatFreshStartOfTheSameUser() {
        ReflectionTestUtils.setField(service, "botUsername", "id380124742639_bot");
        Company company = new Company();
        company.setId(235L);
        company.setTitle("Кристалл, банкетный зал");
        company.setUrlChat("https://max.ru/join/6CnIZCfuUU3_other");
        when(companyRepository.findById(235L)).thenReturn(Optional.of(company));
        String inviteUrl = service.buildInviteUrl(235L, company.getUrlChat(), null);
        String payload = inviteUrl.substring(inviteUrl.indexOf("start=") + "start=".length());
        String started = service.handleBotStarted(203090551L, payload).orElse("");
        // The private reply names the group command: MAX never repeats "bot added" for a served group.
        assertTrue(started.contains("Если бот уже состоит в этой группе"), started);
        assertTrue(started.endsWith("команду:\n/start " + payload), started);
        when(companyRepository.findFirstByMaxLinkUserIdOrderByMaxLinkRequestedAtDesc(203090551L))
                .thenReturn(Optional.of(company));

        Optional<String> response = service.handleGroupStartCommand(-74761808437879L, 203090551L, payload);

        assertTrue(response.orElse("").contains("привязана"));
        assertEquals(-74761808437879L, company.getMaxGroupChatId());
    }

    @Test
    void spentCommandFromAnotherUserIsNotBound() {
        when(companyRepository.findFirstByMaxLinkUserIdOrderByMaxLinkRequestedAtDesc(999L)).thenReturn(Optional.empty());

        Optional<String> response = service.handleGroupStartCommand(-74761808437879L, 999L,
                "cAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertTrue(response.orElse("").contains("устарела или уже использована"));
        verify(companyRepository, never()).save(org.mockito.ArgumentMatchers.any());
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
    void linksBotAddedEventByMaxWebGroupUrlWithoutProtocol() {
        Company company = new Company();
        company.setId(1161L);
        company.setTitle("Новая MAX фирма");
        company.setUrlChat("web.max.ru/-72727178175095");

        when(companyRepository.findTop3ByMaxGroupChatIdIsNullAndUrlChatContaining("-72727178175095"))
                .thenReturn(List.of(company));

        Optional<String> response = service.handleBotAdded(-72727178175095L, null);

        assertTrue(response.orElse("").contains("Новая MAX фирма"));
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
    void linksBotAddedEventByMaxJoinLinkWithoutProtocolFromChatInfo() throws Exception {
        Company company = new Company();
        company.setId(1161L);
        company.setTitle("Новая MAX фирма");
        company.setUrlChat("max.ru/join/9zHtzGdJS6B0P0kHGaH6jUGUrRURXAM5E0yXBmz4PFc");

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

        assertTrue(response.orElse("").contains("Новая MAX фирма"));
        assertEquals(-74924486091383L, company.getMaxGroupChatId());
        verify(companyRepository).save(company);
    }

    @Test
    void refusesPendingCompanyWhenAddedChatHasDifferentJoinLink() throws Exception {
        Company company = new Company();
        company.setId(1160L);
        company.setTitle("Метролог Групп");
        company.setUrlChat("https://max.ru/join/expectedJoinToken123");
        company.setMaxLinkRequestedAt(LocalDateTime.now());

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

    @Test
    void refusesPendingJoinLinkWhenChatApiDoesNotConfirmItsLink() {
        Company company = new Company();
        company.setId(1160L);
        company.setTitle("Метролог Групп");
        company.setUrlChat("https://max.ru/join/expectedJoinToken123");
        company.setMaxLinkRequestedAt(LocalDateTime.now());

        when(maxBotClient.getChat(-74924486091383L)).thenReturn(null);
        when(companyRepository.findFirstByMaxLinkUserIdOrderByMaxLinkRequestedAtDesc(203090551L))
                .thenReturn(Optional.of(company));

        Optional<String> response = service.handleBotAdded(-74924486091383L, 203090551L);

        assertTrue(response.orElse("").contains("не совпадает"));
        verify(companyRepository, never()).save(company);
    }
}
