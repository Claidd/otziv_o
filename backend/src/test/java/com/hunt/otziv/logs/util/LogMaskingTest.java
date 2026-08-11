package com.hunt.otziv.logs.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogMaskingTest {

    @Test
    void masksPhoneKeepingOnlyLastFourDigits() {
        assertEquals("***7788", LogMasking.maskPhone("+7 (999) 555-77-88"));
        assertEquals("***", LogMasking.maskPhone("12"));
        assertEquals("", LogMasking.maskPhone(null));
    }

    @Test
    void masksEmailKeepingDomain() {
        assertEquals("u***@example.com", LogMasking.maskEmail("user@example.com"));
        assertEquals("***", LogMasking.maskEmail("not-an-email"));
        assertEquals("", LogMasking.maskEmail(""));
    }

    @Test
    void masksLongTokens() {
        assertEquals("abcd...wxyz", LogMasking.maskToken("abcdefghijklmnopqrstuvwxyz"));
        assertEquals("***", LogMasking.maskToken("short"));
    }

    @Test
    void masksPhoneCollections() {
        assertEquals("[***0000, ***1111]", LogMasking.maskPhones(List.of("+79990000000", "89991111111")));
    }
}
