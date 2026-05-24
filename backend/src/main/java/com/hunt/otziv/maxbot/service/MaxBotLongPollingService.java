package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "max.bot.long-polling", name = "enabled", havingValue = "true")
public class MaxBotLongPollingService {

    private static final String UPDATE_TYPES = "bot_started,bot_added,message_created";

    private final MaxBotClient maxBotClient;
    private final MaxBotUpdateService maxBotUpdateService;

    @Value("${max.bot.long-polling.timeout-seconds:25}")
    private int timeoutSeconds;

    private volatile Long marker;

    @Scheduled(fixedDelayString = "${max.bot.long-polling.fixed-delay-ms:30000}")
    public void pollUpdates() {
        JsonNode response = maxBotClient.getUpdates(marker, timeoutSeconds, UPDATE_TYPES);
        if (response == null || response.isNull()) {
            return;
        }

        JsonNode updates = response.path("updates");
        if (updates.isArray()) {
            updates.forEach(maxBotUpdateService::handleUpdate);
        }

        JsonNode nextMarker = response.path("marker");
        if (nextMarker.isNumber()) {
            marker = nextMarker.asLong();
        }
    }
}
