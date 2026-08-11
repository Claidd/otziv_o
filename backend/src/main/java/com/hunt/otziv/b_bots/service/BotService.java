package com.hunt.otziv.b_bots.service;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.l_lead.dto.LeadDTO;

import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.Authentication;

public interface BotService {

    // Создать нового бота
    boolean createBot(BotDTO botDTO, Authentication authentication);

    // Обновить бота
    boolean updateBot(BotDTO botDTO, Long id, Authentication authentication);

    // Удалить бота
    void deleteBot(Long id, Authentication authentication);

    // Найти бота по id
    BotDTO findById(Long id, Authentication authentication);
    BotDTO findByWorker(Principal principal);

    // Найти бота по id
    Bot findBotById(Long id);
    // Найти всех ботов
    List<BotDTO> getAllBots(Authentication authentication);

    List<Bot> getAllBotsByWorkerId(Long id);

    List<Bot> getAllBotsByWorkerIdActiveIsTrue(Long id);

    List<BotDTO> getAllBotsByWorkerActiveIsTrue(Authentication authentication);

    Bot save(Bot bot);

    List<Bot> getAllBotsByWorker(Principal principal);
    StatusBot changeStatus(String status);

    List<Bot> getFindAllByFilialCityId(Long cityId);

    List<Bot> getActiveBotsOutsideCityWithCounterAtLeast(Long cityId, int minCounter);

    Optional<Bot> claimReserveBotForCity(City targetCity, Collection<Long> excludedBotIds);

    Optional<Bot> claimNewAccountForCity(City targetCity, Collection<Long> excludedBotIds);

    Optional<Bot> claimNewAccountFromOwnCity(City targetCity, Collection<Long> excludedBotIds);
}
