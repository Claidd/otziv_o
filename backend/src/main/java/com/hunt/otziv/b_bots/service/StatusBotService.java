package com.hunt.otziv.b_bots.service;

import com.hunt.otziv.b_bots.model.StatusBot;

import java.util.List;

public interface StatusBotService {
    StatusBot findByTitle(String botStatus);

    List<String> findAllBotsStatus();
}
