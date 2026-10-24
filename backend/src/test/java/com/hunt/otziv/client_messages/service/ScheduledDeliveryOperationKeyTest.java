package com.hunt.otziv.client_messages.service;

import static org.assertj.core.api.Assertions.*;
import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.whatsapp.service.WhatsAppOperationKey;
import org.junit.jupiter.api.Test;

class ScheduledDeliveryOperationKeyTest {
    @Test void restartCannotReuseAnUncertainOrAnEarlierSuccessfulOperation() {
        var state = ScheduledClientMessageState.builder().id(23L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER).sentCount(2).build();
        var old = ScheduledDeliveryOperationKey.of(state);
        assertThat(old).isEqualTo(WhatsAppOperationKey.of("scheduled-client-message-v1", 23L,
                ClientMessageScenario.PAYMENT_REMINDER, 2));
        state.setDispatchGeneration(1);
        var restarted = ScheduledDeliveryOperationKey.of(state);
        assertThat(restarted).isNotEqualTo(old).isEqualTo(ScheduledDeliveryOperationKey.of(state));
        state.setSentCount(3);
        assertThat(ScheduledDeliveryOperationKey.of(state)).isNotEqualTo(restarted);
        state.setDispatchGeneration(-1);
        assertThatThrownBy(() -> ScheduledDeliveryOperationKey.of(state)).isInstanceOf(IllegalStateException.class);
    }
}
