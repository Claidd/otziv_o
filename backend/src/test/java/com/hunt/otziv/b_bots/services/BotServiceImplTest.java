package com.hunt.otziv.b_bots.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.r_review.bot.ReviewBotCooldownService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotServiceImplTest {

    @Mock
    private UserService userService;

    @Mock
    private StatusBotService statusBotService;

    @Mock
    private BotsRepository botsRepository;

    @Mock
    private WorkerService workerService;

    @Mock
    private BusinessAuditService businessAuditService;

    @Mock
    private ReviewBotCooldownService botCooldownService;

    @Test
    void claimNewAccountFromOwnCityUsesOnlyReadyActiveAccountInTargetCity() {
        BotServiceImpl service = service();
        City city = city(320L, "Город 320");
        Bot excluded = bot(10L, "Впиши Имя Фамилию", true, "Новый");
        Bot inactive = bot(11L, "Впиши Имя Фамилию", false, "Новый");
        Bot wrongStatus = bot(12L, "Впиши Имя Фамилию", true, "В работе");
        Bot selected = bot(13L, "Впиши Имя Фамилию", true, "Новый");

        when(botsRepository.findBotsByFioAndCity("Впиши Имя Фамилию", 320L))
                .thenReturn(List.of(excluded, inactive, wrongStatus, selected));
        when(botCooldownService.isAvailableForAssignment(wrongStatus)).thenReturn(true);
        when(botCooldownService.isAvailableForAssignment(selected)).thenReturn(true);

        Optional<Bot> result = service.claimNewAccountFromOwnCity(city, Set.of(10L));

        assertTrue(result.isPresent());
        assertEquals(13L, result.get().getId());
        assertEquals(320L, result.get().getBotCity().getId());
        verify(botsRepository, never()).save(selected);
    }

    private BotServiceImpl service() {
        return new BotServiceImpl(
                userService,
                statusBotService,
                botsRepository,
                workerService,
                businessAuditService,
                botCooldownService
        );
    }

    private Bot bot(Long id, String fio, boolean active, String statusTitle) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setFio(fio);
        bot.setActive(active);
        bot.setBotCity(city(320L, "Город 320"));
        StatusBot status = new StatusBot();
        status.setBotStatusTitle(statusTitle);
        bot.setStatus(status);
        return bot;
    }

    private City city(Long id, String title) {
        City city = new City();
        city.setId(id);
        city.setTitle(title);
        return city;
    }
}
