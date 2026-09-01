package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BankPaymentInstructionSourceTest {

    @ParameterizedTest
    @ValueSource(strings = {"TBANK_LINK", "BANK_LINK", "TOCHKA_LINK", " tOcHkA_lInK "})
    void recognizesEverySupportedBankLinkAlias(String source) {
        assertThat(BankPaymentInstructionSource.isBankLink(source)).isTrue();
        assertThat(BankPaymentInstructionSource.normalize(source, "MANAGER_TEXT"))
                .isNotEqualTo(BankPaymentInstructionSource.MANAGER_TEXT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "MANAGER_TEXT", "UNKNOWN"})
    void neverTreatsManagerTextOrUnknownValuesAsBankLinks(String source) {
        assertThat(BankPaymentInstructionSource.isBankLink(source)).isFalse();
    }
}
