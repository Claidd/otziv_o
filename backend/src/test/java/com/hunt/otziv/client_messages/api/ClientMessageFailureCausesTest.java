package com.hunt.otziv.client_messages.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every failure a manager-control card shows comes with a cause and a next step in plain words. */
class ClientMessageFailureCausesTest {

    @ParameterizedTest
    @ValueSource(strings = {"gateway_not_ready", "draining", "operation_unknown", "whatsapp_group_unavailable",
            "whatsapp_group_missing", "telegram_group_missing", "bot_removed", "max_group_missing", "missing_client",
            "empty_client_url", "payment_instruction_failed", "order_missing", "rolled_back_before_send", "message_empty"})
    void knownFailuresExplainTheCauseAndTheNextStep(String code) {
        var cause = ClientMessageFailureCauses.of(code.toUpperCase());

        assertThat(cause).isPresent();
        assertThat(cause.get().text()).startsWith("Причина: ").contains(" Что сделать: ");
        assertThat(cause.get().text()).doesNotContain("подробности сбоя сохранены");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "something_new"})
    void anUnknownCodeHasNoInventedCause(String code) {
        assertThat(ClientMessageFailureCauses.of(code)).isEmpty();
        assertThat(ClientMessageFailureCauses.of(null)).isEmpty();
    }
}
