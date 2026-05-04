package com.hunt.otziv.r_review.utils;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.hunt.otziv.r_review.utils.ReviewBoardSearch.hasText;
import static com.hunt.otziv.r_review.utils.ReviewBoardSearch.keywordPredicate;
import static com.hunt.otziv.r_review.utils.ReviewBoardSearch.parseKeywordLong;
import static com.hunt.otziv.r_review.utils.ReviewBoardSearch.parseKeywordUuid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewBoardSearchTest {

    @Test
    void hasTextTreatsBlankValuesAsEmpty() {
        assertFalse(hasText(null));
        assertFalse(hasText(""));
        assertFalse(hasText("   "));
        assertTrue(hasText(" заказ "));
    }

    @Test
    void parseKeywordLongAcceptsOnlyWholeNumbers() {
        assertEquals(42L, parseKeywordLong(" 42 "));
        assertNull(parseKeywordLong("42a"));
        assertNull(parseKeywordLong("-42"));
        assertNull(parseKeywordLong("999999999999999999999999"));
    }

    @Test
    void parseKeywordUuidAcceptsTrimmedUuid() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000042");

        assertEquals(uuid, parseKeywordUuid(" 00000000-0000-0000-0000-000000000042 "));
        assertNull(parseKeywordUuid("42"));
        assertNull(parseKeywordUuid("not-a-uuid"));
    }

    @Test
    void keywordPredicateAddsNumericAndUuidTermsOnlyWhenApplicable() {
        String textOnly = keywordPredicate(false, false);
        assertTrue(textOnly.contains("LOWER(COALESCE(r.text, '')) LIKE :keyword"));
        assertTrue(textOnly.contains("LOWER(COALESCE(d.comment, '')) LIKE :keyword"));
        assertFalse(textOnly.contains(":keywordLong"));
        assertFalse(textOnly.contains(":keywordUuid"));

        String withNumericAndUuid = keywordPredicate(true, true);
        assertTrue(withNumericAndUuid.contains("r.id = :keywordLong"));
        assertTrue(withNumericAndUuid.contains("o.id = :keywordLong"));
        assertTrue(withNumericAndUuid.contains("c.id = :keywordLong"));
        assertTrue(withNumericAndUuid.contains("d.id = :keywordUuid"));
    }
}
