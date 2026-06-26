package com.hunt.otziv.admin.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.b_bots.repository.StatusBotRepository;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import com.hunt.otziv.uploads.service.FileUploadGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotImportServiceTest {

    private static final String DEFAULT_FIO = "Впиши Имя Фамилию";

    private BotsRepository botsRepository;
    private BotImportService service;

    @BeforeEach
    void setUp() {
        botsRepository = mock(BotsRepository.class);
        StatusBotRepository statusBotRepository = mock(StatusBotRepository.class);
        WorkerRepository workerRepository = mock(WorkerRepository.class);
        CityRepository cityRepository = mock(CityRepository.class);
        BusinessAuditService businessAuditService = mock(BusinessAuditService.class);
        FileUploadGuard fileUploadGuard = new FileUploadGuard(
                5_242_880,
                20_000_000,
                8_000,
                8_000,
                5_242_880,
                5_000
        );

        when(botsRepository.findExistingLogins(anyList())).thenReturn(Set.of());
        when(botsRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(statusBotRepository.findById(1L)).thenReturn(Optional.of(StatusBot.builder()
                .id(1L)
                .botStatusTitle("Новый")
                .build()));
        when(workerRepository.findById(5L)).thenReturn(Optional.of(Worker.builder()
                .id(5L)
                .build()));
        when(cityRepository.findById(325L)).thenReturn(City.builder()
                .id(325L)
                .title("Шаблон")
                .build());
        when(cityRepository.findById(326L)).thenReturn(City.builder()
                .id(326L)
                .title("Фламп")
                .build());

        service = new BotImportService(
                botsRepository,
                statusBotRepository,
                workerRepository,
                cityRepository,
                businessAuditService,
                fileUploadGuard
        );
    }

    @Test
    void importBotsKeepsFileFioWithoutCityOverride() {
        List<Bot> saved = importOne(null);

        assertThat(saved).singleElement()
                .satisfies(bot -> {
                    assertThat(bot.getFio()).isEqualTo("Иван Иванов");
                    assertThat(bot.getBotCity().getId()).isEqualTo(325L);
                });
    }

    @Test
    void importBotsForCityOverridesFileFioWithTemplateName() {
        List<Bot> saved = importOne(326L);

        assertThat(saved).singleElement()
                .satisfies(bot -> {
                    assertThat(bot.getFio()).isEqualTo(DEFAULT_FIO);
                    assertThat(bot.getBotCity().getId()).isEqualTo(326L);
                });
    }

    private List<Bot> importOne(Long cityId) {
        List<Bot> saved = new ArrayList<>();
        when(botsRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<Bot> bots = invocation.getArgument(0);
            bots.forEach(saved::add);
            return saved;
        });

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "bots.csv",
                "text/csv",
                ("bot_login;bot_password;bot_fio;bot_counter;bot_active;bot_status;bot_worker;bot_city_id\n" +
                        "79990000000;pass;Иван Иванов;0;1;1;5;325\n")
                        .getBytes(StandardCharsets.UTF_8));

        service.importBots(file, cityId);

        return saved;
    }
}
