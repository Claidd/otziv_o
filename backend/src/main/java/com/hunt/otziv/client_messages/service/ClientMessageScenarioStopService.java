package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ClientMessageScenarioStops;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.config.settings.api.BackgroundAutomationSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClientMessageScenarioStopService implements ClientMessageScenarioStops {

    private final BackgroundAutomationSettings automationSettings;
    private final ClientMessageTime messageTime;

    @Override
    public String stopReason(String scenario) {
        ClientMessageScenario known;
        try {
            known = ClientMessageScenario.valueOf(scenario);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return null;
        }
        return ClientMessageAutomationPolicy.stopReason(automationSettings, known, messageTime.nowStorage());
    }
}
