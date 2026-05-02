package com.hunt.otziv.b_bots.services;

import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.repository.StatusBotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class StatusBotServiceImpl implements StatusBotService{

    private final StatusBotRepository statusBotRepository;

    public StatusBotServiceImpl(StatusBotRepository statusBotRepository) {
        this.statusBotRepository = statusBotRepository;
    }

    @Override
    public StatusBot findByTitle(String botStatus) { // Найти статус по названию
        return statusBotRepository.findByBotStatusTitle(botStatus).orElse(null);
    } // Найти статус по названию

    @Override
    public List<String> findAllBotsStatus() { // Взять все статусы
        return statusBotRepository.findAllByBotStatusTitle();
    } // Взять все статусы
}
