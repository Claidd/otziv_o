package com.hunt.otziv.l_lead.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeadPhoneNormalizerTest {

    @Test
    void normalizeConvertsRussianMobileFormatsToCanonicalSevenPrefix() {
        assertEquals("79021234567", LeadPhoneNormalizer.normalize("8 (902) 123-45-67"));
        assertEquals("79021234567", LeadPhoneNormalizer.normalize("+7 902 123 45 67"));
        assertEquals("79021234567", LeadPhoneNormalizer.normalize("9021234567"));
    }

    @Test
    void normalizeKeepsNonPhoneTextTrimmedAndNullAsEmptyString() {
        assertEquals("", LeadPhoneNormalizer.normalize(null));
        assertEquals("no phone", LeadPhoneNormalizer.normalize("  no phone  "));
    }

    @Test
    void variantsIncludeCanonicalPlusAndEightPrefixFormsInStableOrder() {
        assertEquals(
                List.of("79021234567", "+79021234567", "89021234567"),
                LeadPhoneNormalizer.variants("8 (902) 123-45-67")
        );
    }
}
