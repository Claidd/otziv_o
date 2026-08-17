package com.hunt.otziv.contractor_payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContractorPaymentTransferNumberTest {

    @Test
    void normalizesPhoneFormattingWithoutChangingItsMeaning() {
        assertEquals(
                "+79991234567",
                ContractorPaymentTransferNumber.normalize("  +7 (999) 123-45-67  ")
        );
        assertTrue(ContractorPaymentTransferNumber.isValid("+7 (999) 123-45-67"));
    }

    @Test
    void acceptsAndCanonicalizesCardWithUnicodeSpacesAndHyphens() {
        String formattedCard = "2202\u00a02082\u20113839 6676";

        assertEquals("2202208238396676", ContractorPaymentTransferNumber.normalize(formattedCard));
        assertTrue(ContractorPaymentTransferNumber.isValid(formattedCard));
        assertTrue(ContractorPaymentTransferNumber.isValid("1".repeat(19)));
    }

    @Test
    void enforcesPhoneAndCardDigitBoundaries() {
        assertTrue(ContractorPaymentTransferNumber.isValid("1".repeat(10)));
        assertTrue(ContractorPaymentTransferNumber.isValid("1".repeat(15)));
        assertTrue(ContractorPaymentTransferNumber.isValid("1".repeat(16)));
        assertFalse(ContractorPaymentTransferNumber.isValid("1".repeat(9)));
        assertFalse(ContractorPaymentTransferNumber.isValid("1".repeat(20)));
        assertFalse(ContractorPaymentTransferNumber.isValid("+" + "1".repeat(16)));
    }

    @Test
    void rejectsUnexpectedPunctuationLettersAndUnicodeDigits() {
        assertFalse(ContractorPaymentTransferNumber.isValid("2202_2082_3839_6676"));
        assertFalse(ContractorPaymentTransferNumber.isValid("2202 2082 3839 667X"));
        assertFalse(ContractorPaymentTransferNumber.isValid("++79991234567"));
        assertFalse(ContractorPaymentTransferNumber.isValid("٢".repeat(16)));
        assertFalse(ContractorPaymentTransferNumber.isValid(null));
    }
}
