package com.hunt.otziv.b_bots.services;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.l_lead.dto.LeadDTO;

import java.security.Principal;
import java.util.List;

public interface BotService {

    // Создать нового бота
    boolean createBot(BotDTO botDTO, Principal principal);

    // Обновить бота
    boolean updateBot(BotDTO botDTO, Long id);

    // Удалить бота
    void deleteBot(Long id);

    // Найти бота по id
    BotDTO findById(Long id);

    // Найти бота по id
    Bot findBotById(Long id);
    // Найти всех ботов
    List<BotDTO> getAllBots();
}
