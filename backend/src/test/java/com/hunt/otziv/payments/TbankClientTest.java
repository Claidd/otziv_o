package com.hunt.otziv.payments;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TbankClientTest {

    @Test
    void formatsRedirectDueDateWithoutFractionalSeconds() {
        OffsetDateTime value = OffsetDateTime.parse("2026-05-22T19:00:01.123456789+03:00");

        assertEquals("2026-05-22T19:00:01+03:00", TbankClient.formatRedirectDueDate(value));
    }
}
