package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.whatsapp.service.WhatsAppOperationKey;

final class ScheduledDeliveryOperationKey {
    private ScheduledDeliveryOperationKey() { }

    static String of(ScheduledClientMessageState state) {
        if (state.getDispatchGeneration() < 0) throw new IllegalStateException("Invalid dispatch generation");
        return state.getDispatchGeneration() == 0
                ? WhatsAppOperationKey.of("scheduled-client-message-v1", state.getId(), state.getScenario(), state.getSentCount())
                : WhatsAppOperationKey.of("scheduled-client-message-v2", state.getId(), state.getScenario(),
                        state.getDispatchGeneration(), state.getSentCount());
    }
}
