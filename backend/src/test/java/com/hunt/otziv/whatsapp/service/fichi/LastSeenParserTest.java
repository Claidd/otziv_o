package com.hunt.otziv.whatsapp.service.fichi;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LastSeenParserTest {

    @Test
    void parseNumericDateWithTime() {
        assertEquals(
                LocalDateTime.of(2025, 6, 25, 7, 30),
                LastSeenParser.parse("25.06.2025 в 07:30").orElseThrow()
        );
    }

    @Test
    void parseIgnoresPhonePrefixBeforeStatusText() {
        assertEquals(
                LocalDateTime.of(2025, 6, 25, 7, 30),
                LastSeenParser.parse("+7 902 123 45 67 был(-а): 25.06.2025 в 07:30").orElseThrow()
        );
    }

    @Test
    void parseReturnsEmptyForBlankOrUnknownStatus() {
        assertTrue(LastSeenParser.parse(" ").isEmpty());
        assertTrue(LastSeenParser.parse("status is hidden").isEmpty());
    }
}
