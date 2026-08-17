package com.hunt.otziv.client_messages.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramTransferCopyButtonTest {

    @Test
    void buildsCardButtonWithCanonicalFrozenValue() {
        TelegramTransferCopyButton button = TelegramTransferCopyButton
                .fromFrozenTransferNumber("2202 2082-3839 6676")
                .orElseThrow();

        assertEquals("Скопировать номер карты", button.text());
        assertEquals("2202208238396676", button.copyText());
    }

    @Test
    void buildsPhoneButtonAndRejectsUntrustedText() {
        TelegramTransferCopyButton button = TelegramTransferCopyButton
                .fromFrozenTransferNumber("+7 (999) 123-45-67")
                .orElseThrow();

        assertEquals("Скопировать номер телефона", button.text());
        assertEquals("+79991234567", button.copyText());
        assertTrue(TelegramTransferCopyButton.fromFrozenTransferNumber("Ссылка на оплату").isEmpty());
    }
}
