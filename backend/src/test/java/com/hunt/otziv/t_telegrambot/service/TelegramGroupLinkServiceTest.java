package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.util.ReflectionTestUtils.setField;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramGroupLinkServiceTest {

    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ManagerRepository managerRepository = mock(ManagerRepository.class);
    private final TelegramGroupLinkService service =
            new TelegramGroupLinkService(companyRepository, userRepository, managerRepository);

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

    @Test
    void linksWorkerGroupByPublicTelegramUsernameWhenCompanyNotMatched() {
        User worker = new User();
        worker.setId(77L);
        worker.setUsername("worker");
        worker.setFio("Специалист");
        worker.setWorkerChatUrl("https://t.me/worker_reviews");

        when(companyRepository.findTop3ByTelegramGroupChatIdIsNullAndUrlChatContainingIgnoreCase("worker_reviews"))
                .thenReturn(List.of());
        when(userRepository.findTop3ByWorkerTelegramGroupChatIdIsNullAndWorkerChatUrlContainingIgnoreCase("worker_reviews"))
                .thenReturn(List.of(worker));

        Optional<String> response = service.handleBotAddedToGroup(-1001234567891L, "worker_reviews", "Специалист");

        assertTrue(response.orElse("").contains("Специалист"));
        assertEquals(-1001234567891L, worker.getWorkerTelegramGroupChatId());
        verify(userRepository).save(worker);
    }

    @Test
    void linksWorkerGroupByStartCommandPayload() {
        User worker = new User();
        worker.setId(77L);
        worker.setUsername("worker");
        worker.setFio("Специалист");
        worker.setWorkerChatUrl("https://t.me/+invite");
        setField(service, "botUsername", "O_Company_Bot");

        when(userRepository.findById(77L)).thenReturn(Optional.of(worker));

        String inviteUrl = service.buildWorkerInviteUrl(worker);
        String payload = inviteUrl.substring(inviteUrl.indexOf("startgroup=") + "startgroup=".length());
        Optional<String> response = service.handleGroupStartCommand(-1001234567891L, "/start " + payload);

        assertTrue(response.orElse("").contains("Специалист"));
        assertEquals(-1001234567891L, worker.getWorkerTelegramGroupChatId());
        verify(userRepository).save(worker);
    }

    @Test
    void linksManagerAuditGroupByStartCommandPayload() {
        User user = new User();
        user.setId(17L);
        user.setUsername("lika");
        user.setFio("Анжелика Б.");
        Manager manager = Manager.builder()
                .id(9L)
                .user(user)
                .auditTelegramGroupUrl("https://t.me/+manager-audit")
                .build();
        setField(service, "botUsername", "O_Company_Bot");
        when(managerRepository.findByIdWithUser(9L)).thenReturn(Optional.of(manager));
        when(managerRepository.findByAuditTelegramGroupChatId(-100900L)).thenReturn(Optional.empty());

        String inviteUrl = service.buildManagerAuditInviteUrl(manager);
        String payload = inviteUrl.substring(inviteUrl.indexOf("startgroup=") + "startgroup=".length());
        Optional<String> response = service.handleGroupStartCommand(-100900L, "/start " + payload);

        assertTrue(response.orElse("").contains("Анжелика"));
        assertEquals(-100900L, manager.getAuditTelegramGroupChatId());
        verify(managerRepository).save(manager);
    }

    @Test
    void doesNotTreatBotStartGroupInviteAsCompanyChatUrl() {
        Company company = new Company();
        company.setId(285L);
        company.setUrlChat("https://t.me/O_Company_Bot?startgroup=c285_abcd");
        setField(service, "botUsername", "O_Company_Bot");

        assertEquals("", service.buildInviteUrl(company));
    }
}
